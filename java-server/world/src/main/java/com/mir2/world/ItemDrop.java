package com.mir2.world;

/**
 * One drop entry of a monster: {@code name} is the client-visible item name, {@code looks} is the
 * {@code TStdItem.Looks} index used by {@code SM_ITEMSHOW}, and {@code oneIn} is the Delphi-style
 * "1 in N" probability used by {@code MonItems} definitions.
 */
public record ItemDrop(String name, int looks, int oneIn) {
  public ItemDrop {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("item name must not be blank");
    if (looks < 0 || looks > 0xffff) throw new IllegalArgumentException("looks must be an unsigned word");
    if (oneIn < 1) throw new IllegalArgumentException("drop chance denominator must be positive");
  }

  public boolean always() {
    return oneIn == 1;
  }
}
