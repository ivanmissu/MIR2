package com.mir2.world;

import java.util.Objects;

/**
 * The wearability gate of {@code TPlayObject.CheckTakeOnItems} (ObjBase.pas:22970).
 *
 * <p>Two families of checks run in the Delphi order: a gender lock on the dress StdModes, a
 * weight budget that differs for the two hand slots, and finally the {@code StdItem.Need}
 * requirement whose meaning is selected by {@code Need} and whose threshold(s) live packed
 * inside {@code NeedLevel}.
 */
public final class EquipRequirement {
  private EquipRequirement() {}

  /** Why a take-on was refused; {@link Result#OK} means the item may be worn. */
  public enum Result {
    OK,
    /** {@code StdMode 10} is male-only, {@code 11} female-only. */
    WRONG_GENDER,
    /** Weapon/right-hand item heavier than MaxHandWeight. */
    HAND_WEIGHT,
    /** Worn weight budget exceeded (MaxWearWeight). */
    WEAR_WEIGHT,
    /** Character level below {@code NeedLevel}. */
    LEVEL,
    /** Attack/magic/taoist power below {@code NeedLevel}. */
    POWER,
    /** Job mismatch for a job-gated requirement. */
    JOB,
    /**
     * {@code Need} selects a rebirth ({@code m_btReLevel}) or castle requirement that this
     * server does not model yet, so the item is refused rather than silently allowed.
     */
    UNSUPPORTED_REQUIREMENT;

    public boolean allowed() {
      return this == OK;
    }
  }

  /**
   * The character facts {@code CheckTakeOnItems} reads. {@code dcMax}/{@code mcMax}/
   * {@code scMax} are the {@code HiWord} halves of the recalculated {@code m_WAbil} ranges,
   * i.e. the values already including currently worn gear.
   */
  public record Character(int gender, int job, int level, int dcMax, int mcMax, int scMax,
      int maxWearWeight, int maxHandWeight) {

    public Character {
      if (gender < 0 || gender > 1) throw new IllegalArgumentException("gender must be 0 or 1");
      if (job < 0) throw new IllegalArgumentException("job must not be negative");
      if (level < 0) throw new IllegalArgumentException("level must not be negative");
    }
  }

  /** {@code gMan} — {@code StdMode 10} dresses are male-only. */
  public static final int GENDER_MALE = 0;
  /** {@code gWoMan} — {@code StdMode 11} dresses are female-only. */
  public static final int GENDER_FEMALE = 1;

  /**
   * Runs the full check.
   *
   * @param wornWeightExcludingTarget the {@code GetUserItemWeitht(nWhere)} total: the weight
   *     of every worn item except the destination slot and both hand slots
   */
  public static Result check(
      EquipmentSlot slot, StdItem item, Character character, int wornWeightExcludingTarget) {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(character, "character");

    if (item.stdMode() == 10 && character.gender() != GENDER_MALE) return Result.WRONG_GENDER;
    if (item.stdMode() == 11 && character.gender() != GENDER_FEMALE) return Result.WRONG_GENDER;

    // nWhere 1 and 2 (weapon, right hand) are budgeted against MaxHandWeight on their own;
    // every other slot shares the MaxWearWeight budget with the rest of the worn set.
    if (slot.countsTowardHandWeight()) {
      if (item.weight() > character.maxHandWeight()) return Result.HAND_WEIGHT;
    } else if (item.weight() + wornWeightExcludingTarget > character.maxWearWeight()) {
      return Result.WEAR_WEIGHT;
    }

    int need = (int) item.need();
    long needLevel = item.needLevel();
    int lowWord = (int) (needLevel & 0xffffL);
    int highWord = (int) ((needLevel >>> 16) & 0xffffL);
    return switch (need) {
      case 0 -> character.level() >= needLevel ? Result.OK : Result.LEVEL;
      case 1 -> character.dcMax() >= needLevel ? Result.OK : Result.POWER;
      case 2 -> character.mcMax() >= needLevel ? Result.OK : Result.POWER;
      case 3 -> character.scMax() >= needLevel ? Result.OK : Result.POWER;
      case 10 -> requireJob(character, lowWord, character.level() >= highWord, Result.LEVEL);
      case 11 -> requireJob(character, lowWord, character.dcMax() >= highWord, Result.POWER);
      case 12 -> requireJob(character, lowWord, character.mcMax() >= highWord, Result.POWER);
      case 13 -> requireJob(character, lowWord, character.scMax() >= highWord, Result.POWER);
      // Need 4/40/41 gate on m_btReLevel (rebirth) and the remaining codes on castle
      // membership; neither subsystem exists yet.
      // TODO(verify): model rebirth level and castle membership when those slices land.
      default -> Result.UNSUPPORTED_REQUIREMENT;
    };
  }

  private static Result requireJob(Character character, int requiredJob, boolean statMet, Result statFailure) {
    if (character.job() != requiredJob) return Result.JOB;
    return statMet ? Result.OK : statFailure;
  }
}
