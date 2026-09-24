package com.mir2.world;

/**
 * Expanded Delphi {@code TAbility} combat ranges.
 *
 * <p>Delphi stores AC/MAC/DC/MC/SC as packed {@code Word} ranges.  The world keeps every
 * range explicit and leaves the little-endian packing to the gate codec.  The original W03
 * slice modelled only DC/AC; W28 restores MAC/MC/SC so skills and their equipment bonuses do
 * not disappear on the server or on {@code SM_ABILITY}.
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
    int minMac,
    int maxMac,
    int minMc,
    int maxMc,
    int minSc,
    int maxSc,
    int level,
    long experience,
    long maxExperience) {

  public Ability {
    if (maxHp < 1 || maxMp < 0) throw new IllegalArgumentException("invalid maximum health or mana");
    if (hp < 0 || hp > maxHp) throw new IllegalArgumentException("hp must be within 0..maxHp");
    if (mp < 0 || mp > maxMp) throw new IllegalArgumentException("mp must be within 0..maxMp");
    range(minDc, maxDc, "attack");
    range(minAc, maxAc, "defence");
    range(minMac, maxMac, "magic defence");
    range(minMc, maxMc, "magic power");
    range(minSc, maxSc, "taoist power");
    if (level < 0 || level > LevelExperience.MAX_UP_LEVEL)
      throw new IllegalArgumentException("level must be 0.." + LevelExperience.MAX_UP_LEVEL);
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
    if (maxExperience < 0) throw new IllegalArgumentException("maxExperience must not be negative");
  }

  /** Compatibility constructor used by the pre-skill code: magical ranges default to zero. */
  public Ability(int hp, int maxHp, int mp, int maxMp, int minDc, int maxDc,
      int minAc, int maxAc, int level, long experience, long maxExperience) {
    this(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc,
        0, 0, 0, 0, 0, 0, level, experience, maxExperience);
  }

  /** Compatibility constructor with derived {@code MaxExp}. */
  public Ability(int hp, int maxHp, int mp, int maxMp, int minDc, int maxDc,
      int minAc, int maxAc, int level, long experience) {
    this(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, level, experience,
        LevelExperience.forLevel(level));
  }

  /**
   * Literal new-character block: HP/MP 15, DC/MC/SC 1-2 and AC/MAC zero.  Delphi does not run
   * the level curve at creation, so these values survive until the first level-up.
   */
  public static Ability defaultPlayer() {
    return new Ability(15, 15, 15, 15, 1, 2, 0, 0,
        0, 0, 1, 2, 1, 2, 1, 0, LevelExperience.forLevel(1));
  }

  public static Ability monster(int maxHp, int minDc, int maxDc, int minAc, int maxAc) {
    return new Ability(maxHp, maxHp, 0, 0, minDc, maxDc, minAc, maxAc, 1, 0);
  }

  public static Ability immortal() {
    return new Ability(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, 1, 0);
  }

  public boolean alive() {
    return hp > 0;
  }

  public Ability withHp(int newHp) {
    return copy(clamp(newHp, maxHp), maxHp, mp, maxMp, level, experience, maxExperience);
  }

  public Ability withMp(int newMp) {
    return copy(hp, maxHp, clamp(newMp, maxMp), maxMp, level, experience, maxExperience);
  }

  public Ability addExperience(long gained) {
    if (gained < 0) throw new IllegalArgumentException("experience gain must not be negative");
    return copy(hp, maxHp, mp, maxMp, level, experience + gained, maxExperience);
  }

  public Ability consumeLevelExperience() {
    long remaining = Math.max(0, experience - maxExperience);
    int nextLevel = level < LevelExperience.MAX_UP_LEVEL ? level + 1 : level;
    return copy(hp, maxHp, mp, maxMp, nextLevel, remaining, LevelExperience.forLevel(nextLevel));
  }

  public boolean readyToLevel() {
    return experience >= maxExperience;
  }

  public Ability restored() {
    return withHp(maxHp).withMp(maxMp);
  }

  private Ability copy(int newHp, int newMaxHp, int newMp, int newMaxMp,
      int newLevel, long newExperience, long newMaxExperience) {
    return new Ability(newHp, newMaxHp, newMp, newMaxMp, minDc, maxDc, minAc, maxAc,
        minMac, maxMac, minMc, maxMc, minSc, maxSc,
        newLevel, newExperience, newMaxExperience);
  }

  private static void range(int min, int max, String name) {
    if (min < 0 || max < min) throw new IllegalArgumentException("invalid " + name + " range");
  }

  private static int clamp(int value, int maximum) {
    return Math.max(0, Math.min(value, maximum));
  }
}
