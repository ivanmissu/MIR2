package com.mir2.world;

/**
 * Subset of Delphi {@code TAbility} needed by the W03 combat slice.
 *
 * <p>Delphi stores attack and defence as packed {@code Word} ranges (low byte = minimum, high byte
 * = maximum). This record keeps them expanded because the packing only matters on the wire.
 */
public record Ability(
    int hp,
    int maxHp,
    int mp,
    int maxMp,
    int minDc,
    int maxDc,
    int minAc,
    int maxAc,
    int level,
    long experience) {

  public Ability {
    if (maxHp < 1 || maxMp < 0) throw new IllegalArgumentException("invalid maximum health or mana");
    if (hp < 0 || hp > maxHp) throw new IllegalArgumentException("hp must be within 0..maxHp");
    if (mp < 0 || mp > maxMp) throw new IllegalArgumentException("mp must be within 0..maxMp");
    if (minDc < 0 || maxDc < minDc) throw new IllegalArgumentException("invalid attack range");
    if (minAc < 0 || maxAc < minAc) throw new IllegalArgumentException("invalid defence range");
    if (level < 0 || level > 255) throw new IllegalArgumentException("level must be 0..255");
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
  }

  /** Level-one melee baseline used until the character domain carries real stats. */
  public static Ability defaultPlayer() {
    return new Ability(100, 100, 20, 20, 3, 8, 0, 2, 1, 0);
  }

  public static Ability monster(int maxHp, int minDc, int maxDc, int minAc, int maxAc) {
    return new Ability(maxHp, maxHp, 0, 0, minDc, maxDc, minAc, maxAc, 1, 0);
  }

  public boolean alive() {
    return hp > 0;
  }

  public Ability withHp(int newHp) {
    return new Ability(clamp(newHp, maxHp), maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, level, experience);
  }

  public Ability withMp(int newMp) {
    return new Ability(hp, maxHp, clamp(newMp, maxMp), maxMp, minDc, maxDc, minAc, maxAc, level, experience);
  }

  public Ability addExperience(long gained) {
    if (gained < 0) throw new IllegalArgumentException("experience gain must not be negative");
    return new Ability(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, level, experience + gained);
  }

  private static int clamp(int value, int maximum) {
    return Math.max(0, Math.min(value, maximum));
  }

  public Ability restored() {
    return withHp(maxHp).withMp(maxMp);
  }
}
