package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AreaTargetSelectorTest {
  private record Target(int objectId, Position position, boolean ally)
      implements AreaTargetSelector.AreaTarget {}

  @Test
  void selectsInclusiveSquareAndStableDistanceThenIdOrder() {
    var center = new Position(10, 10);
    var targets = List.of(
        new Target(4, new Position(12, 10), true),
        new Target(2, new Position(9, 9), true),
        new Target(3, new Position(10, 11), true),
        new Target(1, new Position(13, 10), true));

    assertEquals(List.of(2, 3, 4), AreaTargetSelector.square(center, 2, targets, Target::ally)
        .stream().map(Target::objectId).toList());
  }

  @Test
  void legalTargetPredicateExcludesEnemiesWithoutChangingGeometry() {
    var targets = List.of(
        new Target(1, new Position(10, 10), true),
        new Target(2, new Position(10, 10), false));

    assertEquals(List.of(1), AreaTargetSelector.square(
        new Position(10, 10), 0, targets, Target::ally).stream().map(Target::objectId).toList());
  }

  @Test
  void rejectsNegativeRadius() {
    assertThrows(IllegalArgumentException.class, () -> AreaTargetSelector.square(
        new Position(0, 0), -1, List.<Target>of(), target -> true));
  }
}
