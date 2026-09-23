package com.mir2.world;

import java.util.Objects;

/**
 * An item lying on the map, mirroring the fields of Delphi {@code TMapItem} used by
 * {@code SM_ITEMSHOW}. Ordinary objects have {@code count = 1}; gold piles use the same map
 * item record with name {@code 金币}, {@code count = coin amount}, and a look selected by the
 * classic gold-shape thresholds.
 */
public record GroundItem(int id, String name, int looks, String mapId, Position position, int count) {
  public static final String GOLD_NAME = "金币";

  public GroundItem {
    if (id <= 0) throw new IllegalArgumentException("item id must be positive");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("item name must not be blank");
    if (looks < 0 || looks > 0xffff) throw new IllegalArgumentException("looks must be an unsigned word");
    if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(position, "position");
    if (count < 1) throw new IllegalArgumentException("count must be positive");
  }

  public GroundItem(int id, String name, int looks, String mapId, Position position) {
    this(id, name, looks, mapId, position, 1);
  }

  public boolean gold() {
    return GOLD_NAME.equals(name);
  }

  public GroundItem withCountAndLooks(int newCount, int newLooks) {
    return new GroundItem(id, name, newLooks, mapId, position, newCount);
  }
}
