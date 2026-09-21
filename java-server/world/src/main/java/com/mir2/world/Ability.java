package com.mir2.world;

/**
 * Subset of Delphi {@code TAbility} needed by the W03 combat slice.
 *
 * <p>Delphi stores attack and defence as packed {@code Word} ranges (low byte = minimum, high byte
 * = maximum). This record keeps them expanded because the packing only matters on the wire.
 *
 * <p>{@code maxExperience} is {@code TAbility.MaxExp}: the amount that triggers the next level.
 * {@code HasLevelUp} refreshes it from {@code GetLevelExp(Level)} (ObjBase.pas:1943), so it is
 * derived state rather than something the persistence layer has to store.
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
    long experience,
    long maxExperience) {

  public Ability {
    if (maxHp < 1 || maxMp < 0) throw new IllegalArgumentException("invalid maximum health or mana");
    if (hp < 0 || hp > maxHp) throw new IllegalArgumentException("hp must be within 0..maxHp");
    if (mp < 0 || mp > maxMp) throw new IllegalArgumentException("mp must be within 0..maxMp");
    if (minDc < 0 || maxDc < minDc) throw new IllegalArgumentException("invalid attack range");
    if (minAc < 0 || maxAc < minAc) throw new IllegalArgumentException("invalid defence range");
    // MAXUPLEVEL is High(Word); the wire encodes Level as a Word too (TAbility.Level).
    if (level < 0 || level > LevelExperience.MAX_UP_LEVEL)
      throw new IllegalArgumentException("level must be 0.." + LevelExperience.MAX_UP_LEVEL);
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
    if (maxExperience < 0) throw new IllegalArgumentException("maxExperience must not be negative");
  }

  /**
   * Compatibility constructor predating the level table: {@code MaxExp} is derived from the
   * level through {@code GetLevelExp}, exactly as {@code HasLevelUp} would.
   */
  public Ability(int hp, int maxHp, int mp, int maxMp, int minDc, int maxDc,
      int minAc, int maxAc, int level, long experience) {
    this(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, level, experience,
        LevelExperience.forLevel(level));
  }

  /**
   * Level-one baseline for a freshly created character, the literal block in
   * {@code TUserEngine.AddPlayObject} (UsrEngn.pas:503): {@code Level=1, DC=MakeLong(1,2),
   * MC=SC=MakeLong(1,2), AC=MAC=0, HP=MP=MaxHP=MaxMP=15, Exp=0, MaxExp=100, MaxWeight=30}.
   *
   * <p>Delphi never runs {@code RecalcLevelAbilitys} at creation, so a level-one character
   * keeps these literals; the growth curve only kicks in at the first level-up. That is why
   * a warrior's DC narrows from 1-2 to 1-1 when it reaches level 2 — the curve's
   * {@code MakeLong(_MAX(nLevel div 5 - 1, 1), _MAX(1, nLevel div 5))} yields 1-1 until
   * level 10. The quirk is reproduced rather than smoothed over.
   */
  public static Ability defaultPlayer() {
    return new Ability(15, 15, 15, 15, 1, 2, 0, 0, 1, 0);
  }

  public static Ability monster(int maxHp, int minDc, int maxDc, int minAc, int maxAc) {
    return new Ability(maxHp, maxHp, 0, 0, minDc, maxDc, minAc, maxAc, 1, 0);
  }

  public boolean alive() {
    return hp > 0;
  }

  public Ability withHp(int newHp) {
    return new Ability(clamp(newHp, maxHp), maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc,
        level, experience, maxExperience);
  }

  public Ability withMp(int newMp) {
    return new Ability(hp, maxHp, clamp(newMp, maxMp), maxMp, minDc, maxDc, minAc, maxAc,
        level, experience, maxExperience);
  }

  public Ability addExperience(long gained) {
    if (gained < 0) throw new IllegalArgumentException("experience gain must not be negative");
    return new Ability(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, level,
        experience + gained, maxExperience);
  }

  /**
   * {@code GetExp} after a level-up: {@code Dec(m_Abil.Exp, m_Abil.MaxExp); Inc(m_Abil.Level)}
   * followed by {@code HasLevelUp} refreshing {@code MaxExp := GetLevelExp(Level)}.
   *
   * <p>The level is only raised while below {@code MAXUPLEVEL}, but the experience is deducted
   * either way — a capped character therefore keeps burning through overflow experience, which
   * is the shipped behaviour.
   */
  public Ability consumeLevelExperience() {
    long remaining = Math.max(0, experience - maxExperience);
    int nextLevel = level < LevelExperience.MAX_UP_LEVEL ? level + 1 : level;
    return new Ability(hp, maxHp, mp, maxMp, minDc, maxDc, minAc, maxAc, nextLevel,
        remaining, LevelExperience.forLevel(nextLevel));
  }

  /** True when the accumulated experience has reached the next-level threshold. */
  public boolean readyToLevel() {
    return experience >= maxExperience;
  }

  private static int clamp(int value, int maximum) {
    return Math.max(0, Math.min(value, maximum));
  }

  public Ability restored() {
    return withHp(maxHp).withMp(maxMp);
  }
}
