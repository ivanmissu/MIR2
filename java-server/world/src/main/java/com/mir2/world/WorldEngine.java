package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deterministic single-owner-thread game world.
 *
 * <p>Network threads only enqueue commands. A tick drains them in FIFO order, mutates maps and
 * objects, and emits immutable events. This mirrors the non-reentrant Delphi {@code TUserEngine}
 * loop without sharing mutable world state with socket threads.
 */
public final class WorldEngine implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(WorldEngine.class.getName());

  public record Config(Duration tickInterval, int viewRange, int maxCommandsPerTick) {
    public Config {
      Objects.requireNonNull(tickInterval, "tickInterval");
      if (tickInterval.isZero() || tickInterval.isNegative() || tickInterval.toMillis() == 0)
        throw new IllegalArgumentException("tick interval must be at least one millisecond");
      if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
      if (maxCommandsPerTick < 1) throw new IllegalArgumentException("command limit must be positive");
    }

    public static Config defaults() {
      // TPlayObject.m_nViewRange is 12 in ObjBase.pas.
      return new Config(Duration.ofMillis(50), 12, 10_000);
    }
  }

  private final Config config;
  private final Map<String, GameMap> maps = new LinkedHashMap<>();
  private final Map<Integer, Player> players = new HashMap<>();
  private final Map<String, Integer> playersByName = new HashMap<>();
  private final ConcurrentLinkedQueue<Pending<?>> commands = new ConcurrentLinkedQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("mir2-world").factory());
  private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong tickCount = new AtomicLong();
  private int nextObjectId = 1;

  public WorldEngine(Config config, Collection<GameMap> maps) {
    this.config = Objects.requireNonNull(config);
    if (maps.isEmpty()) throw new IllegalArgumentException("at least one map is required");
    for (GameMap map : maps) {
      Objects.requireNonNull(map, "map");
      if (this.maps.putIfAbsent(map.id(), map) != null)
        throw new IllegalArgumentException("duplicate map id: " + map.id());
    }
  }

  public WorldEngine(Collection<GameMap> maps) {
    this(Config.defaults(), maps);
  }

  /** Starts fixed-rate ticks. Callers may instead use {@link #tickOnce()} in deterministic tests. */
  public void start() {
    if (closed.get()) throw new IllegalStateException("world engine is closed");
    if (!started.compareAndSet(false, true)) throw new IllegalStateException("world engine already started");
    long interval = config.tickInterval().toMillis();
    scheduler.scheduleAtFixedRate(this::scheduledTick, 0, interval, TimeUnit.MILLISECONDS);
  }

  public boolean isRunning() {
    return started.get() && !closed.get() && !scheduler.isShutdown();
  }

  public long tickCount() {
    return tickCount.get();
  }

  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      String name,
      String mapId,
      Position position,
      Direction direction,
      WorldEventSink sink) {
    return enterPlayer(name, mapId, position, direction, 0, 0, sink);
  }

  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> enter(name, mapId, position, direction, feature, status, sink));
  }

  /** Enters at the requested spawn or the nearest currently available cell. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(preferredPosition, "preferredPosition");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      return enter(name, mapId, nearestAvailable(map, preferredPosition), direction, feature, status, sink);
    });
  }

  public CompletableFuture<MoveResult> move(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(movement, "movement");
    return submit(() -> movePlayer(playerId, target, direction, movement));
  }

  /** The claimed position is checked to reject stale or accelerated client actions. */
  public CompletableFuture<Boolean> turn(int playerId, Position claimedPosition, Direction direction) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> turnPlayer(playerId, claimedPosition, direction));
  }

  public CompletableFuture<Void> leavePlayer(int playerId) {
    return submit(() -> {
      leave(playerId);
      return null;
    });
  }

  public CompletableFuture<WorldObjectSnapshot> snapshot(int playerId) {
    return submit(() -> requirePlayer(playerId).snapshot());
  }

  public CompletableFuture<Integer> onlinePlayers() {
    return submit(players::size);
  }

  /**
   * Executes one world tick on the current thread. The first caller becomes the permanent owner;
   * concurrent or cross-thread mutation is rejected.
   */
  public void tickOnce() {
    if (closed.get()) throw new IllegalStateException("world engine is closed");
    claimOwnership();
    for (int processed = 0; processed < config.maxCommandsPerTick(); processed++) {
      Pending<?> pending = commands.poll();
      if (pending == null) break;
      pending.execute();
    }
    tickCount.incrementAndGet();
  }

  private void scheduledTick() {
    try {
      tickOnce();
    } catch (Throwable error) {
      // Scheduled executors suppress all future invocations if a task lets an exception escape.
      LOG.log(Level.SEVERE, "world tick failed", error);
    }
  }

  private WorldObjectSnapshot enter(
      String name, String mapId, Position position, Direction direction,
      int feature, int status, WorldEventSink sink) {
    if (name.isBlank()) throw new IllegalArgumentException("player name must not be blank");
    if (playersByName.containsKey(name)) throw new IllegalStateException("player is already online: " + name);
    GameMap map = requireMap(mapId);
    if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);

    int id = allocateObjectId();
    List<Integer> visibleIds = visibleIds(map, position, 0);
    Player player = new Player(id, name, map, position, direction, feature, status, sink);
    map.place(id, position);
    players.put(id, player);
    playersByName.put(name, id);

    List<WorldObjectSnapshot> visible = visibleIds.stream()
        .map(players::get)
        .filter(Objects::nonNull)
        .map(Player::snapshot)
        .toList();
    emit(player, new WorldEvent.MapEntered(player.snapshot(), map.info(), visible));
    WorldEvent appeared = new WorldEvent.ObjectAppeared(player.snapshot());
    for (int viewerId : visibleIds) emit(players.get(viewerId), appeared);
    return player.snapshot();
  }

  private MoveResult movePlayer(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Player player = requirePlayer(playerId);
    Position source = player.position;
    Position expected = source.translate(direction, movement.steps());
    if (!expected.equals(target))
      return rejectMove(player, target, WorldEvent.MoveRejection.INVALID_TARGET);

    for (int step = 1; step <= movement.steps(); step++) {
      Position candidate = source.translate(direction, step);
      if (!player.map.contains(candidate))
        return rejectMove(player, target, WorldEvent.MoveRejection.OUT_OF_BOUNDS);
      if (!player.map.isTerrainWalkable(candidate))
        return rejectMove(player, target, WorldEvent.MoveRejection.BLOCKED_TERRAIN);
      if (player.map.objectAt(candidate) != 0)
        return rejectMove(player, target, WorldEvent.MoveRejection.OCCUPIED);
    }

    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(player.map, source, player.id));
    player.map.move(player.id, source, target);
    player.position = target;
    player.direction = direction;
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(player.map, target, player.id));
    WorldObjectSnapshot movedPlayer = player.snapshot();

    emit(player, new WorldEvent.MoveAccepted(movedPlayer, source, movement));
    emitOwnVisibilityChanges(player, visibleBefore, visibleAfter);
    emitMovementToObservers(movedPlayer, source, movement, visibleBefore, visibleAfter);
    return MoveResult.accepted(movedPlayer);
  }

  private boolean turnPlayer(int playerId, Position claimedPosition, Direction direction) {
    Player player = requirePlayer(playerId);
    if (!player.position.equals(claimedPosition)) {
      emit(player, new WorldEvent.TurnRejected(player.id, WorldEvent.TurnRejection.POSITION_MISMATCH));
      return false;
    }
    player.direction = direction;
    WorldObjectSnapshot snapshot = player.snapshot();
    emit(player, new WorldEvent.TurnAccepted(snapshot));
    WorldEvent turned = new WorldEvent.ObjectTurned(snapshot);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) emit(players.get(viewerId), turned);
    return true;
  }

  private void leave(int playerId) {
    Player player = requirePlayer(playerId);
    List<Integer> visibleIds = visibleIds(player.map, player.position, player.id);
    player.map.remove(player.id, player.position);
    players.remove(player.id);
    playersByName.remove(player.name);
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(player.id);
    for (int viewerId : visibleIds) emit(players.get(viewerId), disappeared);
    emit(player, new WorldEvent.MapLeft(player.id));
  }

  private MoveResult rejectMove(
      Player player, Position target, WorldEvent.MoveRejection reason) {
    emit(player, new WorldEvent.MoveRejected(player.id, target, reason));
    return MoveResult.rejected(player.snapshot(), reason);
  }

  private void emitOwnVisibilityChanges(
      Player movingPlayer, Set<Integer> visibleBefore, Set<Integer> visibleAfter) {
    for (int oldObject : difference(visibleBefore, visibleAfter))
      emit(movingPlayer, new WorldEvent.ObjectDisappeared(oldObject));
    for (int newObject : difference(visibleAfter, visibleBefore))
      emit(movingPlayer, new WorldEvent.ObjectAppeared(requirePlayer(newObject).snapshot()));
  }

  private void emitMovementToObservers(
      WorldObjectSnapshot movedPlayer,
      Position source,
      MovementKind movement,
      Set<Integer> visibleBefore,
      Set<Integer> visibleAfter) {
    Set<Integer> observers = new LinkedHashSet<>(visibleBefore);
    observers.addAll(visibleAfter);
    for (int observerId : observers) {
      Player observer = players.get(observerId);
      if (visibleBefore.contains(observerId) && visibleAfter.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectMoved(movedPlayer, source, movement));
      } else if (visibleBefore.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectDisappeared(movedPlayer.id()));
      } else {
        emit(observer, new WorldEvent.ObjectAppeared(movedPlayer));
      }
    }
  }

  private static Position nearestAvailable(GameMap map, Position preferred) {
    if (!map.contains(preferred)) throw new IllegalArgumentException("spawn lies outside map: " + preferred);
    if (map.canWalk(preferred)) return preferred;
    int maxRadius = Math.max(map.width(), map.height());
    for (int radius = 1; radius < maxRadius; radius++) {
      int lowX = Math.max(0, preferred.x() - radius);
      int highX = Math.min(map.width() - 1, preferred.x() + radius);
      int lowY = Math.max(0, preferred.y() - radius);
      int highY = Math.min(map.height() - 1, preferred.y() + radius);
      for (int x = lowX; x <= highX; x++) {
        for (int y = lowY; y <= highY; y++) {
          if (x != lowX && x != highX && y != lowY && y != highY) continue;
          Position candidate = new Position(x, y);
          if (map.canWalk(candidate)) return candidate;
        }
      }
    }
    throw new IllegalStateException("map has no available spawn cell: " + map.id());
  }

  private List<Integer> visibleIds(GameMap map, Position center, int excludedId) {
    List<Integer> result = new ArrayList<>();
    for (int id : map.objectsInSquare(center, config.viewRange())) {
      if (id != excludedId && players.containsKey(id)) result.add(id);
    }
    return result;
  }

  private static Set<Integer> difference(Set<Integer> left, Set<Integer> right) {
    Set<Integer> result = new LinkedHashSet<>(left);
    result.removeAll(right);
    return result;
  }

  private static void emit(Player player, WorldEvent event) {
    if (player == null) return;
    try {
      player.sink.send(event);
    } catch (RuntimeException error) {
      // A broken socket/session must not abort a world tick or roll back valid state.
      LOG.log(Level.WARNING, "world event sink failed for player " + player.id, error);
    }
  }

  private Player requirePlayer(int id) {
    Player player = players.get(id);
    if (player == null) throw new NoSuchElementException("player is not in the world: " + id);
    return player;
  }

  private GameMap requireMap(String id) {
    GameMap map = maps.get(id);
    if (map == null) throw new NoSuchElementException("unknown map: " + id);
    return map;
  }

  private int allocateObjectId() {
    if (nextObjectId <= 0) throw new IllegalStateException("world object id space exhausted");
    return nextObjectId++;
  }

  private void claimOwnership() {
    Thread current = Thread.currentThread();
    Thread owner = ownerThread.get();
    if (owner == null && ownerThread.compareAndSet(null, current)) return;
    if (ownerThread.get() != current)
      throw new IllegalStateException("world state may only be mutated by its owner thread");
  }

  private <T> CompletableFuture<T> submit(Supplier<T> action) {
    CompletableFuture<T> future = new CompletableFuture<>();
    if (closed.get()) {
      future.completeExceptionally(new IllegalStateException("world engine is closed"));
      return future;
    }
    Pending<T> pending = new Pending<>(action, future);
    commands.add(pending);
    if (closed.get() && commands.remove(pending)) pending.cancel();
    return future;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    scheduler.shutdownNow();
    Pending<?> pending;
    while ((pending = commands.poll()) != null) pending.cancel();
  }

  private record Pending<T>(Supplier<T> action, CompletableFuture<T> future) {
    private Pending {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(future, "future");
    }

    private void execute() {
      try {
        future.complete(action.get());
      } catch (Throwable error) {
        future.completeExceptionally(error);
      }
    }

    private void cancel() {
      future.completeExceptionally(new CancellationException("world engine stopped"));
    }
  }

  private static final class Player {
    private final int id;
    private final String name;
    private final GameMap map;
    private final int feature;
    private final int status;
    private final WorldEventSink sink;
    private Position position;
    private Direction direction;

    private Player(
        int id,
        String name,
        GameMap map,
        Position position,
        Direction direction,
        int feature,
        int status,
        WorldEventSink sink) {
      this.id = id;
      this.name = name;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.feature = feature;
      this.status = status;
      this.sink = sink;
    }

    private WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, name, WorldObjectType.PLAYER, map.id(), position, direction, feature, status);
    }
  }
}
