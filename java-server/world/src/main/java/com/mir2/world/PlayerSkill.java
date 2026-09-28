package com.mir2.world;

/** Durable {@code TUserMagic} fields; the {@code MagicInfo} pointer is resolved from MagicCatalog. */
public record PlayerSkill(int magicId, int level, int trainingPoints, int key) {
  public PlayerSkill {
    if (magicId < 1 || magicId > 0xffff) throw new IllegalArgumentException("magic id must be a Word");
    if (level < 0 || level > MagicDefinition.MAX_SKILL_LEVEL)
      throw new IllegalArgumentException("skill level must be 0.." + MagicDefinition.MAX_SKILL_LEVEL);
    if (trainingPoints < 0) throw new IllegalArgumentException("training points must not be negative");
    if (key < 0 || key > 0xff) throw new IllegalArgumentException("magic key must be a byte");
  }

  public static PlayerSkill learned(int magicId) {
    return new PlayerSkill(magicId, 0, 0, 0);
  }

  public PlayerSkill withKey(int newKey) {
    return new PlayerSkill(magicId, level, trainingPoints, newKey);
  }

  /** Adds one Delphi {@code TrainSkill} result without changing the spell's level. */
  public PlayerSkill withTraining(int points) {
    if (points < 0) throw new IllegalArgumentException("training points must not be negative");
    return new PlayerSkill(magicId, level, Math.addExact(trainingPoints, points), key);
  }

  /**
   * Applies one {@code CheckMagicLevelup} pass from {@code ObjBase.pas:21818}.
   *
   * <p>The original server checks at most one level per hit.  Keeping that detail matters for
   * the low-level training curve: any remainder stays in {@code nTranPoint}, and a later hit
   * performs the next check rather than silently consuming multiple thresholds.
   */
  public PlayerSkill train(MagicDefinition definition, int playerLevel, int points) {
    if (definition == null) throw new NullPointerException("definition");
    if (definition.id() != magicId) throw new IllegalArgumentException("skill/definition mismatch");
    if (playerLevel < 1) throw new IllegalArgumentException("player level must be positive");
    if (points < 0) throw new IllegalArgumentException("training points must not be negative");

    // ObjBase.pas only calls TrainSkill when TrainLevel[btLevel] is already met; a character
    // may carry a learned row below that level requirement, but hits do not train it yet.
    if (level >= MagicDefinition.MAX_SKILL_LEVEL || playerLevel < definition.requiredLevel(level)) {
      return this;
    }
    int nextPoints = Math.addExact(trainingPoints, points);
    int nextLevel = level;
    if (nextPoints >= definition.trainingRequired(level)) {
      nextPoints -= definition.trainingRequired(level);
      nextLevel++;
    }
    return new PlayerSkill(magicId, nextLevel, nextPoints, key);
  }
}
