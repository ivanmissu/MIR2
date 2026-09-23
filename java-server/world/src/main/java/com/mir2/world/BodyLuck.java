package com.mir2.world;

/**
 * The 幸运值 (body luck) accumulator of {@code TBaseObject}: the {@code m_dBodyLuck} double
 * plus its derived integer level {@code m_nBodyLuckLevel} (ObjBase.pas:2374, {@code AddBodyLuck}).
 *
 * <p>Delphi grows the accumulator from experience ({@code AddBodyLuck(dwExp * 0.002)} in
 * {@code GetExp}, plus {@code AddBodyLuck(100)} on every level-up) and shrinks it on death and
 * murder. The integer level is {@code Trunc(m_dBodyLuck / BODYLUCKUNIT)} clamped to
 * {@code [-10, 5]}.
 *
 * <p>The level is a pure accounting stat in the current engine: Delphi only reads
 * {@code m_nBodyLuckLevel} inside the NPC weapon-upgrade success formula (ObjNpc.pas:1309) and
 * the {@code nCHECKLUCKYPOINT} quest condition (ObjNpc.pas:7152), both of which belong to the
 * still-red-lined NPC script engine. It is nonetheless tracked and persisted faithfully so the
 * value is correct the day those subsystems land, and so the murder / death penalties Delphi
 * applies are observable through the GM {@code @Luck} report path.
 *
 * <p>The class is immutable; {@link #add(double)} returns the next state, mirroring the way the
 * rest of the world module stages changes.
 */
public record BodyLuck(double value, int level) {

  /** {@code BODYLUCKUNIT} (M2Share.pas:94): one luck level is 5000 accumulator units. */
  public static final int UNIT = 5000;

  /** {@code AddBodyLuck} clamps the accumulator's growth at ±5 units and the level at [-10, 5]. */
  public static final int MAX_LEVEL = 5;
  public static final int MIN_LEVEL = -10;

  /** A fresh character starts at {@code m_dBodyLuck := 0} (ObjBase.pas:1226). */
  public static final BodyLuck NONE = new BodyLuck(0, 0);

  public BodyLuck {
    if (level < MIN_LEVEL || level > MAX_LEVEL) {
      throw new IllegalArgumentException("body luck level must be within " + MIN_LEVEL + ".." + MAX_LEVEL);
    }
  }

  /**
   * Rebuilds the state from a stored accumulator, recomputing the level exactly as
   * {@code AddBodyLuck(0)} does at login (UsrEngn.pas:2368 loads {@code m_dBodyLuck}, then the
   * enter-map path calls {@code AddBodyLuck(0)} to derive the level).
   */
  public static BodyLuck ofAccumulator(double value) {
    return new BodyLuck(value, deriveLevel(value));
  }

  /**
   * {@code TBaseObject.AddBodyLuck} (ObjBase.pas:2374). Positive deltas only apply while the
   * accumulator is below {@code +5 * UNIT}; negative deltas only while it is above
   * {@code -5 * UNIT} — the asymmetric guard is verbatim, so a single add can nudge the value a
   * little past the boundary but never compounds beyond it. The level is then re-derived and
   * clamped to {@code [-10, 5]}.
   */
  public BodyLuck add(double delta) {
    double next = value;
    if (delta > 0 && value < 5.0 * UNIT) {
      next = value + delta;
    }
    if (delta < 0 && value > -(5.0 * UNIT)) {
      next = value + delta;
    }
    return new BodyLuck(next, deriveLevel(next));
  }

  /** {@code n := Trunc(m_dBodyLuck / BODYLUCKUNIT)} then clamp to {@code [-10, 5]}. */
  private static int deriveLevel(double value) {
    int n = (int) (value / UNIT); // Delphi Trunc: truncates toward zero, like a Java long cast.
    if (n > MAX_LEVEL) n = MAX_LEVEL;
    if (n < MIN_LEVEL) n = MIN_LEVEL;
    return n;
  }
}
