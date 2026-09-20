package com.mir2.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectionTest {
  @Test
  void numericCodesAndDeltasMatchGrobal2() {
    Position origin = new Position(10, 10);
    assertEquals(new Position(10, 9), origin.translate(Direction.fromCode(0), 1));
    assertEquals(new Position(12, 8), origin.translate(Direction.fromCode(1), 2));
    assertEquals(new Position(8, 10), origin.translate(Direction.fromCode(6), 2));
    assertEquals(new Position(9, 9), origin.translate(Direction.fromCode(7), 1));
    assertThrows(IllegalArgumentException.class, () -> Direction.fromCode(8));
  }

  @Test
  void directionTowardUsesEightWaySignSemantics() {
    Position origin = new Position(10, 10);
    assertEquals(Direction.UP_RIGHT, Direction.toward(origin, new Position(50, 2)));
    assertEquals(Direction.DOWN, Direction.toward(origin, new Position(10, 11)));
    assertThrows(IllegalArgumentException.class, () -> Direction.toward(origin, origin));
  }
}
