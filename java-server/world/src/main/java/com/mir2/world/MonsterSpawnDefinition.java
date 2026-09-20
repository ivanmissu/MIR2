package com.mir2.world;

import java.util.Objects;

/** One row from the legacy M2Server {@code MonGen.txt} format. */
public record MonsterSpawnDefinition(
    String mapName,
    int x,
    int y,
    String monsterName,
    int range,
    int count,
    long respawnMillis,
    int missionGenRate) {

  public MonsterSpawnDefinition {
    mapName = text(mapName, "map name");
    monsterName = text(monsterName, "monster name");
    if (x < 0 || y < 0) throw new IllegalArgumentException("spawn coordinates must be non-negative");
    if (range < 0 || count < 0 || missionGenRate < 0)
      throw new IllegalArgumentException("range, count and mission rate must be non-negative");
    if (respawnMillis <= 0) throw new IllegalArgumentException("respawn time must be positive");
  }

  private static String text(String value, String field) {
    Objects.requireNonNull(value, field);
    String result = value.trim();
    if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return result;
  }
}
