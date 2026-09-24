package com.mir2.gate;

import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@code IsBlockIP} (RunGate/Main.pas:1306-1333) — in particular the two different
 * comparators Delphi uses for its two ban lists, which is the behaviour operators rely on to
 * ban whole ranges.
 */
class BlockIpListTest {

  @Test
  void permanentEntriesMatchAsAPrefix() {
    // CompareLStr(candidate, entry, Length(entry)) compares only the first Length(entry)
    // characters, so a truncated entry bans an entire range.
    BlockIpList list = BlockIpList.ofPermanent(Set.of("192.168.1."));
    assertTrue(list.isBlocked("192.168.1.7"));
    assertTrue(list.isBlocked("192.168.1.250"));
    assertFalse(list.isBlocked("192.168.2.7"));
  }

  @Test
  void permanentEntryLongerThanTheAddressNeverMatches() {
    // CompareLStr bails out when Length(src) < compn: the candidate is shorter than the
    // entry, so the comparison is refused outright rather than treated as a partial hit.
    BlockIpList list = BlockIpList.ofPermanent(Set.of("10.0.0.1234"));
    assertFalse(list.isBlocked("10.0.0.1"));
    assertTrue(list.isBlocked("10.0.0.12345"), "candidate longer than the entry still matches");
  }

  @Test
  void temporaryEntriesRequireAFullMatch() {
    // The temp list uses CompareText — full equality, no prefix semantics.
    BlockIpList list = new BlockIpList();
    list.blockTemporarily("10.0.0.1");
    assertTrue(list.isBlocked("10.0.0.1"));
    assertFalse(list.isBlocked("10.0.0.10"), "temp entries are not prefixes");
    assertFalse(list.isBlocked("10.0.0."));
  }

  @Test
  void parsesTheClassicFileKeepingPrefixEntries() {
    // RunGate's LoadBlockIPFile is a bare LoadFromFile with no inet_addr validation
    // (GateShare.pas:172-181), which is exactly why prefix entries survive in the wild.
    BlockIpList list = BlockIpList.parse(new StringReader("""
        10.0.0.1

          192.168.
        172.16.0.5
        """));
    assertEquals(List.of("10.0.0.1", "192.168.", "172.16.0.5"), list.permanentEntries());
    assertTrue(list.isBlocked("192.168.99.99"), "prefix entry survived the load");
    assertTrue(list.isBlocked("10.0.0.1"));
    assertFalse(list.isBlocked("10.0.0.2"));
  }

  @Test
  void separatesPermanentFromTemporaryForPersistence() {
    // SaveBlockIPList writes only BlockIPList, never TempBlockIPList (GateShare.pas:103-113).
    BlockIpList list = BlockIpList.ofPermanent(Set.of("1.2.3.4"));
    list.blockTemporarily("5.6.7.8");
    assertEquals(List.of("1.2.3.4"), list.permanentEntries());
    assertEquals(List.of("5.6.7.8"), list.temporaryEntries());
    list.blockPermanently("9.10.11.12");
    assertEquals(List.of("1.2.3.4", "9.10.11.12"), list.permanentEntries());
  }

  @Test
  void matchesSocketAddressesAndIsEmptyByDefault() {
    assertTrue(BlockIpList.empty().isEmpty());
    assertFalse(BlockIpList.empty().isBlocked(new InetSocketAddress("127.0.0.1", 7000)));
    BlockIpList list = BlockIpList.ofPermanent(Set.of("127.0.0.1"));
    assertTrue(list.isBlocked(new InetSocketAddress("127.0.0.1", 7000)));
  }

  @Test
  void entriesAreDeduplicatedAndCaseInsensitive() {
    BlockIpList list = new BlockIpList();
    list.blockTemporarily("10.0.0.1");
    list.blockTemporarily("10.0.0.1");
    assertEquals(1, list.temporaryEntries().size());
    // IPv6 text is hex, and Delphi's comparators are case-insensitive throughout.
    BlockIpList v6 = BlockIpList.ofPermanent(Set.of("FE80::"));
    assertTrue(v6.isBlocked("fe80::1"));
  }
}
