package com.mir2.world;

/**
 * The six weight fields of the packed Delphi {@code TAbility} record (Grobal2.pas:733):
 * {@code Weight/MaxWeight}, {@code WearWeight/MaxWearWeight} and
 * {@code HandWeight/MaxHandWeight}.
 *
 * <p>These travel inside {@code SM_ABILITY}. {@code SM_WEIGHTCHANGED} only carries the three
 * *current* totals (ClMain.pas:4473), so the maxima reach the client through this record and
 * nowhere else. That matters because the client guards its bottom status bars with
 * {@code (MaxExp > 0) and (MaxWeight > 0)} (FState.pas:3646) and divides by
 * {@code MaxWeight}/{@code MaxWearWeight}/{@code MaxHandWeight} when it shades the
 * overloaded-character icons (Actor.pas:2633) — a zero maximum blanks the bars and risks a
 * division by zero.
 *
 * <p>The maxima come from {@code TBaseObject.RecalcLevelAbilitys} (ObjBase.pas:1889) through
 * {@link LevelAbilities}; equipment adds its bonuses on top, mirroring
 * {@code RecalcAbilitys}.
 */
public record WeightLimits(
    int weight,
    int maxWeight,
    int wearWeight,
    int maxWearWeight,
    int handWeight,
    int maxHandWeight) {

  public WeightLimits {
    if (weight < 0 || wearWeight < 0 || handWeight < 0)
      throw new IllegalArgumentException("carried weights must not be negative");
    if (maxWeight < 0 || maxWearWeight < 0 || maxHandWeight < 0)
      throw new IllegalArgumentException("weight limits must not be negative");
  }

  /**
   * The limits a character of this job and level carries with nothing equipped — the state
   * right after {@code RecalcLevelAbilitys} and before {@code RecalcAbilitys} folds in gear.
   */
  public static WeightLimits forLevel(int job, int level) {
    return new WeightLimits(
        0, LevelAbilities.maxWeight(job, level),
        0, LevelAbilities.maxWearWeight(job, level),
        0, LevelAbilities.maxHandWeight(job, level));
  }
}
