package com.mir2.gate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The gate IP ban list — Delphi's {@code BlockIPList} / {@code TempBlockIPList} pair
 * (LoginGate/GateShare.pas:28-29, RunGate/GateShare.pas:93-94) and the {@code IsBlockIP}
 * predicate every gate runs before it will even look at a connection
 * (LoginGate/Main.pas:152-171, RunGate/Main.pas:1306-1333).
 *
 * <p><b>Two lists, deliberately.</b> Delphi keeps a persistent list loaded from
 * {@code .\BlockIPList.txt} ({@code LoadBlockIPFile}, GateShare.pas:86-101) and a volatile
 * "temp" list that only lives for the process lifetime. The operator UI can promote an address
 * from temp to permanent (IPaddrFilter.pas:166-189), and {@code SaveBlockIPList} writes only the
 * permanent one back to disk (GateShare.pas:103-113). This class mirrors that split: entries
 * loaded from the file are permanent, entries added at runtime by the admission code are
 * temporary, and only permanent entries would ever be written back.
 *
 * <p><b>The matching quirk is preserved.</b> LoginGate compares parsed 32-bit addresses
 * ({@code inet_addr}) for exact equality, but RunGate compares <em>strings</em> with two
 * different comparators in the same function (RunGate/Main.pas:1313-1332):
 * <ul>
 *   <li>the temp list uses {@code CompareText} — a full, case-insensitive equality test;</li>
 *   <li>the permanent list uses {@code CompareLStr(sIPaddr, sBlockIPaddr, Length(sBlockIPaddr))}
 *       (Common/HUtil32.pas:1158-1172) — a case-insensitive comparison of only the first
 *       {@code Length(entry)} characters, i.e. a <b>prefix</b> match.</li>
 * </ul>
 * The prefix behaviour is not a bug to fix: it is what lets an operator ban {@code 192.168.1.}
 * (or even {@code 192.168.}) and catch a whole range, and it is relied upon in the wild. It is
 * reproduced here verbatim, including the consequence that a permanent entry of {@code 1}
 * blocks every address starting with "1". Note the matching asymmetry that follows from
 * {@code CompareLStr}'s two length guards: an entry <em>longer</em> than the candidate address
 * never matches.
 *
 * <p>Blank lines are skipped when loading. Delphi's {@code LoadBlockIPFile} in LoginGate also
 * skips addresses that {@code inet_addr} rejects, but RunGate's simply does
 * {@code BlockIPList.LoadFromFile} with no validation at all (RunGate/GateShare.pas:172-181) —
 * which is precisely why the prefix entries above survive in the file. This loader keeps every
 * non-blank line so prefix bans work, matching RunGate.
 */
public final class BlockIpList {

  /** Entries from {@code BlockIPList.txt}: prefix-matched, survive a restart. */
  private final List<String> permanent = new CopyOnWriteArrayList<>();
  /** Entries banned at runtime: exact-matched, lost on restart (Delphi's TempBlockIPList). */
  private final List<String> temporary = new CopyOnWriteArrayList<>();

  public BlockIpList() {}

  private BlockIpList(Set<String> permanentEntries) {
    permanent.addAll(permanentEntries);
  }

  /** An empty list — the default for a gate started without a {@code BlockIPList.txt}. */
  public static BlockIpList empty() {
    return new BlockIpList();
  }

  /** Builds a list of permanent (prefix-matched) entries directly. */
  public static BlockIpList ofPermanent(Set<String> entries) {
    return new BlockIpList(normalise(entries));
  }

  /**
   * Reads {@code BlockIPList.txt}: one address (or address prefix) per line, blanks skipped.
   * Every surviving line becomes a permanent, prefix-matched entry — see the class note on why
   * no {@code inet_addr} validation happens here.
   */
  public static BlockIpList parse(Reader source) {
    Set<String> entries = new LinkedHashSet<>();
    try (BufferedReader reader = new BufferedReader(source)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) entries.add(trimmed);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("failed to read BlockIPList", failure);
    }
    return new BlockIpList(normalise(entries));
  }

  /**
   * {@code IsBlockIP} — checked before admission, before any protocol byte is read. Temp
   * entries match the whole address; permanent entries match as a prefix (see class note).
   */
  public boolean isBlocked(SocketAddress address) {
    return isBlocked(ip(address));
  }

  /** {@code IsBlockIP} against an already-extracted address string. */
  public boolean isBlocked(String ip) {
    if (ip == null || ip.isEmpty()) return false;
    String candidate = ip.toLowerCase(Locale.ROOT);
    for (String blocked : temporary) {
      // CompareText: full, case-insensitive equality.
      if (blocked.equals(candidate)) return true;
    }
    for (String blocked : permanent) {
      // CompareLStr(candidate, blocked, Length(blocked)): case-insensitive prefix match,
      // and never a match when the entry is longer than the address being tested.
      if (!blocked.isEmpty() && candidate.startsWith(blocked)) return true;
    }
    return false;
  }

  /**
   * {@code TempBlockIPList.Add} — the {@code mBlock} branch of {@code BlockMethod}. The entry
   * is exact-matched and disappears when the process restarts.
   */
  public void blockTemporarily(String ip) {
    String normalised = normaliseEntry(ip);
    if (normalised != null && !temporary.contains(normalised)) temporary.add(normalised);
  }

  /**
   * {@code BlockIPList.Add} — the {@code mBlockList} branch. Prefix-matched and, in Delphi,
   * written back to {@code BlockIPList.txt} by {@code SaveBlockIPList}.
   */
  public void blockPermanently(String ip) {
    String normalised = normaliseEntry(ip);
    if (normalised != null && !permanent.contains(normalised)) permanent.add(normalised);
  }

  /** The permanent entries, in insertion order — the set {@code SaveBlockIPList} would persist. */
  public List<String> permanentEntries() {
    return List.copyOf(permanent);
  }

  /** The runtime-only entries, in insertion order. */
  public List<String> temporaryEntries() {
    return List.copyOf(temporary);
  }

  public boolean isEmpty() {
    return permanent.isEmpty() && temporary.isEmpty();
  }

  /** Extracts the peer address the Delphi gates would have read from {@code RemoteAddress}. */
  public static String ip(SocketAddress address) {
    if (address instanceof InetSocketAddress inet) {
      InetAddress value = inet.getAddress();
      return value == null ? inet.getHostString() : value.getHostAddress();
    }
    return String.valueOf(address);
  }

  private static Set<String> normalise(Set<String> entries) {
    Set<String> normalised = new LinkedHashSet<>();
    if (entries != null) {
      for (String entry : entries) {
        String value = normaliseEntry(entry);
        if (value != null) normalised.add(value);
      }
    }
    return normalised;
  }

  private static String normaliseEntry(String entry) {
    if (entry == null) return null;
    String trimmed = entry.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  /** Snapshot of both lists, for logging and the operator-facing status line. */
  public List<String> allEntries() {
    List<String> all = new ArrayList<>(permanent.size() + temporary.size());
    all.addAll(permanent);
    all.addAll(temporary);
    return List.copyOf(all);
  }
}
