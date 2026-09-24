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
 *
 * <p>{@code neighbours} is the W23 addition that makes <em>moving</em> monsters comparable:
 * the cells and facings of every other actor the client currently believes is in view,
 * rebuilt from the {@code SM_TURN}/{@code SM_WALK}/{@code SM_RUN}/{@code SM_DISAPPEAR}
 * broadcast stream. Object ids are server-local, so entries are keyed by the actor's cell
 * and sorted — two servers whose monster AI made the same decisions produce the same census,
 * and a single divergent step shows up immediately. It stays empty (and therefore invisible
 * to the differ) on scripts that never bring another actor into view.
 *
 * <p>{@code worldTime} is the world clock value echoed by the {@code @tick} pump, or -1 when
 * the servers run an ordinary clock. Under a manual clock both sides must report the same
 * value after the same script, which turns "did both worlds actually run the same number of
 * ticks?" into a first-class assertion instead of an assumption.
 *
 * <p>{@code groupMembers} is the W30 duo surface: the roster exactly as the last
 * {@code SM_GROUPMEMBERS} broadcast spelled it (server order, names verbatim), cleared on
 * every {@code SM_NEWMAP} the way a client forgets its party on a fresh map load. A group
 * that formed differently — or disbanded on only one server — is a STATE failure, not just a
 * message-set note. {@code nameColor} is the last {@code SM_CHANGENAMECOLOR} the client saw
 * for its own actor (or -1 before any repaint), which is the only wire-visible trace of the
 * PK level model: the aggressor's 交手 tint, the murder penalty's yellow, red at 200 points.
 *
 * <p>{@code groundItems} is the drop census: every item the client believes is lying on the
 * ground ({@code SM_ITEMSHOW} add / {@code SM_ITEMHIDE} remove, the {@code
 * g_DropedItemList} bookkeeping), rendered as name+cell and sorted so server-local item ids
 * never reach the comparison. A loot table that rolled differently, or a drop that landed on
 * a different cell, is a STATE failure — which is what makes the party scenario's pickup
 * step observable rather than assumed.
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
    List<String> combat,
    List<String> neighbours,
    long worldTime,
    List<String> groupMembers,
    int nameColor,
    List<String> groundItems) {

  public StateSnapshot {
    mapId = Objects.requireNonNullElse(mapId, "");
    bagItems = List.copyOf(bagItems);
    wornItems = List.copyOf(wornItems);
    combat = List.copyOf(combat);
    neighbours = List.copyOf(neighbours);
    groupMembers = List.copyOf(groupMembers);
    groundItems = List.copyOf(groundItems);
  }

  /** Compatibility overload for callers predating the neighbour census. */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long experience, long gold, List<String> bagItems,
      List<String> wornItems, List<String> combat) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, experience, gold, bagItems,
        wornItems, combat, List.of(), -1);
  }

  /** Overload for the neighbour census without a manual-clock world time. */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long experience, long gold, List<String> bagItems,
      List<String> wornItems, List<String> combat, List<String> neighbours) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, experience, gold, bagItems,
        wornItems, combat, neighbours, -1);
  }

  /** Overload for the neighbour census with a manual-clock world time (W23). */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long experience, long gold, List<String> bagItems,
      List<String> wornItems, List<String> combat, List<String> neighbours, long worldTime) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, experience, gold, bagItems,
        wornItems, combat, neighbours, worldTime, List.of(), -1);
  }

  /** Overload for the W30 group/name-colour fields without the ground census. */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long experience, long gold, List<String> bagItems,
      List<String> wornItems, List<String> combat, List<String> neighbours, long worldTime,
      List<String> groupMembers, int nameColor) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, experience, gold, bagItems,
        wornItems, combat, neighbours, worldTime, groupMembers, nameColor, List.of());
  }

  /** Compatibility overload for callers predating the combat/experience fields. */
  public StateSnapshot(String mapId, int x, int y, int direction, int hp, int maxHp,
      int mp, int maxMp, int level, long gold, List<String> bagItems, List<String> wornItems) {
    this(mapId, x, y, direction, hp, maxHp, mp, maxMp, level, 0, gold, bagItems, wornItems,
        List.of(), List.of(), -1);
  }

  /** Multi-line rendering used verbatim inside the report's diff blocks. */
  public String describe() {
    return "map=" + mapId + " cell=(" + x + "," + y + ") dir=" + direction
        + " hp=" + hp + "/" + maxHp + " mp=" + mp + "/" + maxMp
        + " level=" + level + " exp=" + experience + " gold=" + gold
        + " bag=" + bagItems + " worn=" + wornItems
        + (combat.isEmpty() ? "" : " combat=" + combat)
        + (neighbours.isEmpty() ? "" : " near=" + neighbours)
        + (worldTime < 0 ? "" : " worldTime=" + worldTime)
        + (groupMembers.isEmpty() ? "" : " group=" + groupMembers)
        + (nameColor < 0 ? "" : " nameColor=" + nameColor)
        + (groundItems.isEmpty() ? "" : " ground=" + groundItems);
  }
}
