package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mir2MapLoaderTest {
  private static final Charset GBK = Charset.forName("GBK");

  @TempDir
  Path temporaryDirectory;

  @Test
  void readsPackedHeaderAndColumnMajorCollisionFlags() throws Exception {
    int width = 2;
    int height = 3;
    byte[] data = mapBytes(width, height, "比奇省");
    // x=1,y=0 is cell index 3. Background high bit marks it blocked.
    putUnsignedShort(data, Mir2MapLoader.HEADER_BYTES + 3 * Mir2MapLoader.CELL_BYTES, 0x8001);
    // x=0,y=2 is cell index 2. Foreground takes precedence over background.
    int foregroundCell = Mir2MapLoader.HEADER_BYTES + 2 * Mir2MapLoader.CELL_BYTES;
    putUnsignedShort(data, foregroundCell, 0x8001);
    putUnsignedShort(data, foregroundCell + 4, 0x8002);
    Path file = temporaryDirectory.resolve("0.map");
    Files.write(file, data);

    GameMap map = Mir2MapLoader.load("0", file);
    assertEquals(2, map.width());
    assertEquals(3, map.height());
    assertEquals("比奇省", map.title());
    assertEquals(GameMap.BACKGROUND_BLOCKED, map.collisionFlag(new Position(1, 0)));
    assertEquals(GameMap.FOREGROUND_BLOCKED, map.collisionFlag(new Position(0, 2)));
    assertTrue(map.isTerrainWalkable(new Position(0, 1)));
  }

  @Test
  void decodesDoorAnchorCellsFromBtDoorIndex() throws Exception {
    int width = 4;
    int height = 4;
    byte[] data = mapBytes(width, height, "doors");
    // Cell (0,0): index 0 → btDoorIndex with the anchor flag but point 0 → not a door.
    writeCellByte(data, width, height, 0, 0, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80);
    // Cell (2,3): anchor carrying door index 7.
    writeCellByte(data, width, height, 2, 3, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80 | 7);
    // Cell (3,0): beyond the ±10 radius of (2,3) already fails the share rule twice over
    // (different index), another status must be created.
    writeCellByte(data, width, height, 3, 0, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80 | 9);
    Path file = temporaryDirectory.resolve("doors.map");
    Files.write(file, data);

    GameMap map = Mir2MapLoader.load("0", file);

    assertEquals(2, map.doors().size());
    assertNull(map.doorAt(new Position(0, 0)));
    DoorInfo big = map.doorAt(new Position(2, 3));
    DoorInfo small = map.doorAt(new Position(3, 0));
    assertEquals(7, big.index());
    assertEquals(9, small.index());
    assertTrue(big.status() != small.status(), "different indexes must hold separate TDoorStatus");
  }

  @Test
  void sameIndexAnchorsWithinTenCellsShareTheirDoorStatus() throws Exception {
    int width = 32;
    int height = 32;
    byte[] data = mapBytes(width, height, "linked");
    writeCellByte(data, width, height, 4, 4, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80 | 5);
    writeCellByte(data, width, height, 14, 14, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80 | 5);
    writeCellByte(data, width, height, 25, 25, Mir2MapLoader.DOOR_INDEX_OFFSET, 0x80 | 5);
    Path file = temporaryDirectory.resolve("linked.map");
    Files.write(file, data);

    GameMap map = Mir2MapLoader.load("0", file);

    DoorInfo first = map.doorAt(new Position(4, 4));
    DoorInfo linked = map.doorAt(new Position(14, 14));
    DoorInfo distant = map.doorAt(new Position(25, 25));
    assertTrue(linked.status() == first.status(), "anchors within ±10 of the same index share one status");
    assertTrue(distant.status() != first.status(), "anchors further than 10 cells get a fresh status");
  }

  private static void writeCellByte(
      byte[] data, int width, int height, int x, int y, int cellOffset, int value) {
    data[Mir2MapLoader.HEADER_BYTES + (x * height + y) * Mir2MapLoader.CELL_BYTES + cellOffset] =
        (byte) value;
  }

  @Test
  void rejectsTruncatedAndDimensionallyInvalidFiles() throws Exception {
    Path truncated = temporaryDirectory.resolve("truncated.map");
    Files.write(truncated, new byte[51]);
    assertThrows(IOException.class, () -> Mir2MapLoader.load("0", truncated));

    Path wrongSize = temporaryDirectory.resolve("wrong-size.map");
    byte[] complete = mapBytes(2, 2, "map");
    Files.write(wrongSize, java.util.Arrays.copyOf(complete, complete.length - 1));
    assertThrows(IOException.class, () -> Mir2MapLoader.load("0", wrongSize));

    byte[] invalid = new byte[Mir2MapLoader.HEADER_BYTES];
    putUnsignedShort(invalid, 0, 1);
    putUnsignedShort(invalid, 2, 1);
    Path invalidDimensions = temporaryDirectory.resolve("invalid.map");
    Files.write(invalidDimensions, invalid);
    assertThrows(IOException.class, () -> Mir2MapLoader.load("0", invalidDimensions));
  }

  private static byte[] mapBytes(int width, int height, String title) {
    byte[] data = new byte[Mir2MapLoader.HEADER_BYTES + width * height * Mir2MapLoader.CELL_BYTES];
    putUnsignedShort(data, 0, width);
    putUnsignedShort(data, 2, height);
    byte[] titleBytes = title.getBytes(GBK);
    data[4] = (byte) titleBytes.length;
    System.arraycopy(titleBytes, 0, data, 5, titleBytes.length);
    return data;
  }

  private static void putUnsignedShort(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
  }
}
