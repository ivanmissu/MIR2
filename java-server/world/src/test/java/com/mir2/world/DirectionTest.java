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

  @Test
  void getNextDirectionKeepsTheM2ShareSnapQuirks() {
    Position origin = new Position(10, 10);
    // Plain per-axis sign cases agree with toward().
    assertEquals(Direction.UP_RIGHT, Direction.getNextDirection(origin, new Position(50, 2)));
    assertEquals(Direction.DOWN_LEFT, Direction.getNextDirection(origin, new Position(5, 20)));
    // M2Share.pas:3492 — one axis > 2 cells away and the other within ±1 snaps to the cardinal:
    // (11, 14) is "basically straight down", not DOWN_RIGHT.
    assertEquals(Direction.DOWN, Direction.getNextDirection(origin, new Position(11, 14)));
    assertEquals(Direction.UP, Direction.getNextDirection(origin, new Position(9, 4)));
    // The Y snap window has a strict lower bound (`sY > dy - 1` covers sY == dy and sY == dy + 1
    // but NOT sY == dy - 1): (14, 11) sits exactly on the excluded row and stays diagonal.
    assertEquals(Direction.DOWN_RIGHT, Direction.getNextDirection(origin, new Position(14, 11)));
    // The pair covers source-row and one row UP (above is -y in this coordinate system)…
    assertEquals(Direction.RIGHT, Direction.getNextDirection(origin, new Position(15, 10)));
    assertEquals(Direction.RIGHT, Direction.getNextDirection(origin, new Position(15, 9)));
    // …but NOT one row down — the strict `sY > dy - 1` asymmetry kept from Delphi.
    assertEquals(Direction.DOWN_RIGHT, Direction.getNextDirection(origin, new Position(15, 11)));
    // (14, 13) stays diagonal — both axes too far apart for either snap window.
    assertEquals(Direction.DOWN_RIGHT, Direction.getNextDirection(origin, new Position(14, 13)));
    // Identical endpoints keep the case body's standing default instead of throwing.
    assertEquals(Direction.DOWN, Direction.getNextDirection(origin, origin));
  }

  @Test
  void oppositeMatchesDelphiGetBackDir() {
    assertEquals(Direction.DOWN, Direction.UP.opposite());
    assertEquals(Direction.DOWN_LEFT, Direction.UP_RIGHT.opposite());
    assertEquals(Direction.LEFT, Direction.RIGHT.opposite());
    assertEquals(Direction.UP_LEFT, Direction.DOWN_RIGHT.opposite());
    assertEquals(Direction.UP, Direction.DOWN.opposite());
    assertEquals(Direction.UP_RIGHT, Direction.DOWN_LEFT.opposite());
    assertEquals(Direction.RIGHT, Direction.LEFT.opposite());
    assertEquals(Direction.DOWN_RIGHT, Direction.UP_LEFT.opposite());
  }
}
