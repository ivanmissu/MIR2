package com.mir2.world;

/**
 * Durable subset of an item carried in a player's bag.
 *
 * <p>The current combat slice only knows the client-visible name and {@code TStdItem.Looks}. A
 * later item-database slice will extend this value with the full {@code TClientItem} durability and
 * make-index fields without coupling the world persistence port to a map position.
 */
public record BackpackItem(String name, int looks) {
  public BackpackItem {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("item name must not be blank");
    if (looks < 0 || looks > 0xffff) throw new IllegalArgumentException("looks must be an unsigned word");
  }

  public static BackpackItem from(GroundItem item) {
    if (item == null) throw new NullPointerException("item");
    return new BackpackItem(item.name(), item.looks());
  }
}
