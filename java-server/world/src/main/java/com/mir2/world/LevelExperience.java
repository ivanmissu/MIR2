package com.mir2.world;

/**
 * The classic level-up experience table, {@code g_dwOldNeedExps} (M2Share.pas:2216) plus the
 * lookup {@code TBaseObject.GetLevelExp} (ObjBase.pas:19184).
 *
 * <p>Delphi declares {@code TLevelNeedExp = array[1..500] of DWord} (Grobal2.pas:1686) and
 * {@code GetLevelExp} clamps above {@code MAXLEVEL} (500) to the last entry:
 *
 * <pre>
 *   if nLevel &lt;= MAXLEVEL then Result := g_Config.dwNeedExps[nLevel]
 *   else Result := g_Config.dwNeedExps[High(g_Config.dwNeedExps)];
 * </pre>
 *
 * <p>Level 0 is not in the Delphi array at all; the server only ever asks for a level it has
 * already raised to at least 1 ({@code UsrEngn.pas:505} forces {@code Abil.Level := 1} on a
 * fresh character), so index 0 maps to the level-1 requirement here rather than throwing.
 *
 * <p>The shipped table tops out at 4,000,000,000 which overflows a signed 32-bit int, so the
 * values are kept as {@code long}. On the wire {@code TAbility.MaxExp} is an unsigned DWord,
 * and the codec masks it back down.
 */
public final class LevelExperience {
  /** {@code MAXLEVEL} in Grobal2.pas:1086. */
  public static final int MAX_LEVEL = 500;

  /** {@code MAXUPLEVEL = High(Word)} in M2Share.pas:91 — the cap {@code GetExp} stops raising at. */
  public static final int MAX_UP_LEVEL = 65535;

  /** The plateau value every level from 51 upwards shares in the shipped table. */
  private static final long PLATEAU = 4_000_000_000L;

  /** Levels 1..50 of {@code g_dwOldNeedExps}; index 0 holds the level-1 requirement. */
  private static final long[] NEED_EXP = {
      100L, // 1
      200L, // 2
      300L, // 3
      400L, // 4
      600L, // 5
      900L, // 6
      1_200L, // 7
      1_700L, // 8
      2_500L, // 9
      6_000L, // 10
      8_000L, // 11
      10_000L, // 12
      15_000L, // 13
      30_000L, // 14
      40_000L, // 15
      50_000L, // 16
      70_000L, // 17
      100_000L, // 18
      120_000L, // 19
      140_000L, // 20
      250_000L, // 21
      300_000L, // 22
      350_000L, // 23
      400_000L, // 24
      500_000L, // 25
      700_000L, // 26
      1_000_000L, // 27
      1_400_000L, // 28
      1_800_000L, // 29
      2_000_000L, // 30
      2_400_000L, // 31
      2_800_000L, // 32
      3_200_000L, // 33
      3_600_000L, // 34
      4_000_000L, // 35
      4_800_000L, // 36
      5_600_000L, // 37
      8_200_000L, // 38
      9_000_000L, // 39
      12_000_000L, // 40
      16_000_000L, // 41
      30_000_000L, // 42
      50_000_000L, // 43
      80_000_000L, // 44
      120_000_000L, // 45
      480_000_000L, // 46
      1_000_000_000L, // 47
      3_000_000_000L, // 48
      3_500_000_000L, // 49
      4_000_000_000L, // 50
  };

  private LevelExperience() {}

  /**
   * {@code GetLevelExp(nLevel)}: the experience the character must accumulate before it
   * advances past {@code level}.
   */
  public static long forLevel(int level) {
    if (level < 0) throw new IllegalArgumentException("level must not be negative");
    // Delphi's array starts at 1; a level-0 character is normalised to 1 before it is used.
    int effective = Math.max(1, Math.min(level, MAX_LEVEL));
    if (effective <= NEED_EXP.length) return NEED_EXP[effective - 1];
    return PLATEAU;
  }
}
