package com.mir2.world;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ability, bag contents, worn equipment and gold that must survive a GAME socket reconnect.
 *
 * <p>Gold is {@code TPlayObject.m_nGold} — a plain Integer on the player object in Delphi,
 * restored from {@code THumDataInfo.HumData.Gold} at login, so it lives beside the ability
 * rather than inside {@code TAbility} (which never carries it on the wire).
 */
public record PlayerState(
    UUID characterId, Ability ability, List<BackpackItem> backpack, Equipment equipment, long gold,
    int pkPoint, double bodyLuck, List<PlayerSkill> skills) {
  /** Delphi {@code MAXBAGITEM}; equipped items are stored separately. */
  public static final int MAX_BACKPACK_ITEMS = 46;

  /**
   * {@code g_Config.nHumanMaxGold} (M2Share.pas:1763) = 10,000,000. {@code IncGold} refuses to
   * push a wallet past it; the repair path only ever deducts, so the cap guards the same
   * signed-int envelope the Delphi code lives in.
   */
  public static final long MAX_GOLD = 10_000_000L;

  public PlayerState {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(ability, "ability");
    Objects.requireNonNull(equipment, "equipment");
    if (ability.level() < 1) throw new IllegalArgumentException("player level must be at least one");
    if (gold < 0 || gold > MAX_GOLD) {
      throw new IllegalArgumentException("gold must be within 0.." + MAX_GOLD);
    }
    backpack = List.copyOf(backpack);
    if (backpack.size() > MAX_BACKPACK_ITEMS) {
      throw new IllegalArgumentException("backpack exceeds " + MAX_BACKPACK_ITEMS + " items");
    }
    if (backpack.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("backpack must not contain null items");
    }
    if (pkPoint < 0) throw new IllegalArgumentException("pk point must not be negative");
    skills = List.copyOf(skills);
    if (skills.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("skills must not contain null entries");
    if (skills.stream().map(PlayerSkill::magicId).distinct().count() != skills.size())
      throw new IllegalArgumentException("skills must not contain duplicate magic ids");
  }

  /** Compatibility overload for callers predating the skill slice. */
  public PlayerState(
      UUID characterId, Ability ability, List<BackpackItem> backpack, Equipment equipment,
      long gold, int pkPoint, double bodyLuck) {
    this(characterId, ability, backpack, equipment, gold, pkPoint, bodyLuck, List.of());
  }

  /** Compatibility overload for callers predating the equipment slice. */
  public PlayerState(UUID characterId, Ability ability, List<BackpackItem> backpack) {
    this(characterId, ability, backpack, Equipment.empty(), 0, 0, 0);
  }

  /** Compatibility overload for callers predating the gold slice. */
  public PlayerState(
      UUID characterId, Ability ability, List<BackpackItem> backpack, Equipment equipment) {
    this(characterId, ability, backpack, equipment, 0, 0, 0);
  }

  /** Compatibility overload for callers predating the PK slice. */
  public PlayerState(
      UUID characterId, Ability ability, List<BackpackItem> backpack, Equipment equipment,
      long gold) {
    this(characterId, ability, backpack, equipment, gold, 0, 0);
  }

  /** Compatibility overload for callers predating the body-luck slice. */
  public PlayerState(
      UUID characterId, Ability ability, List<BackpackItem> backpack, Equipment equipment,
      long gold, int pkPoint) {
    this(characterId, ability, backpack, equipment, gold, pkPoint, 0);
  }

  public static PlayerState initial(UUID characterId) {
    return new PlayerState(characterId, Ability.defaultPlayer(), List.of(), Equipment.empty(), 0, 0, 0);
  }

  public PlayerState withEquipment(Equipment newEquipment) {
    return new PlayerState(characterId, ability, backpack, newEquipment, gold, pkPoint, bodyLuck, skills);
  }

  public PlayerState withGold(long newGold) {
    return new PlayerState(characterId, ability, backpack, equipment, newGold, pkPoint, bodyLuck, skills);
  }

  /** {@code m_nPkPoint}: the murder counter {@code PKLevel} is derived from. */
  public PlayerState withPkPoint(int newPkPoint) {
    return new PlayerState(characterId, ability, backpack, equipment, gold, newPkPoint, bodyLuck, skills);
  }

  /** {@code m_dBodyLuck}: the persisted 幸运值 accumulator ({@code HumData.dBodyLuck}). */
  public PlayerState withBodyLuck(double newBodyLuck) {
    return new PlayerState(characterId, ability, backpack, equipment, gold, pkPoint, newBodyLuck, skills);
  }

  public PlayerState withSkills(List<PlayerSkill> newSkills) {
    return new PlayerState(characterId, ability, backpack, equipment, gold, pkPoint, bodyLuck,
        newSkills);
  }

  /** {@code TBaseObject.PKLevel} = {@code m_nPkPoint div 100}. */
  public int pkLevel() {
    return PkLevel.of(pkPoint);
  }
}
