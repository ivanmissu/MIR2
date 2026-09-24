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
}
