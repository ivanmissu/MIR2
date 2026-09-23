package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The 幸运值 accumulator {@code AddBodyLuck} (ObjBase.pas:2374). */
class BodyLuckTest {

  @Test
  void startsEmpty() {
    assertEquals(0.0, BodyLuck.NONE.value());
    assertEquals(0, BodyLuck.NONE.level());
  }

  @Test
  void positiveDeltaGrowsAccumulatorAndDerivesLevel() {
    // Trunc(12000 / 5000) = 2.
    BodyLuck luck = BodyLuck.NONE.add(12_000);
    assertEquals(12_000.0, luck.value());
    assertEquals(2, luck.level());
  }

  @Test
  void negativeDeltaShrinksAccumulator() {
    // The -500 the murder branch always applies (ObjBase.pas:20938).
    BodyLuck luck = BodyLuck.ofAccumulator(1_000).add(-500);
    assertEquals(500.0, luck.value());
    assertEquals(0, luck.level());
  }

  @Test
  void levelTruncatesTowardZeroLikeDelphiTrunc() {
    assertEquals(0, BodyLuck.ofAccumulator(4_999).level());
    assertEquals(0, BodyLuck.ofAccumulator(-4_999).level());
    assertEquals(1, BodyLuck.ofAccumulator(5_000).level());
    assertEquals(-1, BodyLuck.ofAccumulator(-5_000).level());
  }

  @Test
  void positiveDeltaIsGuardedAtPlusFiveUnits() {
    // AddBodyLuck only adds a positive delta while value < 5 * UNIT; once at/above the guard
    // a further positive delta is ignored entirely (verbatim asymmetric guard).
    BodyLuck atGuard = BodyLuck.ofAccumulator(5.0 * BodyLuck.UNIT);
    BodyLuck after = atGuard.add(1_000);
    assertEquals(5.0 * BodyLuck.UNIT, after.value());
  }

  @Test
  void negativeDeltaIsGuardedAtMinusFiveUnits() {
    BodyLuck atGuard = BodyLuck.ofAccumulator(-(5.0 * BodyLuck.UNIT));
    BodyLuck after = atGuard.add(-1_000);
    assertEquals(-(5.0 * BodyLuck.UNIT), after.value());
  }

  @Test
  void singleAddCanNudgePastTheGuardButNeverCompounds() {
    // Just below the guard: one big add crosses it (value can exceed 5*UNIT once),
    // but level is still clamped to the max of 5.
    BodyLuck near = BodyLuck.ofAccumulator(5.0 * BodyLuck.UNIT - 1);
    BodyLuck after = near.add(100_000);
    assertEquals(5.0 * BodyLuck.UNIT - 1 + 100_000, after.value());
    assertEquals(BodyLuck.MAX_LEVEL, after.level());
    // A further positive add is now ignored (value >= guard).
    assertEquals(after.value(), after.add(50).value());
  }

  @Test
  void levelClampsToBounds() {
    assertEquals(BodyLuck.MAX_LEVEL, BodyLuck.ofAccumulator(1_000_000).level());
    assertEquals(BodyLuck.MIN_LEVEL, BodyLuck.ofAccumulator(-1_000_000).level());
  }

  @Test
  void constructorRejectsOutOfRangeLevel() {
    assertThrows(IllegalArgumentException.class, () -> new BodyLuck(0, 6));
    assertThrows(IllegalArgumentException.class, () -> new BodyLuck(0, -11));
  }
}
