package com.mir2.shadowdiff;

import java.util.List;
import java.util.Objects;

/**
 * The observable player state a real client could reconstruct from the wire at one moment:
 * cell, facing, health pools, level/experience, gold and the bag/worn item lists.
 *
 * <p>Everything volatile between two independent server processes is already normalised
 * out: object ids are omitted (each server allocates its own), MakeIndexes are omitted from
 * item identity (each server numbers instances independently) and the map id is kept
 * because both servers must be configured with the same world for the comparison to mean
 * anything.
 */
public record StateSnapshot(
    String mapId,
    int x,
    int y,
    int direction,
    int hp,
    int maxHp,
    int mp,
    int maxMp,
    int level,
    long gold,
    List<String> bagItems,
    List<String> wornItems) {

  public StateSnapshot {
    mapId = Objects.requireNonNullElse(mapId, "");
    bagItems = List.copyOf(bagItems);
    wornItems = List.copyOf(wornItems);
  }

  /** Multi-line rendering used verbatim inside the report's diff blocks. */
  public String describe() {
    return "map=" + mapId + " cell=(" + x + "," + y + ") dir=" + direction
        + " hp=" + hp + "/" + maxHp + " mp=" + mp + "/" + maxMp
        + " level=" + level + " gold=" + gold
        + " bag=" + bagItems + " worn=" + wornItems;
  }
}
