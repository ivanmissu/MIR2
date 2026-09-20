package com.mir2.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the 52-byte header and 12-byte column-major cells used by the legacy {@code .map} files. */
public final class Mir2MapLoader {
  public static final int HEADER_BYTES = 52;
  public static final int CELL_BYTES = 12;
  private static final int TITLE_BYTES = 16;
  private static final Charset GBK = Charset.forName("GBK");

  private Mir2MapLoader() {}

  public static GameMap load(String mapId, Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    if (data.length < HEADER_BYTES) throw new IOException("truncated MIR2 map header: " + path);

    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    int width = Short.toUnsignedInt(buffer.getShort());
    int height = Short.toUnsignedInt(buffer.getShort());
    long cellCount = (long) width * height;
    if (width <= 1 || height <= 1 || cellCount > GameMap.MAX_CELLS)
      throw new IOException("invalid MIR2 map dimensions " + width + "x" + height + ": " + path);

    long expectedBytes = HEADER_BYTES + cellCount * CELL_BYTES;
    if (data.length < expectedBytes)
      throw new IOException("truncated MIR2 map data: expected at least " + expectedBytes
          + " bytes but found " + data.length);

    int titleLength = Byte.toUnsignedInt(data[4]);
    if (titleLength > TITLE_BYTES) throw new IOException("invalid MIR2 map title length: " + titleLength);
    String title = new String(data, 5, titleLength, GBK);

    byte[] collisionFlags = new byte[(int) cellCount];
    List<GameMap.DoorDefinition> doors = new ArrayList<>();
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        int index = x * height + y;
        int offset = HEADER_BYTES + index * CELL_BYTES;
        int background = unsignedShort(data, offset);
        int foreground = unsignedShort(data, offset + 4);
        if ((background & 0x8000) != 0) collisionFlags[index] = GameMap.BACKGROUND_BLOCKED;
        if ((foreground & 0x8000) != 0) collisionFlags[index] = GameMap.FOREGROUND_BLOCKED;

        // TMapUnitInfo is packed: BkImg, MidImg, FrImg, DoorIndex, DoorOffset, AniFrame,
        // AniTick, Area, Light. Envir.LoadMapData only registers $80-tagged non-zero indexes;
        // DoorOffset is retained for client/map diagnostics but does not set server DoorStatus.
        int doorIndex = Byte.toUnsignedInt(data[offset + 6]);
        if ((doorIndex & 0x80) != 0 && (doorIndex & 0x7f) > 0) {
          doors.add(new GameMap.DoorDefinition(new Position(x, y), doorIndex & 0x7f,
              Byte.toUnsignedInt(data[offset + 7])));
        }
      }
    }
    return GameMap.fromCollisionFlags(mapId, title, width, height, collisionFlags, doors);
  }

  private static int unsignedShort(byte[] data, int offset) {
    return Byte.toUnsignedInt(data[offset]) | (Byte.toUnsignedInt(data[offset + 1]) << 8);
  }
}
