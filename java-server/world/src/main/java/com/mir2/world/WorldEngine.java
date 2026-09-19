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
      long itemLingerMillis) {

    public Config {
      Objects.requireNonNull(tickInterval, "tickInterval");
      if (tickInterval.isZero() || tickInterval.isNegative() || tickInterval.toMillis() == 0)
        throw new IllegalArgumentException("tick interval must be at least one millisecond");
      if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
      if (maxCommandsPerTick < 1) throw new IllegalArgumentException("command limit must be positive");
      if (hitIntervalMillis < 0) throw new IllegalArgumentException("hit interval must not be negative");
      if (corpseLingerMillis < 0 || itemLingerMillis < 0)
        throw new IllegalArgumentException("linger durations must not be negative");
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
  private int nextObjectId = 1;

  public WorldEngine(Config config, Collection<GameMap> maps) {
    this(config, maps, System::currentTimeMillis, new Random());
  }

  public WorldEngine(Collection<GameMap> maps) {
    this(Config.defaults(), maps);
  }

  /** Deterministic constructor: tests inject a virtual clock and a seeded damage generator. */
  public WorldEngine(Config config, Collection<GameMap> maps, LongSupplier clock, Random random) {
    this.config = Objects.requireNonNull(config);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
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

  /** Spawns a melee monster; monsters have no sink and are only observed through player events. */
  public CompletableFuture<WorldObjectSnapshot> spawnMonster(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> spawn(template, mapId, position, direction));
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

  public CompletableFuture<Void> leavePlayer(int playerId) {
    return submit(() -> {
      leave(playerId);
      return null;
    });
  }

  public CompletableFuture<WorldObjectSnapshot> snapshot(int objectId) {
    return submit(() -> requireObject(objectId).snapshot());
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
    updateMonsters();
    expireGroundItems();
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
    groundItems.remove(item.id());
    itemDropTimes.remove(item.id());
    player.backpack.add(item);
    emit(player, new WorldEvent.ItemPickedUp(player.id, item));
    WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
    for (int viewerId : visibleIds(player.map, item.position(), 0)) emit(players.get(viewerId), hidden);
    return true;
  }

  private void leave(int playerId) {
    Player player = requirePlayer(playerId);
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
    victim.setAbility(victim.ability().withHp(victim.ability().hp() - damage));
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
    player.ability = player.ability.addExperience(experience);
    emit(player, new WorldEvent.ExperienceGained(player.id, experience, player.ability.experience()));
  }

  private void dropLoot(Monster monster) {
    for (ItemDrop drop : monster.template.drops()) {
      if (!drop.always() && random.nextInt(drop.oneIn()) != 0) continue;
      Position cell = freeItemCell(monster.map, monster.position);
      if (cell == null) continue;
      GroundItem item = new GroundItem(allocateObjectId(), drop.name(), drop.looks(), monster.map.id(), cell);
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
      if (monster.position.distanceTo(target.position) <= 1) {
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
    private final String name;
    private final GameMap map;
    private final int feature;
    private final int status;
    private final WorldEventSink sink;
    private final List<GroundItem> backpack = new ArrayList<>();
    private Position position;
    private Direction direction;
    private Ability ability = Ability.defaultPlayer();
    private long lastAttackAt = Long.MIN_VALUE / 4;

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
