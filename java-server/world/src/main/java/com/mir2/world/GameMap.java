package com.mir2.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * One MIR2 map's collision and moving-object index.
 *
 * <p>The original {@code TEnvirnoment.MapCellArray} is column-major and permits one solid moving
 * object per walkable cell. This class retains those semantics while keeping all mutations on the
 * world owner thread.
 */
public final class GameMap {
  public static final byte WALKABLE = 0;
  public static final byte BACKGROUND_BLOCKED = 1;
  public static final byte FOREGROUND_BLOCKED = 2;
  public static final int MAX_CELLS = 1_000_000;

  private final String id;
  private final String title;
  private final int width;
  private final int height;
  private final byte[] collisionFlags;
  private final int[] occupants;

  private GameMap(String id, String title, int width, int height, byte[] collisionFlags) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(title, "title");
    long cells = (long) width * height;
    if (width <= 1 || height <= 1 || cells > MAX_CELLS)
      throw new IllegalArgumentException("map dimensions must be greater than one and at most 1,000,000 cells");
    if (collisionFlags.length != cells) throw new IllegalArgumentException("collision data size mismatch");
    for (byte flag : collisionFlags) {
      if (flag < WALKABLE || flag > FOREGROUND_BLOCKED)
        throw new IllegalArgumentException("invalid map collision flag: " + flag);
    }
    this.id = id;
    this.title = title;
    this.width = width;
    this.height = height;
    this.collisionFlags = collisionFlags.clone();
    this.occupants = new int[collisionFlags.length];
  }

  public static GameMap empty(String id, String title, int width, int height) {
    return new GameMap(id, title, width, height, new byte[Math.multiplyExact(width, height)]);
  }

  /** Convenient constructor for tests and generated PoC maps. */
  public static GameMap withBlockedCells(
      String id, String title, int width, int height, Collection<Position> blockedCells) {
    byte[] flags = new byte[Math.multiplyExact(width, height)];
    for (Position position : blockedCells) {
      if (position.x() < 0 || position.x() >= width || position.y() < 0 || position.y() >= height)
        throw new IllegalArgumentException("blocked cell lies outside map: " + position);
      flags[position.x() * height + position.y()] = BACKGROUND_BLOCKED;
    }
    return new GameMap(id, title, width, height, flags);
  }

  static GameMap fromCollisionFlags(String id, String title, int width, int height, byte[] flags) {
    return new GameMap(id, title, width, height, flags);
  }

  public String id() {
    return id;
  }

  public String title() {
    return title;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public MapInfo info() {
    return new MapInfo(id, title, width, height);
  }

  public boolean contains(Position position) {
    return position.x() >= 0 && position.x() < width && position.y() >= 0 && position.y() < height;
  }

  public byte collisionFlag(Position position) {
    return collisionFlags[index(position)];
  }

  public boolean isTerrainWalkable(Position position) {
    return contains(position) && collisionFlags[indexUnchecked(position)] == WALKABLE;
  }

  public boolean canWalk(Position position) {
    return isTerrainWalkable(position) && occupants[indexUnchecked(position)] == 0;
  }

  public int objectAt(Position position) {
    return contains(position) ? occupants[indexUnchecked(position)] : 0;
  }

  void place(int objectId, Position position) {
    if (objectId <= 0) throw new IllegalArgumentException("object id must be positive");
    if (!canWalk(position)) throw new IllegalStateException("map cell is not available: " + position);
    occupants[indexUnchecked(position)] = objectId;
  }

  void move(int objectId, Position source, Position target) {
    int sourceIndex = index(source);
    if (occupants[sourceIndex] != objectId) throw new IllegalStateException("source cell does not contain object");
    if (!canWalk(target)) throw new IllegalStateException("target cell is not available: " + target);
    occupants[sourceIndex] = 0;
    occupants[indexUnchecked(target)] = objectId;
  }

  void remove(int objectId, Position position) {
    int index = index(position);
    if (occupants[index] != objectId) throw new IllegalStateException("map cell does not contain object");
    occupants[index] = 0;
  }

  /** Returns occupied object ids in deterministic column-major order. */
  List<Integer> objectsInSquare(Position center, int range) {
    if (range < 0) throw new IllegalArgumentException("range must not be negative");
    int lowX = Math.max(0, center.x() - range);
    int highX = Math.min(width - 1, center.x() + range);
    int lowY = Math.max(0, center.y() - range);
    int highY = Math.min(height - 1, center.y() + range);
    List<Integer> result = new ArrayList<>();
    for (int x = lowX; x <= highX; x++) {
      for (int y = lowY; y <= highY; y++) {
        int objectId = occupants[x * height + y];
        if (objectId != 0) result.add(objectId);
      }
    }
    return result;
  }

  private int index(Position position) {
    if (!contains(position)) throw new IllegalArgumentException("position lies outside map: " + position);
    return indexUnchecked(position);
  }

  private int indexUnchecked(Position position) {
    return position.x() * height + position.y();
  }

  public record MapInfo(String id, String title, int width, int height) {
    public MapInfo {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(title, "title");
      if (width <= 1 || height <= 1) throw new IllegalArgumentException("invalid map dimensions");
    }
  }
}
