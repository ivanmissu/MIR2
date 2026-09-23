package com.mir2.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The 禁止取下物品列表 — the Java form of Delphi's {@code g_DisableTakeOffList}
 * (M2Share.pas:1214-1216, {@code LoadDisableTakeOffList} / {@code InDisableTakeOffList}).
 *
 * <p>Delphi stores {@code name <TAB> itemIndex} pairs and {@code InDisableTakeOffList} matches
 * on the item index ({@code Objects[i] = nItemIdx - 1}). This engine keys the standard-item
 * catalog by name rather than by a numeric StdItems.DB index, so membership is tested by name —
 * the same identity every other item command already uses ({@code CompareText} / case-insensitive
 * equality). Names are compared case-insensitively to match Delphi's {@code CompareText} habits;
 * Chinese item names are unaffected either way.
 *
 * <p>The list gates three code paths verbatim: it blocks {@code ClientTakeOffItems}
 * (ObjBase.pas:17144 and :17259 — the take-off and take-off-by-name variants) and it skips a slot
 * inside {@code DropUseItems} (ObjBase.pas:15532) so a listed item is never dropped on death.
 * An empty list (the default, matching a server with no {@code DisableTakeOffList.txt}) never
 * blocks anything.
 */
public final class DisableTakeOffList {

  private static final DisableTakeOffList EMPTY = new DisableTakeOffList(Set.of());

  private final Set<String> names;

  private DisableTakeOffList(Set<String> names) {
    Set<String> lowered = new LinkedHashSet<>();
    for (String name : names) {
      if (name != null && !name.isBlank()) lowered.add(name.trim().toLowerCase());
    }
    this.names = Set.copyOf(lowered);
  }

  /** A server booted without a {@code DisableTakeOffList.txt}: nothing is locked. */
  public static DisableTakeOffList empty() {
    return EMPTY;
  }

  /** Builds a list from item names directly (test/bootstrap convenience). */
  public static DisableTakeOffList of(Set<String> names) {
    return names.isEmpty() ? EMPTY : new DisableTakeOffList(names);
  }

  /**
   * {@code InDisableTakeOffList} — true when the item may not be taken off (nor dropped on
   * death). Delphi matches on the StdItems.DB index; the name is this engine's stable identity.
   */
  public boolean contains(StdItem item) {
    return item != null && !names.isEmpty() && names.contains(item.name().toLowerCase());
  }

  public boolean isEmpty() {
    return names.isEmpty();
  }

  public int size() {
    return names.size();
  }

  /**
   * Parses the classic {@code DisableTakeOffList.txt} (M2Share.pas:4578, {@code LoadDisableTakeOffList}):
   * one entry per line, {@code ;}-prefixed lines and blank lines skipped, fields split on any of
   * space / {@code /} / {@code ,} / TAB ({@code GetValidStr3}). The first field is the item name;
   * the trailing numeric index is read for provenance but not required — this engine matches by
   * name. A line with only a name (no index) is still accepted, unlike Delphi which needs
   * {@code nItemIdx >= 0}; the divergence is documented because the engine has no index column to
   * enforce.
   */
  public static DisableTakeOffList parse(Reader source) {
    Set<String> names = new LinkedHashSet<>();
    try (BufferedReader reader = new BufferedReader(source)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == ';') continue;
        // GetValidStr3 splits on [space / , TAB]; the first token is the item name.
        String[] fields = trimmed.split("[ /,\\t]+", 2);
        String name = fields[0].trim();
        if (!name.isEmpty()) names.add(name);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("failed to read DisableTakeOffList", failure);
    }
    return of(names);
  }
}
