package com.mir2.world;

import java.util.Objects;

/** Immutable object state safe to pass from the world thread to transport threads. */
public record WorldObjectSnapshot(
    int id,
    String name,
    WorldObjectType type,
    String mapId,
    Position position,
    Direction direction) {

  public WorldObjectSnapshot {
    if (id <= 0) throw new IllegalArgumentException("object id must be positive");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("object name must not be blank");
    Objects.requireNonNull(type, "type");
    if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
  }
}
