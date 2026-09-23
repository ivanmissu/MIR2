package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The per-instance weapon luck/curse bytes {@code btValue[3]}/{@code btValue[4]}. */
class WeaponPointsTest {

  @Test
  void defaultIsNone() {
    assertTrue(WeaponPoints.NONE.isNone());
    assertEquals(0, WeaponPoints.NONE.luck());
    assertEquals(0, WeaponPoints.NONE.curse());
  }

  @Test
  void withLuckAndCurseAreCopyOnWrite() {
    WeaponPoints lucky = WeaponPoints.NONE.withLuck(3);
    assertEquals(3, lucky.luck());
    assertEquals(0, lucky.curse());
    assertFalse(lucky.isNone());
    assertTrue(WeaponPoints.NONE.isNone()); // original untouched

    WeaponPoints cursed = lucky.withCurse(2);
    assertEquals(3, cursed.luck());
    assertEquals(2, cursed.curse());
  }

  @Test
  void rejectsNonByteValues() {
    assertThrows(IllegalArgumentException.class, () -> new WeaponPoints(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new WeaponPoints(256, 0));
    assertThrows(IllegalArgumentException.class, () -> new WeaponPoints(0, -1));
    assertThrows(IllegalArgumentException.class, () -> new WeaponPoints(0, 256));
  }

  @Test
  void maxCurseMatchesMakeWeaponUnlockCap() {
    assertEquals(10, WeaponPoints.MAX_CURSE);
  }
}
