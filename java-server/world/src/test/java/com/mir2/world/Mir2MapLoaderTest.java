package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void extractsPackedDoorIndexAndOffsetAndSharesNearbyDoorStatus() throws Exception {
    byte[] data = mapBytes(4, 3, "door map");
    // TMapUnitInfo: DoorIndex at byte 6, DoorOffset at byte 7. Two nearby cells with the same
    // low-seven-bit index share a Delphi TDoorStatus regardless of their stored offsets.
    int first = Mir2MapLoader.HEADER_BYTES + (1 * 3 + 1) * Mir2MapLoader.CELL_BYTES;
    data[first + 6] = (byte) 0x83;
    data[first + 7] = 0x12;
    int second = Mir2MapLoader.HEADER_BYTES + (2 * 3 + 1) * Mir2MapLoader.CELL_BYTES;
    data[second + 6] = (byte) 0x83;
    data[second + 7] = (byte) 0x80;
    // $80 with a zero low portion is not a door according to Envir.LoadMapData.
    int ignored = Mir2MapLoader.HEADER_BYTES + (3 * 3) * Mir2MapLoader.CELL_BYTES;
    data[ignored + 6] = (byte) 0x80;
    Path file = temporaryDirectory.resolve("doors.map");
    Files.write(file, data);

    GameMap map = Mir2MapLoader.load("doors", file);
    assertEquals(2, map.doors().size());
    assertEquals(new GameMap.Door(new Position(1, 1), 3, 0x12, false),
        map.doorAt(new Position(1, 1)).orElseThrow());
    assertTrue(map.openDoor(new Position(1, 1), 100L).isPresent());
    assertTrue(map.doorAt(new Position(2, 1)).orElseThrow().open(),
        "nearby cells with the same DoorIndex share one status");
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
