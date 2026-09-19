package com.mir2.world;

import java.util.Objects;

/** An item lying on the map, mirroring the fields of Delphi {@code TMapItem} used by SM_ITEMSHOW. */
public record GroundItem(int id, String name, int looks, String mapId, Position position) {
  public GroundItem {
    if (id <= 0) throw new IllegalArgumentException("item id must be positive");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("item name must not be blank");
    if (looks < 0 || looks > 0xffff) throw new IllegalArgumentException("looks must be an unsigned word");
    if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(position, "position");
  }
}
