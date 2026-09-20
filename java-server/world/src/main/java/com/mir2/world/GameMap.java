package com.mir2.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One MIR2 map's collision, door and moving-object index.
 *
 * <p>The original {@code TEnvirnoment.MapCellArray} is column-major and permits one solid moving
 * object per walkable cell. Door definitions retain {@code TMapUnitInfo.btDoorIndex} and
 * {@code btDoorOffset}; their mutable open state is owned by the single world thread just like
 * Delphi's shared {@code TDoorStatus} records.
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
  private final Map<Position, DoorCell> doors;
  private final List<DoorStatus> doorStatuses;

  private GameMap(
      String id,
      String title,
      int width,
      int height,
      byte[] collisionFlags,
      Collection<DoorDefinition> doorDefinitions) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(collisionFlags, "collisionFlags");
    Objects.requireNonNull(doorDefinitions, "doorDefinitions");
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
    this.doors = new LinkedHashMap<>();
    this.doorStatuses = new ArrayList<>();
    installDoors(doorDefinitions);
  }

  public static GameMap empty(String id, String title, int width, int height) {
    return new GameMap(id, title, width, height, new byte[Math.multiplyExact(width, height)], List.of());
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
    return new GameMap(id, title, width, height, flags, List.of());
  }

  /** Convenient map fixture with legacy door cells. Door cells do not alter terrain collision. */
  public static GameMap withDoors(
      String id, String title, int width, int height, Collection<DoorDefinition> doorDefinitions) {
    return new GameMap(id, title, width, height, new byte[Math.multiplyExact(width, height)], doorDefinitions);
  }

  static GameMap fromCollisionFlags(String id, String title, int width, int height, byte[] flags) {
    return new GameMap(id, title, width, height, flags, List.of());
  }

  static GameMap fromCollisionFlags(
      String id, String title, int width, int height, byte[] flags, Collection<DoorDefinition> doors) {
    return new GameMap(id, title, width, height, flags, doors);
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

  /** Returns the loaded door metadata and current shared open state at one cell, if any. */
  public Optional<Door> doorAt(Position position) {
    DoorCell cell = doors.get(position);
    return cell == null ? Optional.empty() : Optional.of(cell.snapshot());
  }

  /** Snapshot of all doors in deterministic map-file (column-major) order. */
  public List<Door> doors() {
    return doors.values().stream().map(DoorCell::snapshot).toList();
  }

  /**
   * Opens a door group. A second request while already open is intentionally silent, matching
   * {@code TUserEngine.OpenDoor}; {@code TPlayObject.ClientOpenDoor} likewise returns without a
   * response for a missing/already-open door.
   */
  Optional<Door> openDoor(Position position, long now) {
    DoorCell cell = doors.get(position);
    if (cell == null || cell.status.opened) return Optional.empty();
    cell.status.opened = true;
    cell.status.openedAt = now;
    return Optional.of(cell.snapshot());
  }

  /**
   * Closes every group whose five-second server-side open window expired. The event coordinate is
   * the group's first map-file cell, the same deterministic equivalent of Delphi iterating
   * {@code m_DoorList} and calling {@code CloseDoor} on the first matching shared status.
   */
  List<Door> closeExpiredDoors(long now, long openMillis) {
    if (openMillis < 0) throw new IllegalArgumentException("door open duration must not be negative");
    List<Door> closed = new ArrayList<>();
    for (DoorStatus status : doorStatuses) {
      if (!status.opened || now - status.openedAt <= openMillis) continue;
      status.opened = false;
      closed.add(status.representative.snapshot());
    }
    return List.copyOf(closed);
  }

  /**
   * Mirrors {@code TEnvirnoment.ArroundDoorOpened}: no nearby door allows passage; when one or
   * more door cells are within the inclusive 1-cell square, every shared door status must be open.
   */
  boolean doorsAroundAreOpen(Position position) {
    for (DoorCell cell : doors.values()) {
      if (Math.abs(cell.position.x() - position.x()) <= 1
          && Math.abs(cell.position.y() - position.y()) <= 1
          && !cell.status.opened) return false;
    }
    return true;
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

  private void installDoors(Collection<DoorDefinition> definitions) {
    for (DoorDefinition definition : definitions) {
      Objects.requireNonNull(definition, "door definition");
      if (!contains(definition.position()))
        throw new IllegalArgumentException("door lies outside map: " + definition.position());
      if (doors.containsKey(definition.position()))
        throw new IllegalArgumentException("duplicate door cell: " + definition.position());

      // Envir.LoadMapData finds the first previous cell within +/-10 with the same DoorIndex and
      // shares its TDoorStatus. Checking every previously loaded cell (in map-file order) keeps
      // that source ordering, including its intentionally non-union-find grouping edge cases.
      DoorStatus status = null;
      for (DoorCell previous : doors.values()) {
        if (previous.index == definition.index()
            && Math.abs(previous.position.x() - definition.position().x()) <= 10
            && Math.abs(previous.position.y() - definition.position().y()) <= 10) {
          status = previous.status;
          break;
        }
      }
      DoorCell cell = new DoorCell(definition.position(), definition.index(), definition.offset(), status);
      if (status == null) {
        status = new DoorStatus(cell);
        cell.status = status;
        doorStatuses.add(status);
      }
      doors.put(cell.position, cell);
    }
  }

  private int index(Position position) {
    if (!contains(position)) throw new IllegalArgumentException("position lies outside map: " + position);
    return indexUnchecked(position);
  }

  private int indexUnchecked(Position position) {
    return position.x() * height + position.y();
  }

  /** Raw data from a {@code TMapUnitInfo} door cell. {@code index} is 1..127, offset is 0..255. */
  public record DoorDefinition(Position position, int index, int offset) {
    public DoorDefinition {
      Objects.requireNonNull(position, "position");
      if (index < 1 || index > 0x7f) throw new IllegalArgumentException("door index must be in 1..127");
      if (offset < 0 || offset > 0xff) throw new IllegalArgumentException("door offset must be an unsigned byte");
    }
  }

  /** A door's raw map metadata plus current shared group state. */
  public record Door(Position position, int index, int offset, boolean open) {
    public Door {
      Objects.requireNonNull(position, "position");
    }
  }

  private static final class DoorCell {
    private final Position position;
    private final int index;
    private final int offset;
    private DoorStatus status;

    private DoorCell(Position position, int index, int offset, DoorStatus status) {
      this.position = position;
      this.index = index;
      this.offset = offset;
      this.status = status;
    }

    private Door snapshot() {
      return new Door(position, index, offset, status.opened);
    }
  }

  private static final class DoorStatus {
    private final DoorCell representative;
    private boolean opened;
    private long openedAt;

    private DoorStatus(DoorCell representative) {
      this.representative = representative;
    }
  }

  public record MapInfo(String id, String title, int width, int height) {
    public MapInfo {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(title, "title");
      if (width <= 1 || height <= 1) throw new IllegalArgumentException("invalid map dimensions");
    }
  }
}
