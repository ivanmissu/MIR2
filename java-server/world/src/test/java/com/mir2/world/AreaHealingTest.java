package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AreaHealingTest {
  private record Target(int objectId, Position position, boolean ally, int hp, int maxHp)
      implements AreaTargetSelector.AreaTarget {}

  @Test
  void clampsEachTargetAndSkipsFullHealth() {
    var targets = List.of(
        new Target(2, new Position(1, 0), true, 90, 100),
        new Target(1, new Position(0, 1), true, 100, 100),
        new Target(3, new Position(0, 2), true, 40, 50));
    assertEquals(List.of(new AreaHealing.Result(2, 10, 100), new AreaHealing.Result(3, 10, 50)),
        AreaHealing.resolve(new Position(0, 0), 2, 20, targets, Target::ally,
            Target::hp, Target::maxHp));
  }

  @Test
  void rejectsInvalidHealthAndNegativePower() {
    var bad = List.of(new Target(1, new Position(0, 0), true, 101, 100));
    assertThrows(IllegalArgumentException.class, () -> AreaHealing.resolve(
        new Position(0, 0), 1, 1, bad, Target::ally, Target::hp, Target::maxHp));
    assertThrows(IllegalArgumentException.class, () -> AreaHealing.resolve(
        new Position(0, 0), 1, -1, List.<Target>of(), Target::ally, Target::hp, Target::maxHp));
  }
}
