package com.mir2.world;

import java.util.List;
import java.util.Objects;

/**
 * One authoritative {@code Magic.DB} row as loaded by {@code LocalDB.LoadMagicDB}.
 *
 * <p>The current 1.50 slice supports training levels 0..3.  GEEM2 has later extension
 * columns, but the leaked engine copies only NeedL1..3/L1Train..3 and hard-codes
 * {@code btTrainLv = 3}; this record intentionally exposes that original shape.
 */
public record MagicDefinition(
    int id,
    String name,
    int effectType,
    int effect,
    int spell,
    int power,
    int maxPower,
    int defSpell,
    int defPower,
    int defMaxPower,
    int job,
    List<Integer> trainLevels,
    List<Integer> maxTrain,
    long delayMillis,
    String description) {

  public static final int MAX_SKILL_LEVEL = 3;
  public static final int ANY_JOB = 99;

  public MagicDefinition {
    if (id < 1 || id > 0xffff) throw new IllegalArgumentException("magic id must be a Word");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) throw new IllegalArgumentException("magic name must not be blank");
    if (effectType < 0 || effectType > 0xff || effect < 0 || effect > 0xff)
      throw new IllegalArgumentException("magic effects must be bytes");
    if (spell < 0 || spell > 0xffff || power < 0 || power > 0xffff
        || maxPower < power || maxPower > 0xffff)
      throw new IllegalArgumentException("invalid spell/power range");
    if (defSpell < 0 || defSpell > 0xff || defPower < 0 || defPower > 0xff
        || defMaxPower < defPower || defMaxPower > 0xff)
      throw new IllegalArgumentException("invalid default spell/power range");
    if (job < 0 || job > 0xff) throw new IllegalArgumentException("job must be a byte");
    trainLevels = List.copyOf(trainLevels);
    maxTrain = List.copyOf(maxTrain);
    if (trainLevels.size() != MAX_SKILL_LEVEL || maxTrain.size() != MAX_SKILL_LEVEL)
      throw new IllegalArgumentException("1.50 magic definitions need three training levels");
    if (trainLevels.stream().anyMatch(level -> level < 0 || level > 0xff)
        || maxTrain.stream().anyMatch(points -> points < 0))
      throw new IllegalArgumentException("invalid training requirements");
    if (delayMillis < 0 || delayMillis > Integer.MAX_VALUE)
      throw new IllegalArgumentException("delay must fit Delphi Integer");
    description = description == null ? "" : description;
  }

  public int requiredLevel(int skillLevel) {
    checkSkillLevel(skillLevel);
    return trainLevels.get(Math.min(skillLevel, trainLevels.size() - 1));
  }

  public int trainingRequired(int skillLevel) {
    checkSkillLevel(skillLevel);
    return maxTrain.get(Math.min(skillLevel, maxTrain.size() - 1));
  }

  /** {@code GetSpellPoint}: banker's rounding, matching Delphi 6 {@code Round}. */
  public int manaCost(int skillLevel) {
    checkSkillLevel(skillLevel);
    return bankersRound(spell / 4.0 * (skillLevel + 1)) + defSpell;
  }

  int scalePower(int rawPower, int skillLevel) {
    checkSkillLevel(skillLevel);
    return bankersRound(rawPower / 4.0 * (skillLevel + 1));
  }

  private static int bankersRound(double value) {
    return (int) Math.rint(value);
  }

  private static void checkSkillLevel(int skillLevel) {
    if (skillLevel < 0 || skillLevel > MAX_SKILL_LEVEL)
      throw new IllegalArgumentException("skill level must be 0.." + MAX_SKILL_LEVEL);
  }
}
