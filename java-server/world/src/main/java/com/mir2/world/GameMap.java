package com.mir2.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  /**
   * Upper bound on a map's cell count, a sanity check against a corrupt {@code .map} header
   * rather than a format limit (the header stores width and height as {@code Word}s).
   * 比奇省 — {@code 0.map}, the default spawn map — is right at 1000x1000, so the cap has to
   * sit comfortably above a million; each cell costs five bytes of collision and occupancy.
   */
  public static final int MAX_CELLS = 4_000_000;

  private final String id;
  private final String title;
  private final int width;
  private final int height;
  private final byte[] collisionFlags;
  private final int[] occupants;
  private final MapFlags flags;
  // OS_DOOR / OS_GATEOBJECT equivalents: anchors indexed by their own cell (Envir.pas GetDoor
  // only matches the exact anchor) and gate routes indexed by their source cell.
  private final Map<Position, DoorInfo> doorAnchors = new LinkedHashMap<>();
  private final List<DoorInfo> doorList = new ArrayList<>();
  private final Map<Position, TeleportRoute> gates = new HashMap<>();
  // g_StartPoint (LocalDB.pas:LoadStartPoint): the town squares players spawn on. Each one
  // radiates a safe zone of nSafeZoneSize cells, checked by TBaseObject.InSafeZone.
  private final List<StartPoint> startPoints = new ArrayList<>();

  private GameMap(String id, String title, int width, int height, byte[] collisionFlags, MapFlags flags) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(flags, "flags");
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
    this.flags = flags;
  }

  private GameMap(String id, String title, int width, int height, byte[] collisionFlags) {
    this(id, title, width, height, collisionFlags, MapFlags.DEFAULT);
  }

  public static GameMap empty(String id, String title, int width, int height) {
    return new GameMap(id, title, width, height, new byte[Math.multiplyExact(width, height)], MapFlags.DEFAULT);
  }

  public static GameMap empty(String id, String title, int width, int height, MapFlags flags) {
    return new GameMap(id, title, width, height, new byte[Math.multiplyExact(width, height)], flags);
  }

  /** Convenient constructor for tests and generated PoC maps. */
  public static GameMap withBlockedCells(
      String id, String title, int width, int height, Collection<Position> blockedCells) {
    return withBlockedCells(id, title, width, height, blockedCells, MapFlags.DEFAULT);
  }

  public static GameMap withBlockedCells(
      String id, String title, int width, int height, Collection<Position> blockedCells, MapFlags flags) {
    byte[] collision = new byte[Math.multiplyExact(width, height)];
    for (Position position : blockedCells) {
      if (position.x() < 0 || position.x() >= width || position.y() < 0 || position.y() >= height)
        throw new IllegalArgumentException("blocked cell lies outside map: " + position);
      collision[position.x() * height + position.y()] = BACKGROUND_BLOCKED;
    }
    return new GameMap(id, title, width, height, collision, flags);
  }

  static GameMap fromCollisionFlags(String id, String title, int width, int height, byte[] flags) {
    return new GameMap(id, title, width, height, flags, MapFlags.DEFAULT);
  }

  static GameMap fromCollisionFlags(String id, String title, int width, int height, byte[] flags, MapFlags mapFlags) {
    return new GameMap(id, title, width, height, flags, mapFlags);
  }

  /** Copy constructor that preserves decoded doors and installed gates but not occupants. */
  private GameMap(GameMap source, String title, MapFlags flags) {
    this(source.id, title, source.width, source.height, source.collisionFlags, flags);
    doorAnchors.putAll(source.doorAnchors);
    doorList.addAll(source.doorList);
    gates.putAll(source.gates);
    startPoints.addAll(source.startPoints);
  }

  /**
   * Returns a copy of this map carrying the MapInfo.txt description. {@code SM_MAPDESCRIPTION}
   * sends {@code m_PEnvir.sMapDesc} (the MapInfo description, ObjBase.pas:SendMapDescription),
   * not the {@code .map} header title, so the bootstrap swaps it in right after loading —
   * this method must not be used once any object has entered the map.
   */
  public GameMap withTitle(String newTitle) {
    if (newTitle == null || newTitle.isBlank()) return this;
    for (int occupant : occupants) {
      if (occupant != 0) throw new IllegalStateException("cannot retitle a map with occupants on it");
    }
    return new GameMap(this, newTitle, this.flags);
  }

  public GameMap withFlags(MapFlags newFlags) {
    Objects.requireNonNull(newFlags, "newFlags");
    for (int occupant : occupants) {
      if (occupant != 0) throw new IllegalStateException("cannot reflag a map with occupants on it");
    }
    return new GameMap(this, this.title, newFlags);
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

  public MapFlags flags() {
    return flags;
  }

  public boolean isSafeZone() {
    return flags.safeZone();
  }

  /**
   * {@code TBaseObject.InSafeZone} (ObjBase.pas:21527): true when the whole map carries
   * {@code boSAFE}, or when the cell lies within {@code nSafeZoneSize} of one of this map's
   * start points. Delphi compares each axis separately, so the zone is a square.
   */
  public boolean isSafeZone(Position position) {
    Objects.requireNonNull(position, "position");
    if (flags.safeZone()) return true;
    for (StartPoint startPoint : startPoints) {
      if (Math.abs(position.x() - startPoint.position().x()) <= startPoint.safeZoneSize()
          && Math.abs(position.y() - startPoint.position().y()) <= startPoint.safeZoneSize()) {
        return true;
      }
    }
    return false;
  }

  /** Registers a {@code StartPoint.txt} entry on this map. */
  public void addStartPoint(StartPoint startPoint) {
    Objects.requireNonNull(startPoint, "startPoint");
    startPoints.add(startPoint);
  }

  public List<StartPoint> startPoints() {
    return List.copyOf(startPoints);
  }

  public boolean isDarkness() {
    return flags.darkness();
  }

  public boolean isDayLight() {
    return flags.dayLight();
  }

  public boolean isFightZone() {
    return flags.isFightZone();
  }

  public boolean isNoChat() {
    return flags.noChat();
  }

  public boolean isQuiz() {
    return flags.quiz();
  }

  public String noReconnectMap() {
    return flags.noReconnectMap();
  }

  public MapInfo info() {
    return new MapInfo(id, title, width, height, flags);
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

  /** Registers one door anchor; anchors later registered share status per the Delphi rule. */
  public void addDoor(DoorInfo door) {
    Objects.requireNonNull(door, "door");
    if (!contains(door.anchor()))
      throw new IllegalArgumentException("door anchor lies outside map: " + door.anchor());
    if (doorAnchors.putIfAbsent(door.anchor(), door) == null) doorList.add(door);
  }

  /** Envir.pas GetDoor: exact anchor-cell match only, no area search. */
  public DoorInfo doorAt(Position cell) {
    return doorAnchors.get(Objects.requireNonNull(cell, "cell"));
  }

  /** Decoded door anchors in column-major load order, as built by {@code LoadMapData}. */
  public List<DoorInfo> doors() {
    return List.copyOf(doorList);
  }

  public boolean hasDoors() {
    return !doorList.isEmpty();
  }

  /**
   * Envir.pas {@code ArroundDoorOpened}: true when no closed door has an anchor inside the
   * +/-1 Chebyshev neighbourhood. Gates silently refuse to fire while a door next to them
   * is closed (TBaseObject.Walk).
   */
  public boolean aroundDoorOpened(Position cell) {
    for (DoorInfo door : doorList) {
      if (Math.abs(door.anchor().x() - cell.x()) <= 1
          && Math.abs(door.anchor().y() - cell.y()) <= 1
          && !door.status().opened()) {
        return false;
      }
    }
    return true;
  }

  /** Installs a map connection point (OS_GATEOBJECT), sourced from MapInfo.txt routes. */
  public void addGate(TeleportRoute route) {
    Objects.requireNonNull(route, "route");
    if (!route.sourceMapId().equals(id))
      throw new IllegalArgumentException("route belongs to a different source map: " + route);
    if (!contains(route.source()))
      throw new IllegalArgumentException("gate source lies outside map: " + route.source());
    gates.put(route.source(), route);
  }

  /** Returns the gate object standing on {@code cell}, or null. */
  public TeleportRoute routeAt(Position cell) {
    return gates.get(Objects.requireNonNull(cell, "cell"));
  }

  /** Number of installed gate cells, for load-time diagnostics. */
  public int gateCount() {
    return gates.size();
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

  public record MapInfo(String id, String title, int width, int height, MapFlags flags) {
    public MapInfo {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(flags, "flags");
      if (width <= 1 || height <= 1) throw new IllegalArgumentException("invalid map dimensions");
    }

    public MapInfo(String id, String title, int width, int height) {
      this(id, title, width, height, MapFlags.DEFAULT);
    }
  }
}
