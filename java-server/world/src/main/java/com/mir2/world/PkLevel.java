package com.mir2.world;

/**
 * The PK-point model behind {@code TBaseObject.PKLevel} (ObjBase.pas:2234) and the name colour
 * it drives ({@code GetNamecolor} ObjBase.pas:19197, {@code GetCharColor} ObjBase.pas:19013).
 *
 * <p>Delphi keeps a single integer, {@code m_nPkPoint}, on the character record
 * ({@code HumData.nPKPOINT}, ObjBase.pas:24904). Everything else is derived:
 *
 * <ul>
 *   <li>{@code PKLevel = m_nPkPoint div 100} — 0 白名, 1 黄名 (100+), 2 红名 (200+),
 *       3+ 深红 (300+), which is the threshold the death penalties branch on.</li>
 *   <li>{@code IncPkPoint}/{@code DecPKPoint} adjust the counter and re-broadcast the name
 *       colour only when the derived level actually changed.</li>
 *   <li>{@code TBaseObject.Run} (ObjBase.pas:4042) bleeds one point off every
 *       {@code dwDecPkPointTime} = 2 minutes, so a murder fades after a few hours.</li>
 * </ul>
 *
 * <p>{@code m_boPKFlag} is a separate, transient "recently fought another player" marker
 * ({@code SetPKFlag} ObjBase.pas:21220, cleared by {@code CheckPKStatus} ObjBase.pas:18868
 * after {@code dwPKFlagTime} = 60 seconds). It is not persisted in Delphi either.
 */
public final class PkLevel {

  /** {@code PKLevel := m_nPkPoint div 100} (ObjBase.pas:2236). */
  public static final int POINTS_PER_LEVEL = 100;

  /** {@code g_Config.nKillHumanAddPKPoint} (M2Share.pas:1712) = 100 — one murder, one level. */
  public static final int KILL_HUMAN_ADD_PK_POINT = 100;

  /** {@code g_Config.dwDecPkPointTime} (M2Share.pas:1709) = 2 minutes between decays. */
  public static final long DEC_PK_POINT_MILLIS = 2 * 60 * 1000L;

  /** {@code g_Config.nDecPkPointCount} (M2Share.pas:1710) = 1 point per decay window. */
  public static final int DEC_PK_POINT_COUNT = 1;

  /** {@code g_Config.dwPKFlagTime} (M2Share.pas:1711) = 60 seconds of "in a fight" colour. */
  public static final long PK_FLAG_MILLIS = 60 * 1000L;

  /** {@code m_btNameColor} initial value (ObjBase.pas:1223) — the ordinary white name. */
  public static final int DEFAULT_NAME_COLOR = 255;

  /** {@code g_Config.btPKFlagNameColor} (M2Share.pas:2039) = $2F, the "recently fought" tint. */
  public static final int PK_FLAG_NAME_COLOR = 0x2F;

  /** {@code g_Config.btPKLevel1NameColor} (M2Share.pas:2040) = $FB — 黄名. */
  public static final int PK_LEVEL1_NAME_COLOR = 0xFB;

  /** {@code g_Config.btPKLevel2NameColor} (M2Share.pas:2041) = $F9 — 红名. */
  public static final int PK_LEVEL2_NAME_COLOR = 0xF9;

  private PkLevel() {}

  /** {@code TBaseObject.PKLevel} — the derived level, never negative. */
  public static int of(int pkPoint) {
    return Math.max(0, pkPoint) / POINTS_PER_LEVEL;
  }

  /** True once {@code PKLevel >= 2}: the red name that drops its whole bag on death. */
  public static boolean isRed(int pkPoint) {
    return of(pkPoint) >= 2;
  }

  /**
   * {@code GetNamecolor} followed by the {@code m_boPKFlag} override in {@code GetCharColor}.
   * The Delphi order matters: the flag tint wins over the level colour, but only while the
   * level is still below 2 (a red name stays red through a duel).
   */
  public static int nameColor(int pkPoint, boolean pkFlag) {
    int level = of(pkPoint);
    int color = DEFAULT_NAME_COLOR;
    if (level == 1) color = PK_LEVEL1_NAME_COLOR;
    if (level >= 2) color = PK_LEVEL2_NAME_COLOR;
    if (level < 2 && pkFlag) color = PK_FLAG_NAME_COLOR;
    return color;
  }
}
