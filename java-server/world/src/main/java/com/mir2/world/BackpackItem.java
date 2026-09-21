package com.mir2.world;

import java.util.Objects;

/**
 * One item instance in a player's bag: the template plus the per-instance fields of
 * Delphi {@code TUserItem}/{@code TClientItem} — {@code MakeIndex}, {@code Dura} and
 * {@code DuraMax}.
 *
 * <p>{@code TUserEngine.CopyToUserItemFromName} (UsrEngn.pas:1624) creates instances at
 * full durability: {@code Dura := StdItem.DuraMax; DuraMax := StdItem.DuraMax} truncated
 * to the instance words. The instance DuraMax is therefore only the low word of the
 * template value.
 */
public record BackpackItem(StdItem item, int makeIndex, int dura, int duraMax) {

  public BackpackItem {
    Objects.requireNonNull(item, "item");
    if (makeIndex < 0) throw new IllegalArgumentException("makeIndex must not be negative");
    requireU16("dura", dura);
    requireU16("duraMax", duraMax);
  }

  /** Instance at full durability, mirroring {@code CopyToUserItemFromName}. */
  public static BackpackItem of(StdItem template, int makeIndex) {
    int full = (int) (template.duraMax() & 0xffff);
    return new BackpackItem(template, makeIndex, full, full);
  }

  public String name() {
    return item.name();
  }

  public int looks() {
    return item.looks();
  }

  /**
   * W03 rows were saved before make indexes existed and load with zero; the world engine
   * replaces such indexes while restoring the player so the client never sees duplicates.
   */
  public BackpackItem withMakeIndex(int newMakeIndex) {
    return new BackpackItem(item, newMakeIndex, dura, duraMax);
  }

  /** Returns the same item instance with updated current durability. */
  public BackpackItem withDura(int newDura) {
    return new BackpackItem(item, makeIndex, newDura, duraMax);
  }

  private static void requireU16(String field, int value) {
    if (value < 0 || value > 0xffff) throw new IllegalArgumentException(field + " must be an unsigned word");
  }
}
