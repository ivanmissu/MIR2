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
    this.config = Objects.requireNonNull(config);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
    this.playerStateStore = Objects.requireNonNull(playerStateStore, "playerStateStore");
    this.itemDatabase = Objects.requireNonNull(itemDatabase, "itemDatabase");
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
      return enter(characterId, name, mapId, nearestAvailable(map, preferredPosition),
          direction, feature, status, sink);
    });
  }

  /** Spawns a melee monster; monsters have no sink and are only observed through player events. */
  public CompletableFuture<WorldObjectSnapshot> spawnMonster(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> spawn(template, mapId, position, direction));
  }

  /**
   * Registers a self-replenishing spawn area, mirroring one {@code TMonGenInfo} row: the world
   * keeps {@code count} monsters of the template alive inside the square around
   * ({@code x},{@code y}), re-rolling random cells like {@code TUserEngine.RegenMonsters}, and
   * refills losses once {@code respawnMillis} has elapsed since the previous regeneration.
   * The initial population is materialised on the next tick.
   */
  public CompletableFuture<Void> addSpawner(MonsterTemplate template, String mapId,
      MonsterSpawnDefinition definition) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(definition, "definition");
    return submit(() -> {
      requireMap(mapId);
      spawners.add(new Spawner(template, mapId, definition));
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

  /** The claimed position is checked to reject stale or accelerated client actions. */
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

  /** Picks up the newest item lying on the player's own cell, as {@code ClientPickUpItem} does. */
  public CompletableFuture<Boolean> pickUp(int playerId, Position claimedPosition) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    return submit(() -> pickUpItem(playerId, claimedPosition));
  }

  /**
   * Handles {@code CM_OPENDOOR}: opens the door anchored at {@code claimed} on the player's
   * map and broadcasts {@code DoorOpened} to every player inside the +/-12 client square,
   * mirroring {@code TUserEngine.OpenDoor}. Unknown cells and already-open doors answer
   * silently {@code false}, as the Delphi handler does (no acknowledgement is sent).
   */
  public CompletableFuture<Boolean> openDoor(int playerId, Position claimed) {
    Objects.requireNonNull(claimed, "claimed");
    return submit(() -> openDoorAt(playerId, claimed));
  }

  /**
   * Links a gate cell into its source map, mirroring {@code TMapManager.AddMapRoute}: the
   * route is registered only when both maps are loaded, and the returned future reports
   * whether the link was installed (a route naming an unknown map is dropped, like the
   * Delphi loader).
   */
  public CompletableFuture<Boolean> addRoute(TeleportRoute route) {
    Objects.requireNonNull(route, "route");
    return submit(() -> {
      if (!maps.containsKey(route.sourceMapId()) || !maps.containsKey(route.destinationMapId())) {
        return false;
      }
      maps.get(route.sourceMapId()).addGate(route);
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
      UUID characterId, String name, String mapId, Position position, Direction direction,
      int feature, int status, WorldEventSink sink) {
    if (name.isBlank()) throw new IllegalArgumentException("player name must not be blank");
    if (playersByName.containsKey(name)) throw new IllegalStateException("player is already online: " + name);
    GameMap map = requireMap(mapId);
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
        player.snapshot(), map.info(), visible, visibleItems(map, position)));
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
    // Delphi handler does not gate on zone or alive state. The γ01 (castle-taken) flag has no
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
    emit(player, new WorldEvent.PlayerMapChanged(snapshot, destination.info()));
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

  private void leave(int playerId) {
    Player player = requirePlayer(playerId);
    persist(player);
    List<Integer> visibleIds = visibleIds(player.map, player.position, player.id);
    player.map.remove(player.id, player.position);
    players.remove(player.id);
    playersByName.remove(player.name);
    for (Monster monster : monsters.values()) {
      if (monster.targetId == player.id) monster.targetId = 0;
    }
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(player.id);
    for (int viewerId : visibleIds) emit(players.get(viewerId), disappeared);
    emit(player, new WorldEvent.MapLeft(player.id));
  }

  // ---------------------------------------------------------------- combat

  private int rollDamage(Ability attacker, Ability defender) {
    int attack = randomBetween(attacker.minDc(), attacker.maxDc());
    int defence = randomBetween(defender.minAc(), defender.maxAc());
    return Math.max(0, attack - defence);
  }

  private int randomBetween(int low, int high) {
    if (high <= low) return low;
    return low + random.nextInt(high - low + 1);
  }

  private void applyDamage(WorldObject victim, WorldObject attacker, int damage) {
    if (damage <= 0) {
      broadcastStruck(victim, attacker.id(), 0);
      return;
    }
    Ability previous = victim.ability();
    victim.setAbility(previous.withHp(previous.hp() - damage));
    if (victim instanceof Player player) {
      try {
        persist(player);
      } catch (RuntimeException failure) {
        victim.setAbility(previous);
        throw failure;
      }
    }
    broadcastStruck(victim, attacker.id(), damage);
    if (victim.ability().alive()) return;
    handleDeath(victim, attacker);
  }

  private void broadcastStruck(WorldObject victim, int attackerId, int damage) {
    WorldEvent struck = new WorldEvent.ObjectStruck(victim.snapshot(), attackerId, damage);
    WorldEvent health = new WorldEvent.HealthChanged(victim.snapshot());
    for (int viewerId : visibleIds(victim.map(), victim.position(), 0)) {
      Player viewer = players.get(viewerId);
      emit(viewer, struck);
      emit(viewer, health);
    }
  }

  private void handleDeath(WorldObject victim, WorldObject killer) {
    long now = clock.getAsLong();
    WorldEvent died = new WorldEvent.ObjectDied(victim.snapshot(), killer.id());
    for (int viewerId : visibleIds(victim.map(), victim.position(), 0)) emit(players.get(viewerId), died);

    if (victim instanceof Monster monster) {
      monster.diedAt = now;
      monster.targetId = 0;
      dropLoot(monster);
      if (killer instanceof Player player) awardExperience(player, monster.template.experience());
    }
    // Player corpses stay on the map until the socket session leaves, matching the Delphi flow
    // where TPlayObject remains in the environment until revival or logout.
  }

  private void awardExperience(Player player, long experience) {
    if (experience <= 0) return;
    Ability previous = player.ability;
    player.ability = previous.addExperience(experience);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.ability = previous;
      throw failure;
    }
    emit(player, new WorldEvent.ExperienceGained(player.id, experience, player.ability.experience()));
  }

  private void dropLoot(Monster monster) {
    for (ItemDrop drop : monster.template.drops()) {
      if (!drop.always() && random.nextInt(drop.oneIn()) != 0) continue;
      Position cell = freeItemCell(monster.map, monster.position);
      if (cell == null) continue;
      // The catalog Looks is canonical when a template exists; the drop entry keeps it
      // for items the catalog does not know.
      int looks = itemDatabase.find(drop.name()).map(StdItem::looks).orElse(drop.looks());
      GroundItem item = new GroundItem(allocateObjectId(), drop.name(), looks, monster.map.id(), cell);
      groundItems.put(item.id(), item);
      itemDropTimes.put(item.id(), clock.getAsLong());
      monster.droppedItemIds.add(item.id());
      WorldEvent shown = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, cell, 0)) emit(players.get(viewerId), shown);
    }
  }

  /** Delphi drops on the death cell first, then scans the surrounding ring for a free cell. */
  private Position freeItemCell(GameMap map, Position center) {
    if (itemsOn(map.id(), center).isEmpty() && map.isTerrainWalkable(center)) return center;
    for (Direction direction : Direction.values()) {
      Position candidate = center.translate(direction, 1);
      if (map.isTerrainWalkable(candidate) && itemsOn(map.id(), candidate).isEmpty()) return candidate;
    }
    return null;
  }

  // ------------------------------------------------------------ monster AI

  /**
   * One {@code TMonGenInfo} equivalent. {@code spawnedIds} mirrors the Delphi {@code CertList}:
   * {@code GetGenMonCount} counts its non-dead members to decide how many to regenerate.
   */
  private static final class Spawner {
    private final MonsterTemplate template;
    private final String mapId;
    private final MonsterSpawnDefinition definition;
    private final List<Integer> spawnedIds = new ArrayList<>();
    private long startTick;

    private Spawner(MonsterTemplate template, String mapId, MonsterSpawnDefinition definition) {
      this.template = template;
      this.mapId = mapId;
      this.definition = definition;
    }
  }

  /** Delphi paces one MonGen per {@code dwRegenMonstersTime} (200ms) pass; we do the same. */
  private long lastRegenAt = Long.MIN_VALUE / 4;
  private int currentSpawner;

  // TUserEngine.ProcessMapDoor runs off its own 500ms cadence (dwProcessMapDoorTick) and
  // closes any door once 5 seconds have passed since dwOpenTick.
  private static final long DOOR_SWEEP_MILLIS = 500;
  private static final long DOOR_CLOSE_AFTER_MILLIS = 5_000;
  private long lastDoorSweepAt = Long.MIN_VALUE / 4;

  /** The 500ms door sweep: closes doors opened for more than 5 seconds and broadcasts it. */
  private void closeDoorsPeriodically() {
    long now = clock.getAsLong();
    if (now - lastDoorSweepAt < DOOR_SWEEP_MILLIS) return;
    lastDoorSweepAt = now;
    for (GameMap map : maps.values()) {
      if (!map.hasDoors()) continue;
      for (DoorInfo door : map.doors()) {
        DoorInfo.DoorStatus status = door.status();
        if (status.opened() && now - status.openedAtMillis() > DOOR_CLOSE_AFTER_MILLIS) {
          status.close();
          // The client re-closes every map cell sharing this door index (MapUnit.pas scans an
          // index-symmetric window), so one broadcast per linked anchor is enough — matching
          // Delphi's per-record CloseDoor over its m_DoorList iteration order.
          WorldEvent closed = new WorldEvent.DoorClosed(map.id(), door.anchor());
          for (int viewerId : playersInSquare(map, door.anchor())) emit(players.get(viewerId), closed);
        }
      }
    }
  }

  private void regenSpawners() {
    if (spawners.isEmpty()) return;
    long now = clock.getAsLong();
    if (now - lastRegenAt < config.regenIntervalMillis()) return;
    lastRegenAt = now;
    Spawner spawner = spawners.get(currentSpawner);
    currentSpawner = (currentSpawner + 1) % spawners.size();
    if (spawner.startTick != 0 && now - spawner.startTick <= spawner.definition.respawnMillis()) return;

    spawner.spawnedIds.removeIf(id -> {
      Monster monster = monsters.get(id);
      return monster == null || !monster.ability.alive();
    });
    int missing = spawner.definition.count() - spawner.spawnedIds.size();
    if (missing <= 0) {
      spawner.startTick = now;
      return;
    }
    GameMap map = maps.get(spawner.mapId);
    if (map == null) return;
    int range = spawner.definition.range();
    boolean regenerated = false;
    for (int i = 0; i < missing; i++) {
      // RegenMonsters rolls a random cell inside the square and simply skips blocked ones.
      Position candidate = null;
      for (int attempt = 0; attempt < 10 && candidate == null; attempt++) {
        Position roll = new Position(
            spawner.definition.x() - range + random.nextInt(range * 2 + 1),
            spawner.definition.y() - range + random.nextInt(range * 2 + 1));
        if (map.canWalk(roll)) candidate = roll;
      }
      if (candidate == null) continue;
      WorldObjectSnapshot spawned = spawn(spawner.template, spawner.mapId, candidate,
          Direction.fromCode(random.nextInt(8)));
      spawner.spawnedIds.add(spawned.id());
      regenerated = true;
    }
    if (regenerated || missing <= 0) spawner.startTick = now;
  }

  /**
   * Periodic online save, mirroring the {@code ProcessHumans} branch that calls
   * {@code SaveHumanRcd} once {@code dwSaveHumanRcdTime} has elapsed per player. Event-driven
   * saves (damage, pickup, leave) already persist eagerly; this catches slow-changing state.
   */
  private void savePlayersPeriodically() {
    if (players.isEmpty()) return;
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (now - player.lastSavedAt < config.saveIntervalMillis()) continue;
      try {
        persist(player);
        player.lastSavedAt = now;
      } catch (RuntimeException failure) {
        // Never let a storage hiccup kill the tick loop; the next interval retries.
        LOG.log(Level.WARNING, "periodic save failed for " + player.name, failure);
      }
    }
  }

  private void updateMonsters() {
    if (monsters.isEmpty()) return;
    long now = clock.getAsLong();
    List<Monster> snapshot = new ArrayList<>(monsters.values());
    for (Monster monster : snapshot) {
      if (!monster.ability.alive()) {
        if (now - monster.diedAt >= config.corpseLingerMillis()) removeMonster(monster);
        continue;
      }
      Player target = acquireTarget(monster);
      if (target == null) continue;
      if (monster.template.behavior() == MonsterBehavior.PASSIVE_FLEE) {
        monsterFlee(monster, target, now);
      } else if (monster.position.distanceTo(target.position) <= 1) {
        monsterAttack(monster, target, now);
      } else {
        monsterChase(monster, target, now);
      }
    }
  }

  private Player acquireTarget(Monster monster) {
    Player current = players.get(monster.targetId);
    if (current != null && current.ability.alive()
        && current.map == monster.map
        && current.position.distanceTo(monster.position) <= monster.template.viewRange()) {
      return current;
    }
    monster.targetId = 0;
    Player best = null;
    for (int candidateId : visibleIds(monster.map, monster.position, monster.id)) {
      Player candidate = players.get(candidateId);
      if (candidate == null || !candidate.ability.alive()) continue;
      if (candidate.position.distanceTo(monster.position) > monster.template.viewRange()) continue;
      if (best == null || candidate.position.distanceTo(monster.position)
          < best.position.distanceTo(monster.position)) {
        best = candidate;
      }
    }
    if (best != null) monster.targetId = best.id;
    return best;
  }

  private void monsterAttack(Monster monster, Player target, long now) {
    if (now - monster.lastAttackAt < monster.template.attackIntervalMillis()) return;
    monster.lastAttackAt = now;
    monster.direction = Direction.toward(monster.position, target.position);
    WorldObjectSnapshot attacker = monster.snapshot();
    WorldEvent swing = new WorldEvent.ObjectAttacked(attacker, AttackKind.HIT);
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) emit(players.get(viewerId), swing);
    applyDamage(target, monster, rollDamage(monster.ability, target.ability));
  }

  /**
   * TChickenDeer run-away mode: walk in the direction opposite to the nearest player, falling
   * back to the neighbouring directions when the straight line is blocked. Fleeing animals
   * never attack.
   */
  private void monsterFlee(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    if (monster.position.equals(target.position)) return;
    Direction away = Direction.toward(target.position, monster.position);
    Direction chosen = null;
    Position next = null;
    for (Direction candidateDirection : new Direction[] {away, rotate(away, 1), rotate(away, -1)}) {
      Position candidate = monster.position.translate(candidateDirection, 1);
      if (monster.map.canWalk(candidate)) {
        chosen = candidateDirection;
        next = candidate;
        break;
      }
    }
    if (chosen == null) return;
    monster.lastWalkAt = now;
    Position source = monster.position;
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(monster.map, source, monster.id));
    monster.map.move(monster.id, source, next);
    monster.position = next;
    monster.direction = chosen;
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(monster.map, next, monster.id));
    emitMovementToObservers(monster.snapshot(), source, MovementKind.WALK, visibleBefore, visibleAfter);
  }

  private void monsterChase(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    Direction direction = Direction.toward(monster.position, target.position);
    Position next = monster.position.translate(direction, 1);
    if (!monster.map.canWalk(next)) {
      // A blocked straight line falls back to the two neighbouring directions, as TMonster does.
      Direction[] alternatives = {rotate(direction, 1), rotate(direction, -1)};
      Position chosen = null;
      for (Direction alternative : alternatives) {
        Position candidate = monster.position.translate(alternative, 1);
        if (monster.map.canWalk(candidate)) {
          direction = alternative;
          chosen = candidate;
          break;
        }
      }
      if (chosen == null) return;
      next = chosen;
    }
    monster.lastWalkAt = now;
    Position source = monster.position;
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(monster.map, source, monster.id));
    monster.map.move(monster.id, source, next);
    monster.position = next;
    monster.direction = direction;
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(monster.map, next, monster.id));
    emitMovementToObservers(monster.snapshot(), source, MovementKind.WALK, visibleBefore, visibleAfter);
  }

  private static Direction rotate(Direction direction, int steps) {
    Direction[] values = Direction.values();
    return values[Math.floorMod(direction.code() + steps, values.length)];
  }

  private void removeMonster(Monster monster) {
    monsters.remove(monster.id);
    if (monster.map.objectAt(monster.position) == monster.id) {
      monster.map.remove(monster.id, monster.position);
    }
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(monster.id);
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      emit(players.get(viewerId), disappeared);
    }
  }

  private void expireGroundItems() {
    if (groundItems.isEmpty() || config.itemLingerMillis() == 0) return;
    long now = clock.getAsLong();
    List<GroundItem> expired = new ArrayList<>();
    for (Map.Entry<Integer, GroundItem> entry : groundItems.entrySet()) {
      Long droppedAt = itemDropTimes.get(entry.getKey());
      if (droppedAt != null && now - droppedAt >= config.itemLingerMillis()) expired.add(entry.getValue());
    }
    for (GroundItem item : expired) {
      groundItems.remove(item.id());
      itemDropTimes.remove(item.id());
      GameMap map = maps.get(item.mapId());
      if (map == null) continue;
      WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
      for (int viewerId : visibleIds(map, item.position(), 0)) emit(players.get(viewerId), hidden);
    }
  }

  // ---------------------------------------------------------------- shared

  private void persist(Player player) {
    playerStateStore.save(player.state());
  }

  /** W03 rows were saved without make indexes; assign stable ones while restoring. */
  private PlayerState withStableMakeIndexes(PlayerState state) {
    boolean needsRenumber = state.backpack().stream().anyMatch(item -> item.makeIndex() <= 0);
    if (!needsRenumber) return state;
    List<BackpackItem> normalised = state.backpack().stream()
        .map(item -> item.makeIndex() > 0 ? item : item.withMakeIndex(allocateMakeIndex()))
        .toList();
    return new PlayerState(state.characterId(), state.ability(), normalised);
  }

  /**
   * Per-instance item id, mirroring M2Share {@code GetItemNumber}: increments and wraps
   * back to 1 once it passes {@code High(Integer) / 2 - 1}. Seeded from the persisted
   * high-water mark so ids stay unique across restarts.
   */
  private int allocateMakeIndex() {
    if (nextItemMakeIndex > Integer.MAX_VALUE / 2 - 1) nextItemMakeIndex = 1;
    return nextItemMakeIndex++;
  }

  private static int seedMakeIndex(long highWater) {
    if (highWater < 0) throw new IllegalArgumentException("make-index high water must not be negative");
    return (int) Math.min(Math.max(highWater + 1, 1), Integer.MAX_VALUE / 2 - 1);
  }

  private static UUID transientCharacterId(String name) {
    Objects.requireNonNull(name, "name");
    return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private MoveResult rejectMove(
      Player player, Position target, WorldEvent.MoveRejection reason) {
    emit(player, new WorldEvent.MoveRejected(player.id, target, reason));
    return MoveResult.rejected(player.snapshot(), reason);
  }

  private AttackResult rejectAttack(Player player, WorldEvent.AttackRejection reason) {
    emit(player, new WorldEvent.AttackRejected(player.id, reason));
    return AttackResult.rejected(player.snapshot(), reason);
  }

  private void emitOwnVisibilityChanges(
      Player movingPlayer, Set<Integer> visibleBefore, Set<Integer> visibleAfter) {
    for (int oldObject : difference(visibleBefore, visibleAfter))
      emit(movingPlayer, new WorldEvent.ObjectDisappeared(oldObject));
    for (int newObject : difference(visibleAfter, visibleBefore))
      emit(movingPlayer, new WorldEvent.ObjectAppeared(requireObject(newObject).snapshot()));
  }

  private void emitItemVisibilityChanges(Player player, Position source, Position target) {
    Set<GroundItem> before = new LinkedHashSet<>(visibleItems(player.map, source));
    Set<GroundItem> after = new LinkedHashSet<>(visibleItems(player.map, target));
    for (GroundItem gone : before) {
      if (!after.contains(gone)) emit(player, new WorldEvent.ItemDisappeared(gone));
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
