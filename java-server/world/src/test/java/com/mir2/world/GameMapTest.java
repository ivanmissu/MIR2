package com.mir2.world;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameMapTest {
  @Test
  void collisionAndMovingObjectOccupancyAreBothEnforced() {
    GameMap map = GameMap.withBlockedCells("0", "比奇", 8, 8, List.of(new Position(2, 2)));
    assertFalse(map.isTerrainWalkable(new Position(2, 2)));
    assertFalse(map.canWalk(new Position(-1, 0)));
    assertTrue(map.canWalk(new Position(1, 1)));

    map.place(7, new Position(1, 1));
    assertEquals(7, map.objectAt(new Position(1, 1)));
    assertFalse(map.canWalk(new Position(1, 1)));
    assertThrows(IllegalStateException.class, () -> map.place(8, new Position(1, 1)));

    map.move(7, new Position(1, 1), new Position(1, 2));
    assertEquals(0, map.objectAt(new Position(1, 1)));
    assertEquals(7, map.objectAt(new Position(1, 2)));
    map.remove(7, new Position(1, 2));
    assertTrue(map.canWalk(new Position(1, 2)));
  }

  @Test
  void squareIndexReturnsColumnMajorObjectOrder() {
    GameMap map = GameMap.empty("0", "test", 20, 20);
    map.place(2, new Position(11, 9));
    map.place(1, new Position(9, 11));
    map.place(3, new Position(15, 15));
    assertEquals(List.of(1, 2), map.objectsInSquare(new Position(10, 10), 2));
  }
}
