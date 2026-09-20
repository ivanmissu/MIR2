package com.mir2.world;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deterministic single-owner-thread game world.
 *
 * <p>Network threads only enqueue commands. A tick drains them in FIFO order, runs monster AI,
 * mutates maps and objects, and emits immutable events. This mirrors the non-reentrant Delphi
 * {@code TUserEngine} loop without sharing mutable world state with socket threads.
 */
public final class WorldEngine implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(WorldEngine.class.getName());

  public record Config(
      Duration tickInterval,
      int viewRange,
      int maxCommandsPerTick,
      long hitIntervalMillis,
      long corpseLingerMillis,
      long itemLingerMillis,
      long regenIntervalMillis,
      long saveIntervalMillis) {

    public Config {
      Objects.requireNonNull(tickInterval, "tickInterval");
      if (tickInterval.isZero() || tickInterval.isNegative() || tickInterval.toMillis() == 0)
        throw new IllegalArgumentException("tick interval must be at least one millisecond");
      if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
      if (maxCommandsPerTick < 1) throw new IllegalArgumentException("command limit must be positive");
      if (hitIntervalMillis < 0) throw new IllegalArgumentException("hit interval must not be negative");
      if (corpseLingerMillis < 0 || itemLingerMillis < 0)
        throw new IllegalArgumentException("linger durations must not be negative");
      if (regenIntervalMillis < 1)
        throw new IllegalArgumentException("regen interval must be at least one millisecond");
      if (saveIntervalMillis < 1)
        throw new IllegalArgumentException("save interval must be at least one millisecond");
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis) {
      // g_Config.dwRegenMonstersTime defaults to 200ms and dwSaveHumanRcdTime to 10 minutes
      // (M2Share.pas defaults).
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, 200, 10 * 60 * 1000);
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick) {
      // TPlayObject.m_dwHitIntervalTime defaults to 900ms; corpses/items use the Delphi defaults.
      this(tickInterval, viewRange, maxCommandsPerTick, 900, 5_000, 180_000);
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
  private final Map<Integer, Monster> monsters = new LinkedHashMap<>();
  private final List<Spawner> spawners = new ArrayList<>();
  private final Map<Integer, GroundItem> groundItems = new LinkedHashMap<>();
  private final Map<Integer, Long> itemDropTimes = new HashMap<>();
  private final ConcurrentLinkedQueue<Pending<?>> commands = new ConcurrentLinkedQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("mir2-world").factory());
  private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong tickCount = new AtomicLong();
  private final LongSupplier clock;
  private final Random random;
  private final PlayerStateStore playerStateStore;
  private final ItemDatabase itemDatabase;
  private final IntSupplier hourSupplier;
  private int gameTime;
  private int nextObjectId = 1;
  private int nextItemMakeIndex = 1;

  public WorldEngine(Config config, Collection<GameMap> maps) {
    this(config, maps, System::currentTimeMillis, new Random(), PlayerStateStore.none());
  }

  public WorldEngine(Config config, Collection<GameMap> maps, PlayerStateStore playerStateStore) {
    this(config, maps, System::currentTimeMillis, new Random(), playerStateStore, ItemDatabase.empty());
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, System::currentTimeMillis, new Random(), playerStateStore, itemDatabase);
  }

  public WorldEngine(Collection<GameMap> maps) {
    this(Config.defaults(), maps);
  }

  /** Deterministic constructor: tests inject a virtual clock and a seeded damage generator. */
  public WorldEngine(Config config, Collection<GameMap> maps, LongSupplier clock, Random random) {
    this(config, maps, clock, random, PlayerStateStore.none(), ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore) {
    this(config, maps, clock, random, playerStateStore, ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port and the standard-item catalog. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, clock, random, playerStateStore, itemDatabase, () -> LocalTime.now().getHour());
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      IntSupplier hourSupplier) {
    this.config = Objects.requireNonNull(config);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
    this.playerStateStore = Objects.requireNonNull(playerStateStore, "playerStateStore");
    this.itemDatabase = Objects.requireNonNull(itemDatabase, "itemDatabase");
    this.hourSupplier = Objects.requireNonNull(hourSupplier, "hourSupplier");
    this.gameTime = gameTimeFromHour(hourSupplier.getAsInt());
    this.nextItemMakeIndex = seedMakeIndex(playerStateStore.itemMakeIndexHighWater());
    if (maps.isEmpty()) throw new IllegalArgumentException("at least one map is required");
    for (GameMap map : maps) {
      Objects.requireNonNull(map, "map");
      if (this.maps.putIfAbsent(map.id(), map) != null)
        throw new IllegalArgumentException("duplicate map id: " + map.id());
    }
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

  /** Transient compatibility overload used by isolated world tests. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    return enterPlayer(transientCharacterId(name), name, mapId, position, direction, feature, status, sink);
  }

  /** Enters a durable character and restores its ability and backpack before MapEntered is emitted. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      UUID characterId,
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> enter(
        characterId, name, mapId, position, direction, feature, status, sink));
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
    return enterPlayerNear(transientCharacterId(name), name, mapId, preferredPosition,
        direction, feature, status, sink);
  }

  /** Durable-character variant of {@link #enterPlayerNear(String, String, Position, Direction, int, int, WorldEventSink)}. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      UUID characterId,
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(preferredPosition, "preferredPosition");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      if (map.flags().noReconnect() && !map.flags().noReconnectMap().isBlank()) {
        String redirectId = map.flags().noReconnectMap();
        if (maps.containsKey(redirectId)) {
          map = maps.get(redirectId);
        }
      }
      return enter(characterId, name, map.id(), nearestAvailable(map, preferredPosition),
          direction, feature, status, sink);
    });
  }

  public CompletableFuture<WorldObjectSnapshot> spawnMonster(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> spawn(template, mapId, position, direction));
  }

  /**
   * Registers a MonGen auto-respawn definition on {@code mapId}. The world evaluates spawners
   * every {@code regenIntervalMillis} (dwRegenMonstersTime, 200ms default) and replenishes
   * losses when their respawn window has elapsed, mirroring {@code TUserEngine.RegenMonsters}.
   */
  public CompletableFuture<Void> addSpawner(MonsterTemplate template, String mapId,
      Position center, int radius, int count, Duration respawnInterval) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(center, "center");
    Objects.requireNonNull(respawnInterval, "respawnInterval");
    if (count <= 0) throw new IllegalArgumentException("spawner count must be positive");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      spawners.add(new Spawner(template, map, center, radius, count, respawnInterval.toMillis()));
      return null;
    });
  }

  public CompletableFuture<MoveResult> move(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(movement, "movement");
    return submit(() -> movePlayer(playerId, target, direction, movement));
  }

  public CompletableFuture<Boolean> turn(int playerId, Position claimedPosition, Direction direction) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> turnPlayer(playerId, claimedPosition, direction));
  }

  /** Melee attack on the single cell in front of the player, as in {@code TBaseObject.AttackDir}. */
  public CompletableFuture<AttackResult> attack(
      int playerId, Position claimedPosition, Direction direction, AttackKind attack) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(attack, "attack");
    return submit(() -> attackWith(playerId, claimedPosition, direction, attack));
  }

  public CompletableFuture<Boolean> pickUp(int playerId, Position claimedPosition) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    return submit(() -> pickUpItem(playerId, claimedPosition));
  }

  /**
   * Opens the door at {@code claimed} if its anchor matches {@code claimed} and it is currently
   * closed, broadcasting {@code SM_OPENDOOR_OK} to observers inside +/-12 cells (UsrEngn.OpenDoor).
   * Like Delphi, the server returns no direct acknowledgment packet to the opener.
   */
  public CompletableFuture<Boolean> openDoor(int playerId, Position claimed) {
    Objects.requireNonNull(claimed, "claimed");
    return submit(() -> openDoorAt(playerId, claimed));
  }

  /**
   * Installs an inter-map connection point, sourced from a {@code MapInfo.txt} route line.
   * Silently ignored when either map is not registered in this world engine instance,
   * mirroring {@code TMapManager.AddMapRoute}.
   */
  public CompletableFuture<Boolean> addRoute(TeleportRoute route) {
    Objects.requireNonNull(route, "route");
    return submit(() -> {
      GameMap source = maps.get(route.sourceMapId());
      GameMap destination = maps.get(route.destinationMapId());
      if (source == null || destination == null) return false;
      source.addGate(route);
      return true;
    });
  }

  public CompletableFuture<Void> leavePlayer(int playerId) {
    return submit(() -> {
      leave(playerId);
      return null;
    });
  }

  public CompletableFuture<WorldObjectSnapshot> snapshot(int objectId) {
    return submit(() -> requireObject(objectId).snapshot());
  }

  /** Returns the private durable state of an online player without exposing it to nearby observers. */
  public CompletableFuture<PlayerState> playerState(int playerId) {
    return submit(() -> requirePlayer(playerId).state());
  }

  public CompletableFuture<Integer> onlinePlayers() {
    return submit(players::size);
  }

  public CompletableFuture<Integer> liveMonsters() {
    return submit(() -> (int) monsters.values().stream().filter(monster -> monster.ability.alive()).count());
  }

  public CompletableFuture<List<GroundItem>> itemsAt(String mapId, Position position) {
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    return submit(() -> itemsOn(mapId, position));
  }

  /**
   * Speaks a message from the specified player, mirroring {@code TPlayObject.ProcessUserLineMsg}.
   * Supports normal chat (12-cell sight broadcast), whisper (/target msg), shout (!msg),
   * and system queries (@who / /who).
   */
  public CompletableFuture<Boolean> say(int playerId, String message) {
    Objects.requireNonNull(message, "message");
    return submit(() -> processSay(playerId, message));
  }

  public CompletableFuture<Boolean> say(String characterName, String message) {
    Objects.requireNonNull(characterName, "characterName");
    Objects.requireNonNull(message, "message");
    return submit(() -> {
      Integer playerId = playersByName.get(characterName);
      if (playerId == null) return false;
      return processSay(playerId, message);
    });
  }

  public int gameTime() {
    return gameTime;
  }

  public int dayBright(GameMap map) {
    return calculateDayBright(map != null ? map.flags() : MapFlags.DEFAULT, this.gameTime);
  }

  public CompletableFuture<Void> setGameTime(int newGameTime) {
    return submit(() -> {
      updateGameTime(newGameTime);
      return null;
    });
  }

  /**
   * Maps an hour (0..23) to Delphi {@code g_nGameTime} (FrnEngn.pas:GetGameTime):
   * <ul>
   *   <li>5..10, 16..22: 1 (daytime)</li>
   *   <li>11, 23: 2 (twilight)</li>
   *   <li>4, 15: 0 (dawn / transition)</li>
   *   <li>0..3, 12..14: 3 (night)</li>
   * </ul>
   */
  public static int gameTimeFromHour(int hour) {
    int h = Math.floorMod(hour, 24);
    return switch (h) {
      case 5, 6, 7, 8, 9, 10, 16, 17, 18, 19, 20, 21, 22 -> 1;
      case 11, 23 -> 2;
      case 4, 15 -> 0;
      default -> 3; // 0, 1, 2, 3, 12, 13, 14
    };
  }

  /**
   * Delphi {@code TPlayObject.DayBright}:
   * 0 = Bright / Day, 1 = Dark / Night, 2 = Twilight.
   * Darkness map flag forces 1; DayLight map flag forces 0.
   */
  public static int calculateDayBright(MapFlags flags, int gameTime) {
    int bright;
    if (flags != null && flags.darkness()) {
      bright = 1;
    } else if (gameTime == 1) {
      bright = 0;
    } else if (gameTime == 3) {
      bright = 1;
    } else {
      bright = 2;
    }
    if (flags != null && flags.dayLight()) {
      bright = 0;
    }
    return bright;
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
    regenSpawners();
    updateMonsters();
    expireGroundItems();
    closeDoorsPeriodically();
    savePlayersPeriodically();
    checkGameTime();
    tickCount.incrementAndGet();
  }

  private void checkGameTime() {
    int current = gameTimeFromHour(hourSupplier.getAsInt());
    if (current != this.gameTime) {
      updateGameTime(current);
    }
  }

  private void updateGameTime(int newGameTime) {
    if (this.gameTime != newGameTime) {
      this.gameTime = newGameTime;
      for (Player player : players.values()) {
        int bright = dayBright(player.map);
        emit(player, new WorldEvent.DayChanging(player.id, this.gameTime, bright));
      }
    }
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
      UUID characterId, String name, String mapId, Position position, Direction direction,
      int feature, int status, WorldEventSink sink) {
    if (name.isBlank()) throw new IllegalArgumentException("player name must not be blank");
    if (playersByName.containsKey(name)) throw new IllegalStateException("player is already online: " + name);
    GameMap map = requireMap(mapId);
    if (map.flags().noReconnect() && !map.flags().noReconnectMap().isBlank()) {
      String redirectId = map.flags().noReconnectMap();
      if (maps.containsKey(redirectId)) {
        map = maps.get(redirectId);
        if (!map.canWalk(position)) {
          position = nearestAvailable(map, new Position(map.width() / 2, map.height() / 2));
        }
      }
    }
    if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);

    PlayerState restored = playerStateStore.load(characterId)
        .orElseGet(() -> PlayerState.initial(characterId));
    // W03 rows predate make indexes; stabilise them before the player becomes visible.
    restored = withStableMakeIndexes(restored);
    // Materialise defaults for characters created by an older schema before exposing the player.
    playerStateStore.save(restored);

    int id = allocateObjectId();
    List<Integer> visibleIds = visibleIds(map, position, 0);
    Player player = new Player(id, characterId, name, map, position, direction, feature, status,
        restored.ability(), restored.backpack(), sink);
    // The enter itself just saved; the periodic pass starts counting from now.
    player.lastSavedAt = clock.getAsLong();
    map.place(id, position);
    players.put(id, player);
    playersByName.put(name, id);

    List<WorldObjectSnapshot> visible = visibleIds.stream()
        .map(this::findObject)
        .filter(Objects::nonNull)
        .map(WorldObject::snapshot)
        .toList();
    emit(player, new WorldEvent.MapEntered(
        player.snapshot(), map.info(), visible, visibleItems(map, position), dayBright(map)));
    WorldEvent appeared = new WorldEvent.ObjectAppeared(player.snapshot());
    for (int viewerId : visibleIds) emit(players.get(viewerId), appeared);
    return player.snapshot();
  }

  private WorldObjectSnapshot spawn(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    GameMap map = requireMap(mapId);
    if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);
    int id = allocateObjectId();
    Monster monster = new Monster(id, template, map, position, direction, clock.getAsLong());
    map.place(id, position);
    monsters.put(id, monster);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(monster.snapshot());
    for (int viewerId : visibleIds(map, position, id)) emit(players.get(viewerId), appeared);
    return monster.snapshot();
  }

  private MoveResult movePlayer(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) return rejectMove(player, target, WorldEvent.MoveRejection.ACTOR_DEAD);
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

    // TBaseObject.Walk fires map gates after the move lands; RunTo ends in the same
    // Walk(RM_RUN) call, so walk and run trigger connection points identically.
    TeleportRoute gate = player.map.routeAt(target);
    if (gate != null && player.map.aroundDoorOpened(target)) {
      // EnterAnotherMap refuses an unwalkable destination and WalkTo rolls the whole move
      // back instead of leaving the player standing on the gate cell.
      GameMap destination = maps.get(gate.destinationMapId());
      if (destination == null || !destination.canWalk(gate.destination())) {
        return rejectMove(player, target, WorldEvent.MoveRejection.GATE_TARGET_UNPASSABLE);
      }
      return teleportPlayer(player, source, direction, movement, gate, destination);
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
    emitItemVisibilityChanges(player, source, target);
    return MoveResult.accepted(movedPlayer);
  }

  private boolean turnPlayer(int playerId, Position claimedPosition, Direction direction) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.TurnRejected(player.id, WorldEvent.TurnRejection.ACTOR_DEAD));
      return false;
    }
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

  private AttackResult attackWith(
      int playerId, Position claimedPosition, Direction direction, AttackKind attack) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) return rejectAttack(player, WorldEvent.AttackRejection.ACTOR_DEAD);
    if (!player.position.equals(claimedPosition))
      return rejectAttack(player, WorldEvent.AttackRejection.POSITION_MISMATCH);
    long now = clock.getAsLong();
    if (now - player.lastAttackAt < config.hitIntervalMillis())
      return rejectAttack(player, WorldEvent.AttackRejection.TOO_FAST);

    player.lastAttackAt = now;
    player.direction = direction;
    WorldObjectSnapshot attacker = player.snapshot();
    emit(player, new WorldEvent.AttackAccepted(attacker, attack));
    WorldEvent swing = new WorldEvent.ObjectAttacked(attacker, attack);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) emit(players.get(viewerId), swing);

    Position front = player.position.translate(direction, 1);
    WorldObject target = objectAt(player.map, front);
    if (target == null || !target.ability().alive()) return AttackResult.missed(attacker);

    int damage = rollDamage(player.ability, target.ability());
    applyDamage(target, player, damage);
    return new AttackResult(true, attacker, target.snapshot(), damage);
  }

  private boolean pickUpItem(int playerId, Position claimedPosition) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.ACTOR_DEAD));
      return false;
    }
    if (!player.position.equals(claimedPosition)) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.NO_ITEM));
      return false;
    }
    GroundItem item = newestItemOn(player.map.id(), player.position);
    if (item == null) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.NO_ITEM));
      return false;
    }
    if (player.backpack.size() >= PlayerState.MAX_BACKPACK_ITEMS) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.BACKPACK_FULL));
      return false;
    }

    BackpackItem backpackItem = BackpackItem.of(
        itemDatabase.find(item.name()).orElseGet(() -> StdItem.placeholder(item.name(), item.looks())),
        allocateMakeIndex());
    player.backpack.add(backpackItem);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.removeLast();
      throw failure;
    }
    groundItems.remove(item.id());
    itemDropTimes.remove(item.id());
    emit(player, new WorldEvent.ItemPickedUp(player.id, item, backpackItem));
    WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
    for (int viewerId : visibleIds(player.map, item.position(), 0)) emit(players.get(viewerId), hidden);
    return true;
  }

  private boolean openDoorAt(int playerId, Position claimed) {
    Player player = requirePlayer(playerId);
    // TPlayObject.ClientOpenDoor checks only the door record itself — castle doors aside, the
    // Delphi handler does not gate on zone or alive state. The bo01 (castle-taken) flag has no
    // castle subsystem behind it yet, so it stays false and every found door may open.
    DoorInfo door = player.map.doorAt(claimed);
    if (door == null || door.status().opened()) return false;
    door.status().open(clock.getAsLong());
    WorldEvent opened = new WorldEvent.DoorOpened(player.map.id(), door.anchor());
    for (int viewerId : playersInSquare(player.map, claimed)) emit(players.get(viewerId), opened);
    return true;
  }

  /** Players (the only door-status recipients) inside the +/-12 client square around center. */
  private List<Integer> playersInSquare(GameMap map, Position center) {
    List<Integer> result = new ArrayList<>();
    for (int objectId : map.objectsInSquare(center, config.viewRange())) {
      if (players.containsKey(objectId)) result.add(objectId);
    }
    return result;
  }

  /**
   * {@code TBaseObject.EnterAnotherMap}: switch the player to the gate's destination map. The
   * preconditions were verified by the caller (walk landed on the gate, no closed door nearby,
   * destination cell walkable), so this implementation cannot fail and never rolls back.
   */
  private MoveResult teleportPlayer(Player player, Position source, Direction direction,
      MovementKind movement, TeleportRoute gate, GameMap destination) {
    GameMap origin = player.map;
    List<Integer> originObservers = visibleIds(origin, source, player.id);
    origin.remove(player.id, source);
    player.map = destination;
    player.position = gate.destination();
    player.direction = direction;
    destination.place(player.id, gate.destination());
    WorldObjectSnapshot snapshot = player.snapshot();

    emit(player, new WorldEvent.MoveAccepted(snapshot, source, movement));
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(player.id);
    for (int viewerId : originObservers) emit(players.get(viewerId), disappeared);
    // SM_CLEAROBJECTS + SM_CHANGEMAP order is fixed: the client drops its scene first and
    // rebuilds it from the events that follow (appearances, items).
    emit(player, new WorldEvent.PlayerMapChanged(snapshot, destination.info(), dayBright(destination)));
    List<Integer> destinationObservers = visibleIds(destination, player.position, player.id);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(snapshot);
    for (int viewerId : destinationObservers) emit(players.get(viewerId), appeared);
    for (int objectId : destinationObservers) {
      WorldObject other = findObject(objectId);
      if (other != null) emit(player, new WorldEvent.ObjectAppeared(other.snapshot()));
    }
    for (GroundItem item : visibleItems(destination, player.position)) {
      emit(player, new WorldEvent.ItemAppeared(item));
    }
    return MoveResult.accepted(snapshot);
  }

  private boolean processSay(int playerId, String rawText) {
    Player speaker = requirePlayer(playerId);
    if (speaker.map.flags().noChat()) {
      emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前地图禁止发言"));
      return false;
    }
    String text = rawText.strip();
    if (text.isEmpty()) return false;

    if (text.startsWith("@")) {
      if (text.equalsIgnoreCase("@who") || text.equalsIgnoreCase("@在线") || text.equalsIgnoreCase("@total")) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前在线玩家: " + players.size() + " 人"));
        return true;
      }
      return true;
    }

    if (text.startsWith("/")) {
      if (text.equalsIgnoreCase("/who") || text.equalsIgnoreCase("/total")) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前在线玩家: " + players.size() + " 人"));
        return true;
      }
      String targetAndMsg = text.substring(1).stripLeading();
      int space = targetAndMsg.indexOf(' ');
      if (space <= 0) return false;
      String targetName = targetAndMsg.substring(0, space).trim();
      String whisperMsg = targetAndMsg.substring(space + 1).trim();
      if (whisperMsg.isEmpty()) return false;
      Integer targetId = playersByName.get(targetName);
      if (targetId != null && players.containsKey(targetId)) {
        Player target = players.get(targetId);
        WorldEvent whisperEvent = new WorldEvent.Whisper(speaker.id, speaker.name, target.id, target.name, whisperMsg);
        emit(target, whisperEvent);
        if (target.id != speaker.id) {
          emit(speaker, whisperEvent);
        }
        return true;
      } else {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, targetName + " 当前不在线或不存在"));
        return false;
      }
    }

    if (text.startsWith("!")) {
      if (speaker.map.flags().quiz()) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前地图禁止大喊"));
        return false;
      }
      String shoutMsg = text.substring(1).trim();
      if (shoutMsg.isEmpty()) return false;
      WorldEvent shoutEvent = new WorldEvent.Shout(speaker.id, speaker.name, shoutMsg);
      for (Player p : players.values()) {
        if (p.map.id().equals(speaker.map.id())) {
          emit(p, shoutEvent);
        }
      }
      return true;
    }

    // Normal chat: broadcast to all observers inside 12-cell square (including speaker)
    WorldEvent chatEvent = new WorldEvent.ChatHeard(speaker.id, speaker.name, text);
    List<Integer> viewers = visibleIds(speaker.map, speaker.position, 0);
    for (int viewerId : viewers) {
      Player viewer = players.get(viewerId);
      if (viewer != null) emit(viewer, chatEvent);
    }
    return true;
  }

  private void leave(int playerId) {
    Player player = requirePlayer(playerId);
    persist(player);
    List<Integer> visibleIds = visibleIds(player.map, player.position, player.id);
    player.map.remove(player.id, player.position);
    players.remove(playerId);
    playersByName.remove(player.name);
    emit(player, new WorldEvent.MapLeft(playerId));
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(playerId);
    for (int viewerId : visibleIds) emit(players.get(viewerId), disappeared);
  }

  private int rollDamage(Ability attacker, Ability defender) {
    int attack = randomBetween(attacker.minDc(), attacker.maxDc());
    int defence = randomBetween(defender.minAc(), defender.maxAc());
    return Math.max(0, attack - defence);
  }

  private int randomBetween(int min, int max) {
    if (min >= max) return min;
    return min + random.nextInt(max - min + 1);
  }

  private void applyDamage(WorldObject victim, WorldObject attacker, int damage) {
    if (damage <= 0) {
      broadcastStruck(victim, attacker.id(), 0);
      return;
    }
    Ability before = victim.ability();
    int nextHp = Math.max(0, before.hp() - damage);
    Ability updated = before.withHp(nextHp);
    victim.setAbility(updated);
    if (victim instanceof Player player) {
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.setAbility(before);
        throw failure;
      }
    }
    broadcastStruck(victim, attacker.id(), damage);
    if (updated.alive()) {
      WorldEvent health = new WorldEvent.HealthChanged(victim.snapshot());
      emitToObserversAndSelf(victim, health);
    } else {
      handleDeath(victim, attacker);
    }
  }

  private void broadcastStruck(WorldObject victim, int attackerId, int damage) {
    WorldEvent struck = new WorldEvent.ObjectStruck(victim.snapshot(), attackerId, damage);
    emitToObserversAndSelf(victim, struck);
  }

  private void emitToObserversAndSelf(WorldObject center, WorldEvent event) {
    if (center instanceof Player p) emit(p, event);
    for (int viewerId : visibleIds(center.map(), center.position(), center.id())) {
      emit(players.get(viewerId), event);
    }
  }

  private void handleDeath(WorldObject victim, WorldObject killer) {
    WorldEvent death = new WorldEvent.ObjectDied(victim.snapshot(), killer.id());
    emitToObserversAndSelf(victim, death);
    if (victim instanceof Monster monster) {
      monster.diedAt = clock.getAsLong();
      monster.targetId = 0;
      dropLoot(monster);
      if (killer instanceof Player player) {
        awardExperience(player, monster.template.experience());
      }
    }
  }

  private void awardExperience(Player player, long experience) {
    if (experience <= 0) return;
    Ability before = player.ability;
    long nextTotal = before.exp() + experience;
    Ability updated = before.withExp(nextTotal);
    player.setAbility(updated);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      throw failure;
    }
    emit(player, new WorldEvent.ExperienceGained(player.id, experience, nextTotal));
  }

  private void dropLoot(Monster monster) {
    for (ItemDrop drop : monster.template.drops()) {
      if (random.nextInt(drop.oneInChance()) != 0) continue;
      Position dropPosition = findDropPosition(monster.map, monster.position);
      if (dropPosition == null) continue;
      int itemId = allocateObjectId();
      int looks = itemDatabase.find(drop.itemName())
          .map(StdItem::looks)
          .orElse(drop.looks());
      GroundItem item = new GroundItem(itemId, drop.itemName(), looks, monster.map.id(), dropPosition);
      groundItems.put(itemId, item);
      itemDropTimes.put(itemId, clock.getAsLong());
      monster.droppedItemIds.add(itemId);
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, dropPosition, 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
  }

  private Position findDropPosition(GameMap map, Position center) {
    if (map.isTerrainWalkable(center)) return center;
    for (int radius = 1; radius <= 2; radius++) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
          Position candidate = new Position(center.x() + dx, center.y() + dy);
          if (map.isTerrainWalkable(candidate)) return candidate;
        }
      }
    }
    return null;
  }

  /**
   * Scans every registered door on every loaded map and closes those whose 5-second
   * open duration has elapsed, mirroring {@code TUserEngine.ProcessMapDoor} (500ms timer).
   * Broadcasts {@code SM_CLOSEDOOR} to observers within +/-12 cells of the closed anchor.
   */
  private void closeDoorsPeriodically() {
    long now = clock.getAsLong();
    for (GameMap map : maps.values()) {
      for (DoorInfo door : map.doors()) {
        if (door.status().opened() && (now - door.status().openedAt() >= DoorInfo.AUTO_CLOSE_MILLIS)) {
          door.status().close();
          WorldEvent closedEvent = new WorldEvent.DoorClosed(map.id(), door.anchor());
          for (int viewerId : playersInSquare(map, door.anchor())) {
            emit(players.get(viewerId), closedEvent);
          }
        }
      }
    }
  }

  /**
   * Processes registered MonGen spawners, replenishing missing monsters when the row's
   * respawn interval has elapsed, mirroring {@code TUserEngine.RegenMonsters}.
   */
  private void regenSpawners() {
    if (spawners.isEmpty()) return;
    long now = clock.getAsLong();
    for (Spawner spawner : spawners) {
      if (now - spawner.lastRegenAt < spawner.respawnIntervalMillis) continue;
      int alive = 0;
      for (int id : spawner.spawnedMonsterIds) {
        Monster m = monsters.get(id);
        if (m != null && m.ability.alive()) alive++;
      }
      spawner.spawnedMonsterIds.removeIf(id -> {
        Monster m = monsters.get(id);
        return m == null || !m.ability.alive();
      });
      int missing = spawner.count - alive;
      if (missing <= 0) continue;
      spawner.lastRegenAt = now;
      for (int i = 0; i < missing; i++) {
        Position pos = pickSpawnerCell(spawner.map, spawner.center, spawner.radius);
        if (pos == null) break;
        Direction dir = Direction.fromCode(random.nextInt(8));
        WorldObjectSnapshot snapshot = spawn(spawner.template, spawner.map.id(), pos, dir);
        spawner.spawnedMonsterIds.add(snapshot.id());
      }
    }
  }

  private Position pickSpawnerCell(GameMap map, Position center, int radius) {
    if (radius <= 0) {
      return map.canWalk(center) ? center : null;
    }
    for (int attempt = 0; attempt < 30; attempt++) {
      int x = center.x() + random.nextInt(2 * radius + 1) - radius;
      int y = center.y() + random.nextInt(2 * radius + 1) - radius;
      Position candidate = new Position(x, y);
      if (map.canWalk(candidate)) return candidate;
    }
    return null;
  }

  /**
   * Persists online players whose last save was at least {@code saveIntervalMillis} ago,
   * mirroring {@code TUserEngine.ProcessHumans} -> {@code SaveHumanRcd} (10-minute default).
   */
  private void savePlayersPeriodically() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (now - player.lastSavedAt >= config.saveIntervalMillis()) {
        try {
          persist(player);
          player.lastSavedAt = now;
        } catch (RuntimeException error) {
          LOG.log(Level.WARNING, "periodic player save failed for " + player.name, error);
        }
      }
    }
  }

  private void updateMonsters() {
    long now = clock.getAsLong();
    List<Monster> snapshot = new ArrayList<>(monsters.values());
    for (Monster monster : snapshot) {
      if (!monster.ability.alive()) {
        if (now - monster.diedAt >= config.corpseLingerMillis()) {
          removeMonster(monster);
        }
        continue;
      }
      Player target = acquireTarget(monster);
      if (target == null) continue;
      int distance = monster.position.distanceTo(target.position);
      if (monster.template.behavior() == MonsterBehavior.PASSIVE_FLEE) {
        monsterFlee(monster, target, now);
        continue;
      }
      if (distance == 1) {
        monsterAttack(monster, target, now);
      } else {
        monsterChase(monster, target, now);
      }
    }
  }

  private Player acquireTarget(Monster monster) {
    Player current = players.get(monster.targetId);
    if (current != null && current.ability.alive() && current.map.id().equals(monster.map.id())
        && monster.position.distanceTo(current.position) <= config.viewRange()) {
      return current;
    }
    monster.targetId = 0;
    Player closest = null;
    int closestDistance = Integer.MAX_VALUE;
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      Player candidate = players.get(viewerId);
      if (candidate == null || !candidate.ability.alive()) continue;
      int distance = monster.position.distanceTo(candidate.position);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = candidate;
      }
    }
    if (closest != null) monster.targetId = closest.id;
    return closest;
  }

  private void monsterAttack(Monster monster, Player target, long now) {
    if (now - monster.lastAttackAt < monster.template.attackIntervalMillis()) return;
    monster.lastAttackAt = now;
    monster.direction = monster.position.directionTo(target.position);
    WorldObjectSnapshot attacker = monster.snapshot();
    WorldEvent swing = new WorldEvent.ObjectAttacked(attacker, AttackKind.HIT);
    emitToObserversAndSelf(monster, swing);
    int damage = rollDamage(monster.ability, target.ability);
    applyDamage(target, monster, damage);
  }

  /**
   * Chicken/deer flee AI: moves away from the nearest player in the opposite direction;
   * never attacks.
   */
  private void monsterFlee(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    monster.lastWalkAt = now;
    Direction away = target.position.directionTo(monster.position);
    Position step = monster.position.translate(away, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, away);
      return;
    }
    Direction left = away.rotateLeft();
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = away.rotateRight();
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  private void monsterChase(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    monster.lastWalkAt = now;
    Direction direction = monster.position.directionTo(target.position);
    Position step = monster.position.translate(direction, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, direction);
      return;
    }
    Direction left = direction.rotateLeft();
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = direction.rotateRight();
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  private void stepMonster(Monster monster, Position target, Direction direction) {
    Position source = monster.position;
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(monster.map, source, monster.id));
    monster.map.move(monster.id, source, target);
    monster.position = target;
    monster.direction = direction;
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(monster.map, target, monster.id));
    WorldObjectSnapshot snapshot = monster.snapshot();
    emitMovementToObservers(snapshot, source, MovementKind.WALK, visibleBefore, visibleAfter);
  }

  private void removeMonster(Monster monster) {
    monster.map.remove(monster.id, monster.position);
    monsters.remove(monster.id);
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(monster.id);
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      emit(players.get(viewerId), disappeared);
    }
  }

  private void expireGroundItems() {
    long now = clock.getAsLong();
    List<GroundItem> expired = new ArrayList<>();
    for (Map.Entry<Integer, Long> entry : itemDropTimes.entrySet()) {
      if (now - entry.getValue() >= config.itemLingerMillis()) {
        GroundItem item = groundItems.get(entry.getKey());
        if (item != null) expired.add(item);
      }
    }
    for (GroundItem item : expired) {
      groundItems.remove(item.id());
      itemDropTimes.remove(item.id());
      WorldEvent disappeared = new WorldEvent.ItemDisappeared(item);
      GameMap map = maps.get(item.mapId());
      if (map != null) {
        for (int viewerId : visibleIds(map, item.position(), 0)) {
          emit(players.get(viewerId), disappeared);
        }
      }
    }
  }

  private void persist(Player player) {
    playerStateStore.save(player.state());
  }

  private MoveResult rejectMove(Player player, Position target, WorldEvent.MoveRejection reason) {
    emit(player, new WorldEvent.MoveRejected(player.id, target, reason));
    return MoveResult.rejected(reason);
  }

  private AttackResult rejectAttack(Player player, WorldEvent.AttackRejection reason) {
    emit(player, new WorldEvent.AttackRejected(player.id, reason));
    return AttackResult.rejected(reason);
  }

  private void emitOwnVisibilityChanges(
      Player player, Set<Integer> visibleBefore, Set<Integer> visibleAfter) {
    for (int enteredId : difference(visibleAfter, visibleBefore)) {
      WorldObject entered = findObject(enteredId);
      if (entered != null) emit(player, new WorldEvent.ObjectAppeared(entered.snapshot()));
    }
    for (int leftId : difference(visibleBefore, visibleAfter)) {
      emit(player, new WorldEvent.ObjectDisappeared(leftId));
    }
  }

  private void emitItemVisibilityChanges(Player player, Position source, Position target) {
    List<GroundItem> before = visibleItems(player.map, source);
    List<GroundItem> after = visibleItems(player.map, target);
    for (GroundItem hidden : before) {
      if (!after.contains(hidden)) emit(player, new WorldEvent.ItemDisappeared(hidden));
    }
    for (GroundItem shown : after) {
      if (!before.contains(shown)) emit(player, new WorldEvent.ItemAppeared(shown));
    }
  }

  private void emitMovementToObservers(
      WorldObjectSnapshot movedObject,
      Position source,
      MovementKind movement,
      Set<Integer> visibleBefore,
      Set<Integer> visibleAfter) {
    Set<Integer> observers = new LinkedHashSet<>(visibleBefore);
    observers.addAll(visibleAfter);
    for (int observerId : observers) {
      Player observer = players.get(observerId);
      if (observer == null) continue;
      if (visibleBefore.contains(observerId) && visibleAfter.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectMoved(movedObject, source, movement));
      } else if (visibleBefore.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectDisappeared(movedObject.id()));
      } else {
        emit(observer, new WorldEvent.ObjectAppeared(movedObject));
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
      if (id != excludedId && (players.containsKey(id) || monsters.containsKey(id))) result.add(id);
    }
    return result;
  }

  private List<GroundItem> visibleItems(GameMap map, Position center) {
    List<GroundItem> result = new ArrayList<>();
    for (GroundItem item : groundItems.values()) {
      if (item.mapId().equals(map.id()) && item.position().distanceTo(center) <= config.viewRange()) {
        result.add(item);
      }
    }
    return List.copyOf(result);
  }

  private List<GroundItem> itemsOn(String mapId, Position position) {
    List<GroundItem> result = new ArrayList<>();
    for (GroundItem item : groundItems.values()) {
      if (item.mapId().equals(mapId) && item.position().equals(position)) result.add(item);
    }
    return List.copyOf(result);
  }

  private GroundItem newestItemOn(String mapId, Position position) {
    GroundItem newest = null;
    for (GroundItem item : groundItems.values()) {
      if (!item.mapId().equals(mapId) || !item.position().equals(position)) continue;
      if (newest == null || item.id() > newest.id()) newest = item;
    }
    return newest;
  }

  private WorldObject objectAt(GameMap map, Position position) {
    int id = map.objectAt(position);
    return id == 0 ? null : findObject(id);
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

  private WorldObject findObject(int id) {
    Player player = players.get(id);
    if (player != null) return player;
    return monsters.get(id);
  }

  private WorldObject requireObject(int id) {
    WorldObject object = findObject(id);
    if (object == null) throw new NoSuchElementException("object is not in the world: " + id);
    return object;
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

  private int allocateMakeIndex() {
    int current = nextItemMakeIndex;
    if (nextItemMakeIndex >= Integer.MAX_VALUE / 2 - 1) nextItemMakeIndex = 1;
    else nextItemMakeIndex++;
    return current;
  }

  private static int seedMakeIndex(int highWater) {
    return highWater <= 0 ? 1 : highWater + 1;
  }

  private PlayerState withStableMakeIndexes(PlayerState state) {
    List<BackpackItem> updated = new ArrayList<>(state.backpack().size());
    boolean modified = false;
    for (BackpackItem item : state.backpack()) {
      if (item.makeIndex() <= 0) {
        updated.add(new BackpackItem(item.item(), allocateMakeIndex(), item.dura(), item.duraMax()));
        modified = true;
      } else {
        updated.add(item);
      }
    }
    return modified ? new PlayerState(state.characterId(), state.ability(), updated) : state;
  }

  private static UUID transientCharacterId(String name) {
    return UUID.nameUUIDFromBytes(("transient:" + name).getBytes());
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

  private static final class Spawner {
    private final MonsterTemplate template;
    private final GameMap map;
    private final Position center;
    private final int radius;
    private final int count;
    private final long respawnIntervalMillis;
    private final List<Integer> spawnedMonsterIds = new ArrayList<>();
    private long lastRegenAt;

    private Spawner(MonsterTemplate template, GameMap map, Position center, int radius,
        int count, long respawnIntervalMillis) {
      this.template = template;
      this.map = map;
      this.center = center;
      this.radius = radius;
      this.count = count;
      this.respawnIntervalMillis = respawnIntervalMillis;
    }
  }

  /** Common state of every solid object tracked by the map occupancy index. */
  private sealed interface WorldObject permits Player, Monster {
    int id();

    GameMap map();

    Position position();

    Ability ability();

    void setAbility(Ability ability);

    WorldObjectSnapshot snapshot();
  }

  private static final class Player implements WorldObject {
    private final int id;
    private final UUID characterId;
    private final String name;
    // m_PEnvir equivalent: reassigned by EnterAnotherMap when a gate teleports the player.
    private GameMap map;
    private final int feature;
    private final int status;
    private final WorldEventSink sink;
    private final List<BackpackItem> backpack;
    private Position position;
    private Direction direction;
    private Ability ability;
    private long lastAttackAt = Long.MIN_VALUE / 4;
    private long lastSavedAt;

    private Player(
        int id,
        UUID characterId,
        String name,
        GameMap map,
        Position position,
        Direction direction,
        int feature,
        int status,
        Ability ability,
        List<BackpackItem> backpack,
        WorldEventSink sink) {
      this.id = id;
      this.characterId = characterId;
      this.name = name;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.feature = feature;
      this.status = status;
      this.ability = ability;
      this.backpack = new ArrayList<>(backpack);
      this.sink = sink;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public GameMap map() {
      return map;
    }

    @Override
    public Position position() {
      return position;
    }

    @Override
    public Ability ability() {
      return ability;
    }

    @Override
    public void setAbility(Ability ability) {
      this.ability = ability;
    }

    private PlayerState state() {
      return new PlayerState(characterId, ability, backpack);
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, name, WorldObjectType.PLAYER, map.id(), position, direction, feature, status, ability);
    }
  }

  private static final class Monster implements WorldObject {
    private final int id;
    private final MonsterTemplate template;
    private final GameMap map;
    private final List<Integer> droppedItemIds = new ArrayList<>();
    private Position position;
    private Direction direction;
    private Ability ability;
    private int targetId;
    private long lastWalkAt;
    private long lastAttackAt;
    private long diedAt;

    private Monster(
        int id, MonsterTemplate template, GameMap map, Position position, Direction direction, long now) {
      this.id = id;
      this.template = template;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.ability = template.ability();
      this.lastWalkAt = now;
      this.lastAttackAt = now;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public GameMap map() {
      return map;
    }

    @Override
    public Position position() {
      return position;
    }

    @Override
    public Ability ability() {
      return ability;
    }

    @Override
    public void setAbility(Ability ability) {
      this.ability = ability;
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, template.name(), WorldObjectType.MONSTER, map.id(), position, direction,
          template.feature(), 0, ability);
    }
  }
}
