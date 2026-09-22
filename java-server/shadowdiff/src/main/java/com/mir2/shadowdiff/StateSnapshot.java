package com.mir2.shadowdiff;

import java.util.List;
import java.util.Objects;

/**
 * The observable player state a real client could reconstruct from the wire at one moment:
 * cell, facing, health pools, level/experience, gold and the bag/worn item lists, plus the
 * combat facts of the op that just ran.
 *
 * <p>Everything volatile between two independent server processes is already normalised
 * out: object ids are omitted (each server allocates its own), MakeIndexes are omitted from
 * item identity (each server numbers instances independently) and the map id is kept
 * because both servers must be configured with the same world for the comparison to mean
 * anything.
 *
 * <p>{@code experience} and {@code combat} exist so PvE 对拍 is not blind: the whole reason
 * to seed the world ({@link com.mir2.world.WorldRandom}) is to make damage reproducible, and
 * damage is only visible through {@code SM_STRUCK} / {@code SM_WINEXP}. {@code combat}
 * records, per op, the damage values and the victim's resulting HP as they arrived —
 * attacker/victim object ids are deliberately dropped because they are server-local.
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
    long experience,
    long gold,
    List<String> bagItems,
    List<String> wornItems,
    List<String> combat) {

  public StateSnapshot {
    mapId = Objects.requireNonNullElse(mapId, "");
    bagItems = List.copyOf(bagItems);
    wornItems = List.copyOf(wornItems);
    combat = List.copyOf(combat);
  }

  /** Compatibility overload for callers predating the combat/experience fields. */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long gold, List<String> bagItems, List<String> wornItems) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, 0, gold, bagItems, wornItems,
        List.of());
  }

  /** Multi-line rendering used verbatim inside the report's diff blocks. */
  public String describe() {
    return "map=" + mapId + " cell=(" + x + "," + y + ") dir=" + direction
        + " hp=" + hp + "/" + maxHp + " mp=" + mp + "/" + maxMp
        + " level=" + level + " exp=" + experience + " gold=" + gold
        + " bag=" + bagItems + " worn=" + wornItems
        + (combat.isEmpty() ? "" : " combat=" + combat);
  }
}
