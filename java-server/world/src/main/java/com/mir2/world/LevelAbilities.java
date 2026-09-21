package com.mir2.world;

/**
 * {@code TBaseObject.RecalcLevelAbilitys} (ObjBase.pas:1869): the per-job stat curves applied
 * whenever a character's level changes.
 *
 * <p>The Delphi code writes straight into {@code m_Abil} (the naked ability), so the result
 * here is a naked {@code Ability}; the caller re-applies equipment through
 * {@code RecalcAbilitys} afterwards, exactly as {@code HasLevelUp} does.
 *
 * <p>Delphi divides {@code nLevel} — an {@code Integer} — by a float rate and rounds, so the
 * arithmetic below is float-based on purpose. {@code Round} in Delphi is banker's rounding
 * (round-half-to-even), which {@link Math#rint} reproduces; plain {@code Math.round} would
 * differ on exact .5 values, which do occur here (e.g. warrior level 2: 14 + rint(9.1) ).
 *
 * <p>Defaults for the configurable divisors come from {@code g_Config} (M2Share.pas:2195):
 * Taos HP 6 / 2.5, Taos MP 8, Wizard HP 15 / 1.8, Warrior HP 4 / 4.5.
 */
public final class LevelAbilities {
  /** {@code jWarr} (M2Share.pas:130). */
  public static final int JOB_WARRIOR = 0;
  /** {@code jWizard}. */
  public static final int JOB_WIZARD = 1;
  /** {@code jTaos}. */
  public static final int JOB_TAOIST = 2;

  private static final int WORD_MAX = 0xffff;

  private LevelAbilities() {}

  /**
   * Rebuilds the level-derived part of a naked ability, preserving current HP/MP (re-clamped
   * into the new maxima, mirroring the tail of {@code RecalcLevelAbilitys}) and the
   * accumulated experience.
   */
  public static Ability forLevel(int job, int level, Ability current) {
    int maxHp = maxHp(job, level);
    int maxMp = maxMp(job, level);
    int minDc = minDc(job, level);
    int maxDc = maxDc(job, level);
    int minAc = 0;
    int maxAc = maxAc(job, level);
    return new Ability(
        Math.min(current.hp(), maxHp),
        maxHp,
        Math.min(current.mp(), maxMp),
        maxMp,
        minDc,
        maxDc,
        minAc,
        maxAc,
        level,
        current.experience());
  }

  /** Delphi {@code _MIN(High(Word), 14 + Round(...))} per job. */
  public static int maxHp(int job, int level) {
    double value = switch (job) {
      // jTaos: 14 + Round((nLevel / 6 + 2.5) * nLevel)
      case JOB_TAOIST -> 14 + rint((level / 6.0 + 2.5) * level);
      // jWizard: 14 + Round((nLevel / 15 + 1.8) * nLevel)
      case JOB_WIZARD -> 14 + rint((level / 15.0 + 1.8) * level);
      // jWarr: 14 + Round((nLevel / 4.0 + 4.5 + nLevel / 20) * nLevel)
      default -> 14 + rint((level / 4.0 + 4.5 + level / 20.0) * level);
    };
    return (int) Math.min(WORD_MAX, Math.max(1, value));
  }

  public static int maxMp(int job, int level) {
    double value = switch (job) {
      // jTaos: 13 + Round((nLevel / 8) * 2.2 * nLevel)
      case JOB_TAOIST -> 13 + rint((level / 8.0) * 2.2 * level);
      // jWizard: 13 + Round((nLevel / 5 + 2) * 2.2 * nLevel)
      case JOB_WIZARD -> 13 + rint((level / 5.0 + 2) * 2.2 * level);
      // jWarr: 11 + Round(nLevel * 3.5)
      default -> 11 + rint(level * 3.5);
    };
    return (int) Math.min(WORD_MAX, Math.max(0, value));
  }

  /** {@code MaxWeight}: bag capacity, {@code 50 + Round((nLevel / d) * nLevel)}. */
  public static int maxWeight(int job, int level) {
    double divisor = switch (job) {
      case JOB_TAOIST -> 4.0;
      case JOB_WIZARD -> 5.0;
      default -> 3.0;
    };
    return (int) Math.min(WORD_MAX, 50 + rint((level / divisor) * level));
  }

  /** {@code MaxWearWeight}: worn capacity, {@code 15 + Round((nLevel / d) * nLevel)}. */
  public static int maxWearWeight(int job, int level) {
    double divisor = switch (job) {
      case JOB_TAOIST -> 50.0;
      case JOB_WIZARD -> 100.0;
      default -> 20.0;
    };
    return (int) Math.min(WORD_MAX, 15 + rint((level / divisor) * level));
  }

  /** {@code MaxHandWeight}: hand capacity, {@code 12 + Round((nLevel / d) * nLevel)}. */
  public static int maxHandWeight(int job, int level) {
    double divisor = switch (job) {
      case JOB_TAOIST -> 42.0;
      case JOB_WIZARD -> 90.0;
      default -> 13.0;
    };
    return (int) Math.min(WORD_MAX, 12 + rint((level / divisor) * level));
  }

  /**
   * {@code DC := MakeLong(_MAX(n - 1, 0), _MAX(1, n))}. Mage and Taoist derive {@code n} from
   * {@code nLevel div 7}; the warrior branch uses {@code nLevel div 5} and, unlike the others,
   * floors the minimum at 1 rather than 0.
   */
  public static int minDc(int job, int level) {
    if (job == JOB_WARRIOR) return Math.max(level / 5 - 1, 1);
    return Math.max(level / 7 - 1, 0);
  }

  public static int maxDc(int job, int level) {
    if (job == JOB_WARRIOR) return Math.max(1, level / 5);
    return Math.max(1, level / 7);
  }

  /**
   * Only the warrior gains natural AC ({@code MakeLong(0, nLevel div 7)}); the mage and the
   * Taoist branch both set {@code AC := 0} and get their defence from gear.
   */
  public static int maxAc(int job, int level) {
    return job == JOB_WARRIOR ? level / 7 : 0;
  }

  /** Delphi {@code Round} is round-half-to-even, which is what {@link Math#rint} does. */
  private static double rint(double value) {
    return Math.rint(value);
  }
}
