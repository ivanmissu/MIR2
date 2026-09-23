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

  /**
   * {@code TUserEngine.ProcessMapDoor} closes a door once it has been open for more than
   * 5000ms ({@code GetTickCount - dwOpenTick > 5000}).
   */
  private static final long DOOR_AUTO_CLOSE_MILLIS = 5_000;

  /**
   * {@code g_Config.nDieScatterBagRate} (M2Share.pas:2020): a non-red character drops one bag
   * entry in three when it dies. Red names drop everything, but the PK-level model is not
   * migrated yet, so only the ordinary rate applies.
   */
  private static final int DIE_SCATTER_BAG_RATE = 3;

  /** {@code g_Config.nMonOneDropGoldCount} (M2Share.pas:1959): one monster gold pile caps here. */
  private static final int MON_ONE_DROP_GOLD_COUNT = 2_000;

  /** {@code DropGoldDown} always scatters within range 3 around the corpse. */
  private static final int GOLD_DROP_RANGE = 3;

  /** {@code ScatterGolds} stops after 17 successful pile attempts. */
  private static final int MAX_GOLD_DROP_PILES = 17;

  /**
   * {@code g_Config.dwMakeGhostTime} (M2Share.pas:1796): a corpse becomes a ghost — i.e. it
   * leaves the map — three minutes after death.
   */
  private static final long MAKE_GHOST_MILLIS = 3 * 60 * 1000L;

  /** {@code g_Config.dwRevivalTime} (M2Share.pas) = 60 seconds between ring revivals. */
  private static final long REVIVAL_COOLDOWN_MILLIS = 60 * 1000L;

  /** {@code ItemDamageRevivalRing} drains exactly {@code Dec(nDura, 1000)} per item. */
  private static final int REVIVAL_RING_DURABILITY_COST = 1000;

  /** {@code g_sRevivalRecoverMsg} (M2Share.pas:3194): the green hint shown when a revival ring
   * fires — 「复活戒指生效，体力恢复.」 in the shipped GBK source.
   */
  private static final String REVIVAL_RECOVER_MESSAGE = "复活戒指生效，体力恢复.";

  /** {@code g_Config.nSuperRepairPriceRate} (M2Share.pas) = 3. */
  private static final int SUPER_REPAIR_PRICE_RATE = 3;

  /** {@code g_Config.nRepairItemDecDura} (M2Share.pas) = 30. */
  private static final int REPAIR_ITEM_DEC_DURA = 30;

  /** StdMode 43 (宝石) is the one category {@code ClientRepairItem} refuses outright. */
  private static final int REPAIR_REFUSED_STD_MODE = 43;

  /** {@code TBaseObject.Run} advances the HP/MP counters by {@code elapsed div 20}. */
  private static final long HP_MP_TICK_MILLIS = 20;

  /** {@code g_Config.nHealthFillTime} (M2Share.pas:1638) = 300 counter units = 6 seconds. */
  private static final long HEALTH_FILL_TICKS = 300;

  /** {@code g_Config.nSpellFillTime} (M2Share.pas:1639) = 800 counter units = 16 seconds. */
  private static final long SPELL_FILL_TICKS = 800;

  /**
   * {@code TUserEngine.AddPlayObject} (UsrEngn.pas:600): a character loaded at {@code HP <= 0}
   * is moved home and set to {@code m_Abil.HP := 14} before entering the world.
   */
  private static final int REVIVE_ON_LOGIN_HP = 14;

  public record Config(
      Duration tickInterval,
      int viewRange,
      int maxCommandsPerTick,
      long hitIntervalMillis,
      long corpseLingerMillis,
      long itemLingerMillis,
      long regenIntervalMillis,
      long saveIntervalMillis,
      long testGold) {

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
      if (testGold < 0 || testGold > PlayerState.MAX_GOLD)
        throw new IllegalArgumentException("test gold must be within 0.." + PlayerState.MAX_GOLD);
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, 0);
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
  private final WorldRandom random;
  private final PlayerStateStore playerStateStore;
  private final ItemDatabase itemDatabase;
  private final IntSupplier hourSupplier;
  private int gameTime;
  private int nextObjectId = 1;
  private int nextItemMakeIndex = 1;

  public WorldEngine(Config config, Collection<GameMap> maps) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), PlayerStateStore.none(),
        ItemDatabase.empty());
  }

  public WorldEngine(Config config, Collection<GameMap> maps, PlayerStateStore playerStateStore) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), playerStateStore,
        ItemDatabase.empty());
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), playerStateStore,
        itemDatabase);
  }

  /**
   * Production constructor with an explicit randomness policy. Pass
   * {@link WorldRandom#seeded(long)} to make the damage/loot streams reproducible across two
   * server processes — the precondition for PvE 影子对拍.
   */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      WorldRandom random) {
    this(config, maps, System::currentTimeMillis, random, playerStateStore, itemDatabase);
  }

  public WorldEngine(Collection<GameMap> maps) {
    this(Config.defaults(), maps);
  }

  /** Deterministic constructor: tests inject a virtual clock and a seeded damage generator. */
  public WorldEngine(Config config, Collection<GameMap> maps, LongSupplier clock, Random random) {
    this(config, maps, clock, WorldRandom.of(random), PlayerStateStore.none(), ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore) {
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port and the standard-item catalog. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, itemDatabase);
  }

  /** Deterministic constructor taking the split-stream randomness policy directly. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      WorldRandom random,
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
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, itemDatabase, hourSupplier);
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      WorldRandom random,
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

  /**
   * The world seed when this engine draws from independent per-stream generators
   * ({@link WorldRandom#seeded(long)}), otherwise empty. Two engines reporting the same seed
   * produce the same damage and loot sequences, which is what makes PvE 影子对拍 meaningful.
   */
  public java.util.OptionalLong worldSeed() {
    return random.seed();
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
    return enterPlayer(characterId, name, mapId, position, direction, feature, status,
        LevelAbilities.JOB_WARRIOR, sink);
  }

  /** Job-aware variant; the job selects the {@code RecalcLevelAbilitys} growth branch. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      UUID characterId,
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      int job,
      WorldEventSink sink) {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> enter(
        characterId, name, mapId, position, direction, feature, status, job, sink));
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
    return enterPlayerNear(characterId, name, mapId, preferredPosition, direction, feature,
        status, LevelAbilities.JOB_WARRIOR, sink);
  }

  /** Job-aware variant of the nearest-cell entry. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      UUID characterId,
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      int job,
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
          direction, feature, status, job, sink);
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
  /**
   * Registers one parsed {@code MonGen.txt} row. The definition's own map name is only a
   * label from the file; {@code mapId} is the map the caller resolved it to.
   */
  public CompletableFuture<Void> addSpawner(
      MonsterTemplate template, String mapId, MonsterSpawnDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    return addSpawner(template, mapId, new Position(definition.x(), definition.y()),
        definition.range(), definition.count(), Duration.ofMillis(definition.respawnMillis()));
  }

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

  /**
   * {@code CM_TAKEONITEM} -> {@code TPlayObject.ClientTakeOnItems} (ObjBase.pas:17072): moves
   * the bag item identified by {@code makeIndex} + {@code itemName} into {@code slotIndex}.
   */
  public CompletableFuture<Boolean> equip(
      int playerId, int slotIndex, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> equipItem(playerId, slotIndex, makeIndex, itemName));
  }

  /** {@code CM_TAKEOFFITEM} -> {@code ClientTakeOffItems} (ObjBase.pas:17221). */
  public CompletableFuture<Boolean> unequip(
      int playerId, int slotIndex, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> unequipItem(playerId, slotIndex, makeIndex, itemName));
  }

  /** {@code CM_EAT} -> {@code ClientUseItems} (ObjBase.pas:17300). */
  public CompletableFuture<Boolean> useItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> consumeItem(playerId, makeIndex, itemName));
  }

  /** {@code CM_DROPITEM} -> {@code ClientDropItem} (ObjBase.pas:16213). */
  public CompletableFuture<Boolean> dropItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> dropBagItem(playerId, makeIndex, itemName));
  }

  /**
   * {@code CM_MERCHANTDLGSELECT -> TMerchant.UserSelect} (ObjNpc.pas:1516), reduced to the one
   * label family this engine can honour without the Market_Def script engine: the label is
   * remembered as {@code m_sScriptLable} (which is how Delphi later picks the repair mode) and
   * {@code @repair} / {@code @s_repair} answer with {@code SM_SENDUSERREPAIR} so the client
   * opens its repair dialog. Every other label is stored silently — the script response it
   * would generate does not exist here yet. The merchant proximity check ({@code FindMerchant},
   * same map, |Δ|<15) needs NPC objects and is deferred to the NPC slice.
   *
   * @return true when the label opened the repair dialog
   */
  public CompletableFuture<Boolean> selectMerchantLabel(
      int playerId, int merchantId, String label) {
    Objects.requireNonNull(label, "label");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      if (!player.ability.alive()) return false;
      // UserSelect only reacts to labels that start with '@'; GetValidStr3 splits the input
      // at CR for the @@-input labels.
      String trimmed = label.strip();
      if (trimmed.isEmpty() || trimmed.charAt(0) != '@') return false;
      String firstToken = trimmed.split("\r", 2)[0];
      player.merchantLabel = trimmed;
      if ("@repair".equalsIgnoreCase(firstToken) || "@s_repair".equalsIgnoreCase(firstToken)) {
        emit(player, new WorldEvent.MerchantRepairDialog(player.id, merchantId));
        return true;
      }
      return false;
    });
  }

  /**
   * {@code CM_MERCHANTQUERYREPAIRCOST -> TMerchant.ClientQueryRepairCost} (ObjNpc.pas:2385).
   * The quote is {@code Round(price div 3 / DuraMax * (DuraMax - Dura))} — note the integer
   * division before the real arithmetic — times three for a special repair. Like Delphi, the
   * quote only ever addresses bag items ({@code m_ItemList}); worn gear has to be taken off
   * first. An item without a price or one at full durability gets the Delphi "cannot repair"
   * answer of -1. A MakeIndex/name that matches nothing in the bag stays silent, exactly like
   * {@code ClientQueryRepairCost} exiting without a reply.
   *
   * @return the quoted cost, -1 when the merchant refuses, or {@code null} when Delphi would
   *     stay silent (no such bag item)
   */
  public CompletableFuture<Integer> queryRepairCost(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int bagIndex = findBagItem(player, makeIndex, itemName);
      if (bagIndex < 0) return null;
      BackpackItem item = player.backpack.get(bagIndex);
      int cost = repairQuote(item, isSpecialRepair(player));
      emit(player, new WorldEvent.RepairCostResolved(player.id, cost));
      return cost;
    });
  }

  /**
   * {@code CM_USERREPAIRITEM -> TMerchant.ClientRepairItem} (ObjNpc.pas:2423). Normal repair
   * lowers DuraMax by the wear's thirtieth before topping Dura up
   * ({@code Dec(DuraMax, (DuraMax - Dura) div 30); Dura := DuraMax}); special repair keeps
   * DuraMax and just refills. The charged price mirrors the Delphi quirk: the query multiplies
   * the normal quote by three, while the charge triples {@code nPrice} *before* the
   * {@code div 3} — so the two only agree when the item price is a multiple of three. A
   * wallet that cannot cover the charge fails without touching the item. {@code GotoLable}
   * (the script follow-up) does not exist here yet.
   *
   * @return true on SM_USERREPAIRITEM_OK, false on SM_USERREPAIRITEM_FAIL, or {@code null}
   *     when Delphi would stay silent (no such bag item)
   */
  public CompletableFuture<Boolean> repairItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int bagIndex = findBagItem(player, makeIndex, itemName);
      if (bagIndex < 0) return null;
      BackpackItem item = player.backpack.get(bagIndex);
      boolean special = isSpecialRepair(player);
      long price = item.item().price();
      if (special) price *= SUPER_REPAIR_PRICE_RATE;
      boolean canRepair = price > 0 && item.duraMax() > item.dura()
          && item.item().stdMode() != REPAIR_REFUSED_STD_MODE;
      int charge = canRepair ? repairCharge(price, item.duraMax(), item.dura()) : 0;
      if (!canRepair || player.gold < charge) {
        emit(player, new WorldEvent.RepairRejected(player.id));
        return false;
      }
      int duraMax = special ? item.duraMax()
          : item.duraMax() - (item.duraMax() - item.dura()) / REPAIR_ITEM_DEC_DURA;
      BackpackItem repaired = new BackpackItem(item.item(), item.makeIndex(), duraMax, duraMax);

      List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
      long previousGold = player.gold;
      player.backpack.set(bagIndex, repaired);
      player.gold -= charge;
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.backpack.clear();
        player.backpack.addAll(previousBackpack);
        player.gold = previousGold;
        throw failure;
      }
      emit(player, new WorldEvent.ItemRepaired(player.id, (int) player.gold, duraMax, duraMax));
      return true;
    });
  }

  /**
   * {@code PlayObject.m_sScriptLable = sSUPERREPAIR} ('@s_repair') selects the special repair;
   * anything else — including the empty label of a fresh login — repairs normally. Delphi
   * compares with {@code =}, which is case-sensitive.
   */
  private static boolean isSpecialRepair(Player player) {
    return "@s_repair".equals(player.merchantLabel);
  }

  /**
   * The quoted price: {@code Round(nPrice div 3 / DuraMax * (DuraMax - Dura))}, ×3 for the
   * special variant ({@code nSuperRepairPriceRate}). Returns -1 when there is nothing to
   * mend or the item has no price — Delphi's "???? 金币" answer.
   */
  private static int repairQuote(BackpackItem item, boolean special) {
    long price = item.item().price();
    if (price <= 0 || item.duraMax() <= item.dura()) return -1;
    if (item.duraMax() <= 0) return (int) Math.min(price, Integer.MAX_VALUE);
    int quote = (int) Math.rint(
        (price / 3) / (double) item.duraMax() * (item.duraMax() - item.dura()));
    return special ? quote * SUPER_REPAIR_PRICE_RATE : quote;
  }

  /**
   * The charged price. The special repair tripled {@code nPrice} *before* the {@code div 3},
   * so it cancels out and lands on the un-truncated base — the quote and the charge genuinely
   * disagree whenever {@code price mod 3 != 0}. Both formulas are reproduced on purpose:
   * {@code repairQuote} triples afterwards, this one divides the tripled price.
   */
  private static int repairCharge(long pricedForMode, int duraMax, int dura) {
    if (duraMax <= 0) return (int) Math.min(pricedForMode, Integer.MAX_VALUE);
    // Delphi: Round(nPrice div 3 / DuraMax * (DuraMax - Dura)); Round is banker's rounding.
    return (int) Math.rint((pricedForMode / 3) / (double) duraMax * (duraMax - dura));
  }

  /**
   * Places an item on the map without a monster having dropped it — the engine-side of
   * {@code TMapItem} creation used by tests and, later, by NPC and quest scripts.
   */
  public CompletableFuture<GroundItem> spawnGroundItem(
      String itemName, int looks, String mapId, Position position) {
    Objects.requireNonNull(itemName, "itemName");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      int itemId = allocateObjectId();
      int resolvedLooks = itemDatabase.find(itemName).map(StdItem::looks).orElse(looks);
      GroundItem item = new GroundItem(itemId, itemName, resolvedLooks, map.id(), position);
      groundItems.put(itemId, item);
      itemDropTimes.put(itemId, clock.getAsLong());
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(map, position, 0)) emit(players.get(viewerId), appeared);
      return item;
    });
  }

  /** Places a wallet-gold pile on the map; mainly used by protocol tests and future scripts. */
  public CompletableFuture<GroundItem> spawnGroundGold(int amount, String mapId, Position position) {
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    if (amount < 1) throw new IllegalArgumentException("gold amount must be positive");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      GroundItem item = putGoldPile(map, position, amount);
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(map, item.position(), 0)) emit(players.get(viewerId), appeared);
      return item;
    });
  }

  /** Current worn set, used by the adapter to answer with {@code SM_SENDUSEITEMS}. */
  public CompletableFuture<Equipment> equipment(int playerId) {
    return submit(() -> requirePlayer(playerId).equipment);
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

  /**
   * Brings a dead player back on the spot with full HP, mirroring the GM command
   * {@code CmdReAlive} (ObjBase.pas:13998). Returns false when the player was already alive.
   */
  public CompletableFuture<Boolean> revive(int playerId) {
    return submit(() -> revivePlayer(playerId));
  }

  /**
   * {@code TPlayObject.CmdChangeLevel} (ObjBase.pas:10848), the GM {@code @Level} command:
   * {@code m_Abil.Level := _MIN(MAXUPLEVEL, nLevel); HasLevelUp(1)}. The level is set outright,
   * the curve is rebuilt and the client receives the same {@code SM_LEVELUP} it would get from
   * an ordinary level-up.
   */
  public CompletableFuture<Integer> setLevel(int playerId, int level) {
    if (level < 1) throw new IllegalArgumentException("level must be at least one");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int capped = Math.min(LevelExperience.MAX_UP_LEVEL, level);
      Ability naked = player.baseAbility;
      Ability working = player.ability;
      EquipmentBonus bonusBefore = player.bonus;
      Ability relevelled = new Ability(naked.hp(), naked.maxHp(), naked.mp(), naked.maxMp(),
          naked.minDc(), naked.maxDc(), naked.minAc(), naked.maxAc(), capped,
          naked.experience(), LevelExperience.forLevel(capped));
      applyLevelUp(player, relevelled);
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.baseAbility = naked;
        player.ability = working;
        player.bonus = bonusBefore;
        throw failure;
      }
      announceLevelUp(player, player.ability);
      return capped;
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
    regenerateHealthAndSpell();
    makeGhostsOfExpiredCorpses();
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
      int feature, int status, int job, WorldEventSink sink) {
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
        restored.ability(), restored.backpack(), restored.equipment(), job, sink);
    // UsrEngn.pas:2310 restores m_nGold from the character record (HumData.nGold).
    player.gold = restored.gold();
    // UserLogon's test-server block (ObjBase.pas:16360): under g_Config.boTestServer the
    // wallet is topped up to nTestGold (default 0, i.e. a no-op). Delphi does not notify the
    // client here; the notification is deferred until after MapEntered below so the client
    // has its actor before the wallet arrives.
    boolean goldFloored = player.gold < config.testGold();
    if (goldFloored) player.gold = config.testGold();
    // MaxExp is derived state in Delphi (HasLevelUp refreshes it from GetLevelExp), so it is
    // never persisted; rebuild it from the restored level before anything reads it.
    player.baseAbility = new Ability(
        player.baseAbility.hp(), player.baseAbility.maxHp(),
        player.baseAbility.mp(), player.baseAbility.maxMp(),
        player.baseAbility.minDc(), player.baseAbility.maxDc(),
        player.baseAbility.minAc(), player.baseAbility.maxAc(),
        player.baseAbility.level(), player.baseAbility.experience(),
        LevelExperience.forLevel(player.baseAbility.level()));
    // RecalcAbilitys runs once at login so the restored gear is reflected before the client
    // receives its first ability packet. Current HP/MP are carried over untouched: Delphi
    // only refills them on revival, not on login.
    recalculateAbilities(player);
    // UsrEngn.pas:576-600 revives a character that was saved at zero HP before it re-enters
    // the world; the Delphi server relocates it home first, which the single-map PoC cannot
    // do, so the player simply stands up on the restored cell with the classic 14 HP.
    if (!player.ability.alive()) {
      player.ability = player.ability.withHp(Math.min(REVIVE_ON_LOGIN_HP, player.ability.maxHp()));
      player.baseAbility = player.rebase(player.ability);
    }
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
    // TPlayObject login sequence (ObjBase.pas:16572) sends RM_SENDUSEITEMS so the client
    // knows what the character is wearing. RM_WEIGHTCHANGED is not part of that sequence —
    // the Delphi login path only refreshes weight when something actually changes it.
    if (!player.equipment.isEmpty()) {
      emit(player, new WorldEvent.EquipmentSent(player.id, player.equipment));
    }
    // RM_ABILITY is part of the login refresh in the Delphi server. Sending the complete
    // packed ability immediately after the map bootstrap prevents a fresh client from
    // retaining placeholder HP/MP/level values until its first later mutation.
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    WorldEvent appeared = new WorldEvent.ObjectAppeared(player.snapshot());
    for (int viewerId : visibleIds) emit(players.get(viewerId), appeared);
    // The test-gold floor is announced once the client can actually see itself.
    if (goldFloored) emit(player, new WorldEvent.GoldChanged(player.id, player.gold));
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
    // AttackTarget.GetHitStruckDamage only assigns weapon wear when the blow penetrates AC.
    if (damage > 0) damageEquipment(player, EquipmentSlot.WEAPON,
        random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 5) + 2);
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
    if (item.gold()) return pickUpGold(player, item);
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

  /**
   * Gold piles are {@code TMapItem} rows named {@code 金币}. Picking one up calls Delphi's
   * {@code IncGold}: the whole pile is accepted only if it fits within {@code nHumanMaxGold};
   * otherwise the pile remains on the floor and the client action fails.
   */
  private boolean pickUpGold(Player player, GroundItem item) {
    long nextGold = player.gold + item.count();
    if (nextGold > PlayerState.MAX_GOLD) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.WALLET_FULL));
      return false;
    }
    long previousGold = player.gold;
    player.gold = nextGold;
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.gold = previousGold;
      throw failure;
    }
    groundItems.remove(item.id());
    itemDropTimes.remove(item.id());
    emit(player, new WorldEvent.GoldPickedUp(player.id, item, player.gold));
    WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
    for (int viewerId : visibleIds(player.map, item.position(), 0)) emit(players.get(viewerId), hidden);
    return true;
  }

  /**
   * {@code TPlayObject.ClientTakeOnItems} (ObjBase.pas:17072). The Delphi handler locates the
   * bag entry by MakeIndex <em>and</em> a case-insensitive name comparison, validates the slot
   * with {@code CheckUserItems} and the wearer with {@code CheckTakeOnItems}, then swaps any
   * item already in the slot back into the bag before recalculating abilities.
   */
  private boolean equipItem(int playerId, int slotIndex, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.ACTOR_DEAD));
      return false;
    }
    if (!EquipmentSlot.isValidIndex(slotIndex)) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.INVALID_SLOT));
      return false;
    }
    EquipmentSlot slot = EquipmentSlot.fromIndex(slotIndex);
    int bagIndex = findBagItem(player, makeIndex, itemName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.NO_SUCH_ITEM));
      return false;
    }
    BackpackItem candidate = player.backpack.get(bagIndex);
    if (!slot.accepts(candidate.item())) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.SLOT_MISMATCH));
      return false;
    }
    if (!EquipRequirement.check(slot, candidate.item(), requirementView(player),
        wornWeightExcluding(player, slot)).allowed()) {
      emit(player, new WorldEvent.EquipRejected(
          player.id, -1, WorldEvent.EquipRejection.REQUIREMENT_NOT_MET));
      return false;
    }
    // Delphi refuses the whole take-on when the occupant of the slot is locked (n18 = -4).
    BackpackItem displaced = player.equipment.at(slot).orElse(null);
    if (displaced != null && isLockedInPlace(displaced)) {
      emit(player, new WorldEvent.EquipRejected(
          player.id, -4, WorldEvent.EquipRejection.CANNOT_TAKE_OFF_EXISTING));
      return false;
    }
    // A swap needs the freed bag slot, so capacity can only be exceeded when nothing is
    // displaced — which cannot happen, the incoming item already occupies a bag slot.
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    Equipment previousEquipment = player.equipment;
    Ability previousAbility = player.ability;

    player.backpack.remove(bagIndex);
    if (displaced != null) player.backpack.add(displaced);
    player.equipment = player.equipment.with(slot, candidate);
    recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.equipment = previousEquipment;
      player.ability = previousAbility;
      throw failure;
    }
    emitEquipmentChange(player,
        new WorldEvent.ItemEquipped(player.id, slot, candidate, player.feature(), player.featureEx()));
    return true;
  }

  /**
   * {@code TPlayObject.ClientTakeOffItems} (ObjBase.pas:17221): the reverse move, gated on the
   * same lock checks plus bag capacity.
   */
  private boolean unequipItem(int playerId, int slotIndex, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!EquipmentSlot.isValidIndex(slotIndex)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -1, WorldEvent.UnequipRejection.BUSY_OR_INVALID_SLOT));
      return false;
    }
    EquipmentSlot slot = EquipmentSlot.fromIndex(slotIndex);
    BackpackItem worn = player.equipment.at(slot).orElse(null);
    // Delphi reports an empty slot and a MakeIndex/name mismatch through the same path.
    if (worn == null || worn.makeIndex() != makeIndex || !worn.name().equalsIgnoreCase(itemName)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -2, WorldEvent.UnequipRejection.SLOT_EMPTY));
      return false;
    }
    if (isLockedInPlace(worn)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -4, WorldEvent.UnequipRejection.CANNOT_TAKE_OFF));
      return false;
    }
    if (player.backpack.size() >= PlayerState.MAX_BACKPACK_ITEMS) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -3, WorldEvent.UnequipRejection.BACKPACK_FULL));
      return false;
    }

    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    Equipment previousEquipment = player.equipment;
    Ability previousAbility = player.ability;

    player.equipment = player.equipment.without(slot);
    player.backpack.add(worn);
    recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.equipment = previousEquipment;
      player.ability = previousAbility;
      throw failure;
    }
    emitEquipmentChange(player,
        new WorldEvent.ItemUnequipped(player.id, slot, worn, player.feature(), player.featureEx()));
    return true;
  }

  /**
   * {@code TPlayObject.ClientUseItems} (ObjBase.pas:17300) restricted to the drinkable
   * StdModes 0-3 that {@code EatItems} (ObjBase.pas:23324) implements. Books (StdMode 4) and
   * the StdMode 31 unpack action belong to slices that do not exist yet.
   */
  private boolean consumeItem(int playerId, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.ACTOR_DEAD));
      return false;
    }
    int bagIndex = findBagItem(player, makeIndex, itemName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.NO_SUCH_ITEM));
      return false;
    }
    BackpackItem item = player.backpack.get(bagIndex);
    int stdMode = item.item().stdMode();
    if (stdMode > 3) {
      // TODO(verify): StdMode 4 (books/skills) and 31 (unpack) need the skill and container
      // slices; refusing keeps the bag consistent instead of silently eating the item.
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.NOT_CONSUMABLE));
      return false;
    }
    if (player.map.flags().isNoDrug()) {
      emit(player, new WorldEvent.UseItemRejected(
          player.id, WorldEvent.UseItemRejection.MAP_FORBIDS_DRUGS));
      return false;
    }

    // EatItems StdMode 0 Shape<>1/2: the AC/MAC dwords are the HP/MP restore amounts, applied
    // immediately by IncHealthSpell (ObjBase.pas:3615), which clamps at the maxima.
    int restoreHp = stdMode == 0 ? (int) Math.min(item.item().ac(), Integer.MAX_VALUE) : 0;
    int restoreMp = stdMode == 0 ? (int) Math.min(item.item().mac(), Integer.MAX_VALUE) : 0;
    Ability previousAbility = player.ability;
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);

    player.backpack.remove(bagIndex);
    if (restoreHp > 0 || restoreMp > 0) {
      player.ability = player.ability
          .withHp(player.ability.hp() + restoreHp)
          .withMp(player.ability.mp() + restoreMp);
    }
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.ability = previousAbility;
      throw failure;
    }
    int healedHp = player.ability.hp() - previousAbility.hp();
    int healedMp = player.ability.mp() - previousAbility.mp();
    emit(player, new WorldEvent.ItemUsed(player.id, item, healedHp, healedMp));
    if (healedHp != 0 || healedMp != 0) {
      emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
    }
    // ClientUseItems ends in WeightChanged() because the bag just got lighter.
    emitWeight(player);
    return true;
  }

  /**
   * {@code TPlayObject.ClientDropItem} (ObjBase.pas:16213): safe-zone and map-flag gates, then
   * the item lands on the ground exactly like monster loot does.
   */
  private boolean dropBagItem(int playerId, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, itemName, makeIndex, WorldEvent.DropRejection.ACTOR_DEAD));
      return false;
    }
    // Delphi splits at the first space because mailed items append a use counter.
    String wantedName = itemName.indexOf(' ') >= 0
        ? itemName.substring(0, itemName.indexOf(' ')) : itemName;
    if (player.map.flags().isSafeZone()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.SAFE_ZONE));
      return false;
    }
    if (player.map.flags().isNoThrowItem()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.MAP_FORBIDS_DROP));
      return false;
    }
    int bagIndex = findBagItem(player, makeIndex, wantedName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.NO_SUCH_ITEM));
      return false;
    }
    Position dropPosition = findDropPosition(player.map, player.position);
    if (dropPosition == null) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.NO_SPACE));
      return false;
    }

    BackpackItem item = player.backpack.get(bagIndex);
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    player.backpack.remove(bagIndex);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      throw failure;
    }

    int itemId = allocateObjectId();
    GroundItem ground = new GroundItem(
        itemId, item.name(), item.looks(), player.map.id(), dropPosition);
    groundItems.put(itemId, ground);
    itemDropTimes.put(itemId, clock.getAsLong());
    emit(player, new WorldEvent.ItemDropped(player.id, item, ground));
    WorldEvent appeared = new WorldEvent.ItemAppeared(ground);
    for (int viewerId : visibleIds(player.map, dropPosition, 0)) emit(players.get(viewerId), appeared);
    emitWeight(player);
    return true;
  }

  /** Bag lookup by MakeIndex plus {@code CompareText}, as every item command does. */
  private static int findBagItem(Player player, int makeIndex, String itemName) {
    for (int index = 0; index < player.backpack.size(); index++) {
      BackpackItem item = player.backpack.get(index);
      if (item.makeIndex() == makeIndex && item.name().equalsIgnoreCase(itemName)) return index;
    }
    return -1;
  }

  /**
   * The "cannot take off" family of checks shared by take-on and take-off
   * (ObjBase.pas:17238-17262). Only the accessory lock is modelled: the two
   * {@code StdItem.Reserved} bits and {@code InDisableTakeOffList} are server-config state
   * that this migration has no source for yet.
   */
  private static boolean isLockedInPlace(BackpackItem item) {
    // TODO(verify): Reserved bits 2/4 and the DisableTakeOffList come from server config that
    // the Java server does not load; no shipped item sets them, so nothing is locked today.
    return false;
  }

  /**
   * {@code GetUserItemWeitht(nWhere)} (ObjBase.pas:23306): total weight of the worn set,
   * excluding the destination slot and — a Delphi quirk — both hand slots, whatever the
   * destination is.
   */
  private static int wornWeightExcluding(Player player, EquipmentSlot destination) {
    int total = 0;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : player.equipment.inSlotOrder()) {
      EquipmentSlot slot = entry.getKey();
      if (slot == destination || slot.countsTowardHandWeight()) continue;
      total += entry.getValue().item().weight();
    }
    return total;
  }

  private static EquipRequirement.Character requirementView(Player player) {
    Ability ability = player.ability;
    return new EquipRequirement.Character(player.gender, player.job, ability.level(),
        ability.maxDc(), 0, 0, player.maxWearWeight(), player.maxHandWeight());
  }

  /**
   * {@code TBaseObject.RecalcAbilitys} (ObjBase.pas:2818): rebuilds the working ability from
   * the base ability plus the worn set. HP/MP survive the rebuild and are only re-clamped
   * when the new maxima are lower.
   */
  private void recalculateAbilities(Player player) {
    EquipmentBonus bonus = player.equipment.bonus();
    Ability base = player.baseAbility;
    int maxHp = clampWord(base.maxHp() + bonus.hp());
    int maxMp = clampWord(base.maxMp() + bonus.mp());
    player.ability = new Ability(
        Math.min(player.ability.hp(), maxHp),
        maxHp,
        Math.min(player.ability.mp(), maxMp),
        maxMp,
        base.minDc() + bonus.minDc(),
        base.maxDc() + bonus.maxDc(),
        base.minAc() + bonus.minAc(),
        base.maxAc() + bonus.maxAc(),
        base.level(),
        base.experience(),
        base.maxExperience());
    player.bonus = bonus;
    player.revival = equipmentGrantsRevival(player.equipment);
  }

  /**
   * The {@code m_boRevival} half of {@code RecalcAbilitys} (ObjBase.pas:2866/2968/3218-3228/
   * 3271). The loop skips items at zero durability, weapon/right-hand/dress slots contribute
   * through {@code AniCount}, every other slot through {@code Shape}. The dress is included in
   * the flag branch even though {@code ItemDamageRevivalRing} never consumes from it — that
   * asymmetry is in the original and is kept here.
   */
  private static boolean equipmentGrantsRevival(Equipment equipment) {
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : equipment.inSlotOrder()) {
      if (entry.getValue().dura() <= 0) continue;
      StdItem item = entry.getValue().item();
      EquipmentSlot slot = entry.getKey();
      if (slot == EquipmentSlot.WEAPON || slot == EquipmentSlot.RIGHT_HAND
          || slot == EquipmentSlot.DRESS) {
        if (isRevivalShape(item.aniCount())) return true;
      } else if (isRevivalShape(item.shape())) {
        return true;
      }
    }
    return false;
  }

  /** {@code Shape/AniCount in [114, 160, 161, 162]} — the four revival-capable shapes. */
  private static boolean isRevivalShape(int value) {
    return value == 114 || value == 160 || value == 161 || value == 162;
  }

  /**
   * {@code StdItem.Shape = 144} sets {@code m_boUnRevival} on the wearer (ObjBase.pas:3139,
   * the accessory branch — the three hand/dress slots branch on AniCount instead and never
   * set this flag), and {@code TBaseObject.Run} refuses the ring revival when the last hitter
   * carries it. Monsters never wear gear, so only a player attacker can suppress a revival.
   */
  private static boolean preventsRevival(WorldObject attacker) {
    if (!(attacker instanceof Player killer)) return false;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : killer.equipment.inSlotOrder()) {
      if (entry.getKey() == EquipmentSlot.WEAPON
          || entry.getKey() == EquipmentSlot.RIGHT_HAND
          || entry.getKey() == EquipmentSlot.DRESS) continue;
      if (entry.getValue().item().shape() == 144) return true;
    }
    return false;
  }

  private static int clampWord(int value) {
    return Math.max(1, Math.min(value, 0xffff));
  }

  /**
   * The common tail of take-on/take-off: RM_ABILITY, the SM_TAKEON_OK/SM_TAKEOFF_OK reply,
   * FeatureChanged to observers and the weight refresh.
   */
  private void emitEquipmentChange(Player player, WorldEvent change) {
    emit(player, change);
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    emitWeight(player);
    // FeatureChanged() broadcasts the new look to everyone who can see the player.
    WorldObjectSnapshot snapshot = player.snapshot();
    WorldEvent appearance = new WorldEvent.ObjectAppeared(snapshot);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) {
      emit(players.get(viewerId), appearance);
    }
  }

  /** {@code TBaseObject.WeightChanged} -> RM_WEIGHTCHANGED -> {@code SM_WEIGHTCHANGED}. */
  private void emitWeight(Player player) {
    emit(player, new WorldEvent.WeightChanged(
        player.id, player.bagWeight(), player.bonus.wearWeight(), player.bonus.handWeight()));
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
    return random.between(WorldRandom.Stream.DAMAGE, min, max);
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
      wearArmorOnStruck(player);
    }
    broadcastStruck(victim, attacker.id(), damage);
    if (updated.alive()) {
      WorldEvent health = new WorldEvent.HealthChanged(victim.snapshot());
      emitToObserversAndSelf(victim, health);
    } else if (victim instanceof Player player && tryRevivalRing(player, attacker)) {
      // TBaseObject.Run's revival branch fired before Die: the ring ate the blow, the player
      // never actually died and no death/scatter side effects ran. HealthChanged and the
      // green hint were emitted by the branch itself.
    } else {
      handleDeath(victim, attacker);
    }
  }

  /**
   * The HP=0 branch of {@code TBaseObject.Run} (ObjBase.pas:3750-3763). Delphi evaluates it
   * on the next tick after a lethal blow; this engine folds it into the damage pass because
   * death itself was already made synchronous in W14 — the observable message order
   * (struck → health restored) is identical either way.
   *
   * <p>Gates, in Delphi order: the last hitter's {@code m_boUnRevival} (worn Shape 144),
   * {@code m_boRevival} (recalculated from the worn set), and the {@code dwRevivalTime}
   * cooldown (60 seconds, strictly greater-than). On success the ring(s) pay 1000 durability
   * each, HP is refilled to MaxHP and the classic green hint goes out.
   */
  private boolean tryRevivalRing(Player player, WorldObject attacker) {
    if (preventsRevival(attacker)) return false;
    if (!player.revival) return false;
    long now = clock.getAsLong();
    if (now - player.revivalTick <= REVIVAL_COOLDOWN_MILLIS) return false;
    player.revivalTick = now;
    consumeRevivalRings(player);
    player.setAbility(player.ability.withHp(player.ability.maxHp()));
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
    emit(player, new WorldEvent.SystemMessage(player.id, REVIVAL_RECOVER_MESSAGE));
    // The damage pass already persisted HP=0; the ring outcome has to reach the store too,
    // but a store hiccup must not un-revive the player — the periodic save catches up.
    try {
      persist(player);
    } catch (RuntimeException error) {
      LOG.log(Level.WARNING, "revival save failed for " + player.name, error);
    }
    return true;
  }

  /**
   * {@code TBaseObject.ItemDamageRevivalRing} (ObjBase.pas:3625): every worn item whose
   * {@code Shape} is revival-capable — or, for the two hand slots only, whose
   * {@code AnniCount} is — pays exactly 1000 durability. The Delphi loop has no {@code break},
   * so two rings both pay for one revival.
   *
   * <p>Quirks kept verbatim: an item drained to zero is destroyed (deleted from the client
   * via SM_DELITEMS and its slot cleared) rather than returned to the bag; and the
   * RM_DURACHANGE follow-up only fires when the wear crossed a 1000-durability display
   * boundary — Delphi's {@code Round(nDura / 1000)} is banker's rounding, so e.g. 2500 → 1500
   * rounds to 2 on both sides and sends nothing.
   */
  private void consumeRevivalRings(Player player) {
    boolean anyDestroyed = false;
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      BackpackItem worn = player.equipment.at(slot).orElse(null);
      if (worn == null) continue;
      StdItem item = worn.item();
      boolean consumes = isRevivalShape(item.shape())
          || ((slot == EquipmentSlot.WEAPON || slot == EquipmentSlot.RIGHT_HAND)
              && isRevivalShape(item.aniCount()));
      if (!consumes) continue;

      int nDura = worn.dura();
      int tDura = thousandsBucket(nDura);
      nDura -= REVIVAL_RING_DURABILITY_COST;
      if (nDura <= 0) {
        nDura = 0;
        // SendDelItems reads the slot before it is cleared, so the removal message still
        // names the item.
        emit(player, new WorldEvent.ItemsRemoved(player.id, List.of(worn)));
        player.equipment = player.equipment.without(slot);
        anyDestroyed = true;
      } else {
        player.equipment = player.equipment.with(slot, worn.withDura(nDura));
      }
      if (tDura != thousandsBucket(nDura)) {
        emit(player, new WorldEvent.ItemDurabilityChanged(
            player.id, slot, worn.makeIndex(), nDura, worn.duraMax(), nDura == 0));
      }
    }
    if (anyDestroyed) recalculateAbilities(player);
  }

  /** Delphi {@code Round(nDura / 1000)}: banker's rounding of the durability thousands. */
  private static int thousandsBucket(int dura) {
    return (int) Math.rint(dura / 1000.0);
  }

  /** {@code StruckDamage}: dress always wears; every occupied slot also has a 1/8 chance. */
  private void wearArmorOnStruck(Player player) {
    if (player.equipment.isEmpty()) return;
    int wear = random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 10) + 5;
    damageEquipment(player, EquipmentSlot.DRESS, wear);
    // Snapshot the slots because a zero-durability item is removed during iteration.
    List<EquipmentSlot> occupied = player.equipment.inSlotOrder().stream()
        .map(Map.Entry::getKey).toList();
    for (EquipmentSlot slot : occupied) {
      if (random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 8) == 0)
        damageEquipment(player, slot, wear);
    }
  }

  /**
   * Applies instance wear and persists it. At zero, Delphi SendDelItems clears wIndex: the
   * item is destroyed (not moved to the bag), its bonuses disappear, and appearance updates.
   */
  private void damageEquipment(Player player, EquipmentSlot slot, int amount) {
    if (amount <= 0) return;
    BackpackItem worn = player.equipment.at(slot).orElse(null);
    if (worn == null || worn.dura() <= 0) return;
    int nextDura = Math.max(0, worn.dura() - amount);
    Equipment previous = player.equipment;
    Ability previousAbility = player.ability;
    EquipmentBonus previousBonus = player.bonus;
    boolean broken = nextDura == 0;
    player.equipment = broken
        ? player.equipment.without(slot)
        : player.equipment.with(slot, worn.withDura(nextDura));
    if (broken) recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.equipment = previous;
      player.ability = previousAbility;
      player.bonus = previousBonus;
      throw failure;
    }
    emit(player, new WorldEvent.ItemDurabilityChanged(
        player.id, slot, worn.makeIndex(), nextDura, worn.duraMax(), broken));
    if (broken) {
      emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
      emitWeight(player);
      WorldEvent appearance = new WorldEvent.ObjectAppeared(player.snapshot());
      for (int viewerId : visibleIds(player.map, player.position, player.id)) {
        emit(players.get(viewerId), appearance);
      }
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
    if (victim instanceof Player player) {
      // TBaseObject.Die marks the object dead and stamps m_dwDeathTick before anything else,
      // because ScatterBagItems and the RM_DEATH broadcast both observe that state.
      player.diedAt = clock.getAsLong();
      scatterBagItems(player);
    }
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

  /**
   * {@code TPlayObject.ScatterBagItems} (ObjBase.pas:26648) with {@code ItemOfCreat = nil},
   * the {@code g_Config.boDieScatterBag} path taken by {@code Die}: every bag entry has a
   * {@code 1 / nDieScatterBagRate} (default 3) chance to drop within {@code DropWide = 2}
   * cells, and the dropped set is reported back through {@code RM_SENDDELITEMLIST}.
   *
   * <p>Worn gear is untouched here. {@code DropUseItems} does handle equipment, but its
   * default config ({@code boKillByHumanDropUseItem = False}) only fires for monster kills and
   * it depends on {@code StdItem.Reserved} bits that this migration has no source for yet.
   */
  // TODO(verify): DropUseItems (equipment drop on death) needs the StdItem.Reserved bits and
  // the PK level model; both are missing, so only the bag scatters today.
  private void scatterBagItems(Player player) {
    if (player.backpack.isEmpty()) return;
    // Delphi refuses the whole scatter on a NODROPITEM map (m_PEnvir.Flag.boNODROPITEM).
    if (player.map.flags().isNoThrowItem()) return;
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    List<BackpackItem> dropped = new ArrayList<>();
    List<GroundItem> landed = new ArrayList<>();
    // Delphi walks the bag backwards so removals do not disturb the remaining indexes.
    for (int index = player.backpack.size() - 1; index >= 0; index--) {
      if (random.nextInt(WorldRandom.Stream.DEATH_SCATTER, DIE_SCATTER_BAG_RATE) != 0) continue;
      BackpackItem item = player.backpack.get(index);
      Position cell = findDropPosition(player.map, player.position);
      if (cell == null) continue; // DropItemDown failed: the entry stays in the bag.
      int itemId = allocateObjectId();
      GroundItem ground = new GroundItem(itemId, item.name(), item.looks(), player.map.id(), cell);
      landed.add(ground);
      dropped.add(item);
      player.backpack.remove(index);
    }
    if (dropped.isEmpty()) return;
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      LOG.log(Level.WARNING, "death scatter rolled back for " + player.name, failure);
      return;
    }
    for (GroundItem ground : landed) {
      groundItems.put(ground.id(), ground);
      itemDropTimes.put(ground.id(), clock.getAsLong());
      WorldEvent appeared = new WorldEvent.ItemAppeared(ground);
      for (int viewerId : visibleIds(player.map, ground.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
    emit(player, new WorldEvent.ItemsRemoved(player.id, dropped));
  }

  /**
   * {@code TBaseObject.ReAlive} (ObjBase.pas:21199) plus the {@code CmdReAlive} tail
   * (ObjBase.pas:14019) that also refills HP and refreshes the ability block: the player
   * stands up on the same cell and every observer receives {@code SM_ALIVE}.
   */
  private boolean revivePlayer(int playerId) {
    Player player = requirePlayer(playerId);
    if (player.ability.alive()) return false;
    Ability before = player.ability;
    player.diedAt = 0;
    // CmdReAlive sets m_WAbil.HP := m_WAbil.MaxHP; MP is left where it was, as in Delphi.
    player.setAbility(player.ability.withHp(player.ability.maxHp()));
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      throw failure;
    }
    emitToObserversAndSelf(player, new WorldEvent.ObjectRevived(player.snapshot()));
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    return true;
  }

  /**
   * {@code TBaseObject.Run} (ObjBase.pas:3718): HP and MP tick back up on their own clocks.
   * The Delphi counters advance by {@code (now - m_dwHPMPTick) div 20} per pass and fire when
   * they reach {@code nHealthFillTime} (300) / {@code nSpellFillTime} (800) — i.e. every
   * 6 seconds for HP and 16 seconds for MP — restoring {@code MaxHP div 75 + 1} and
   * {@code MaxMP div 18 + 1}. Dead objects regenerate nothing.
   */
  private void regenerateHealthAndSpell() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (player.lastRegenAt == 0) {
        player.lastRegenAt = now;
        continue;
      }
      long elapsed = now - player.lastRegenAt;
      long units = elapsed / HP_MP_TICK_MILLIS;
      // Sub-20ms slivers are left on the clock so a fast tick interval still accumulates,
      // which is what Delphi's integer division against m_dwHPMPTick effectively does.
      if (units <= 0) continue;
      player.lastRegenAt = now - elapsed % HP_MP_TICK_MILLIS;
      if (!player.ability.alive()) continue;
      player.healthTicks += units;
      player.spellTicks += units;
      boolean changed = false;
      if (player.ability.hp() < player.ability.maxHp() && player.healthTicks >= HEALTH_FILL_TICKS) {
        int step = player.ability.maxHp() / 75 + 1;
        player.ability = player.ability.withHp(player.ability.hp() + step);
        player.baseAbility = player.rebase(player.ability);
        player.healthTicks = 0;
        changed = true;
      }
      if (player.ability.mp() < player.ability.maxMp() && player.spellTicks >= SPELL_FILL_TICKS) {
        int step = player.ability.maxMp() / 18 + 1;
        player.ability = player.ability.withMp(player.ability.mp() + step);
        player.baseAbility = player.rebase(player.ability);
        player.spellTicks = 0;
        changed = true;
      }
      // Delphi clears a counter that reached the threshold even when the pool was already full.
      if (player.healthTicks >= HEALTH_FILL_TICKS) player.healthTicks = 0;
      if (player.spellTicks >= SPELL_FILL_TICKS) player.spellTicks = 0;
      if (changed) {
        emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
      }
    }
  }

  /**
   * {@code TBaseObject.Run}'s dead branch (ObjBase.pas:3769): after
   * {@code g_Config.dwMakeGhostTime} (3 minutes) the corpse turns into a ghost, which for a
   * player object means it is removed from the map exactly like a disconnect would.
   */
  private void makeGhostsOfExpiredCorpses() {
    long now = clock.getAsLong();
    List<Player> expired = new ArrayList<>();
    for (Player player : players.values()) {
      if (player.ability.alive() || player.diedAt == 0) continue;
      if (now - player.diedAt > MAKE_GHOST_MILLIS) expired.add(player);
    }
    for (Player player : expired) {
      try {
        leave(player.id);
      } catch (RuntimeException error) {
        LOG.log(Level.WARNING, "ghosting failed for " + player.name, error);
      }
    }
  }

  /**
   * {@code TPlayObject.GetExp} (ObjBase.pas:1843): accumulate, announce, then level while the
   * threshold is met. Delphi only checks once per kill, but a single monster can never award
   * more than one level's worth, so the loop below is the same behaviour with a guard against
   * configured multipliers that could.
   */
  private void awardExperience(Player player, long experience) {
    if (experience <= 0) return;
    Ability before = player.ability;
    EquipmentBonus bonusBefore = player.bonus;
    long nextTotal = before.experience() + experience;
    player.setAbility(before.addExperience(experience));
    List<Ability> reached = new ArrayList<>();
    // Delphi levels up inside GetExp, before the save; the same order is kept here so a
    // storage failure rolls back the level as well as the experience.
    while (player.ability.readyToLevel()) {
      int levelBefore = player.baseAbility.level();
      applyLevelUp(player, player.baseAbility.consumeLevelExperience());
      reached.add(player.ability);
      // A capped character keeps burning overflow experience without gaining levels; stop
      // once the level can no longer move so the loop always terminates.
      if (player.baseAbility.level() == levelBefore) break;
    }
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      player.bonus = bonusBefore;
      throw failure;
    }
    emit(player, new WorldEvent.ExperienceGained(player.id, experience, nextTotal));
    for (Ability level : reached) announceLevelUp(player, level);
  }

  /**
   * {@code TBaseObject.HasLevelUp} (ObjBase.pas:1943): refresh {@code MaxExp}, rebuild the
   * level-derived stats, re-apply equipment and top the pools up.
   */
  private void applyLevelUp(Player player, Ability relevelled) {
    player.baseAbility = LevelAbilities.forLevel(player.job, relevelled.level(), relevelled);
    // RecalcAbilitys re-derives the working ability (m_WAbil) from the new naked values.
    recalculateAbilities(player);
    // HasLevelUp ends with IncHealthSpell(2000, 2000), which clamps at the new maxima.
    player.ability = player.ability.withHp(player.ability.hp() + 2000)
        .withMp(player.ability.mp() + 2000);
    player.baseAbility = player.rebase(player.ability);
  }

  /** The {@code RM_LEVELUP} fan-out: SM_LEVELUP, the full ability block and the new pools. */
  private void announceLevelUp(Player player, Ability reached) {
    emit(player, new WorldEvent.LevelUp(
        player.id, reached.level(), reached.experience(), reached));
    // RM_LEVELUP's handler also refreshes the whole ability block (ObjBase.pas:5584).
    emit(player, new WorldEvent.AbilityChanged(
        player.id, reached, player.gold, player.job, player.weightsAtLevel(reached.level())));
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
  }

  private void dropLoot(Monster monster) {
    for (ItemDrop drop : monster.template.drops()) {
      if (random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.oneIn()) != 0) continue;
      Position dropPosition = findDropPosition(monster.map, monster.position);
      if (dropPosition == null) continue;
      int itemId = allocateObjectId();
      int looks = itemDatabase.find(drop.name())
          .map(StdItem::looks)
          .orElse(drop.looks());
      GroundItem item = new GroundItem(itemId, drop.name(), looks, monster.map.id(), dropPosition);
      groundItems.put(itemId, item);
      itemDropTimes.put(itemId, clock.getAsLong());
      monster.droppedItemIds.add(itemId);
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, dropPosition, 0)) {
        emit(players.get(viewerId), appeared);
      }
    }

    int gold = 0;
    for (MonsterDropTable.GoldDrop drop : monster.template.goldDrops()) {
      if (random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.oneIn()) != 0) continue;
      gold += (drop.count() / 2) + random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.count());
    }
    scatterGold(monster, gold);
  }

  private void scatterGold(Monster monster, int gold) {
    if (gold <= 0) return;
    int remaining = gold;
    for (int pile = 0; pile < MAX_GOLD_DROP_PILES && remaining > 0; pile++) {
      int amount = Math.min(remaining, MON_ONE_DROP_GOLD_COUNT);
      remaining -= amount;
      Position dropPosition = findDropPosition(monster.map, monster.position, GOLD_DROP_RANGE);
      if (dropPosition == null) break;
      GroundItem item = putGoldPile(monster.map, dropPosition, amount);
      monster.droppedItemIds.add(item.id());
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, item.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
  }

  private GroundItem putGoldPile(GameMap map, Position position, int amount) {
    if (amount < 1) throw new IllegalArgumentException("gold amount must be positive");
    for (GroundItem item : groundItems.values()) {
      if (!item.gold() || !item.mapId().equals(map.id()) || !item.position().equals(position)) continue;
      int merged = item.count() + amount;
      if (merged > MON_ONE_DROP_GOLD_COUNT) continue;
      GroundItem updated = item.withCountAndLooks(merged, goldShape(merged));
      groundItems.put(updated.id(), updated);
      itemDropTimes.put(updated.id(), clock.getAsLong());
      return updated;
    }
    int itemId = allocateObjectId();
    GroundItem item = new GroundItem(itemId, GroundItem.GOLD_NAME, goldShape(amount), map.id(), position, amount);
    groundItems.put(itemId, item);
    itemDropTimes.put(itemId, clock.getAsLong());
    return item;
  }

  private static int goldShape(int gold) {
    int shape = 112;
    if (gold >= 30) shape = 113;
    if (gold >= 70) shape = 114;
    if (gold >= 300) shape = 115;
    if (gold >= 1000) shape = 116;
    return shape;
  }

  private Position findDropPosition(GameMap map, Position center) {
    return findDropPosition(map, center, 2);
  }

  private Position findDropPosition(GameMap map, Position center, int maxRadius) {
    if (map.isTerrainWalkable(center)) return center;
    for (int radius = 1; radius <= maxRadius; radius++) {
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
        if (door.status().opened() && (now - door.status().openedAtMillis() >= DOOR_AUTO_CLOSE_MILLIS)) {
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
        Direction dir = Direction.fromCode(random.nextInt(WorldRandom.Stream.SPAWN, 8));
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
      int x = center.x() + random.nextInt(WorldRandom.Stream.SPAWN, 2 * radius + 1) - radius;
      int y = center.y() + random.nextInt(WorldRandom.Stream.SPAWN, 2 * radius + 1) - radius;
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
      // TMonster.Run with m_boNoAttackMode (ObjMon.pas:449) skips the entire
      // target/chase/attack block, so a stationary dummy never even looks for a player.
      if (monster.template.behavior() == MonsterBehavior.STATIONARY) continue;
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
    if (current != null && isAttackTarget(current) && current.map.id().equals(monster.map.id())
        && monster.position.distanceTo(current.position) <= config.viewRange()) {
      return current;
    }
    monster.targetId = 0;
    Player closest = null;
    int closestDistance = Integer.MAX_VALUE;
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      Player candidate = players.get(viewerId);
      if (candidate == null || !isAttackTarget(candidate)) continue;
      int distance = monster.position.distanceTo(candidate.position);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = candidate;
      }
    }
    if (closest != null) monster.targetId = closest.id;
    return closest;
  }

  /**
   * The monster half of {@code TBaseObject.IsAttackTarget} (ObjBase.pas:21370): a creature
   * never picks a player standing in a safe zone. Delphi only tests the *target* here — a
   * monster inside the zone may still be hit by a player who reaches it.
   */
  private static boolean isAttackTarget(Player player) {
    return player.ability.alive() && !player.map.isSafeZone(player.position);
  }

  private void monsterAttack(Monster monster, Player target, long now) {
    if (now - monster.lastAttackAt < monster.template.attackIntervalMillis()) return;
    monster.lastAttackAt = now;
    monster.direction = Direction.toward(monster.position, target.position);
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
    Direction away = Direction.toward(target.position, monster.position);
    Position step = monster.position.translate(away, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, away);
      return;
    }
    Direction left = rotate(away, -1);
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = rotate(away, 1);
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  private void monsterChase(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    monster.lastWalkAt = now;
    Direction direction = Direction.toward(monster.position, target.position);
    Position step = monster.position.translate(direction, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, direction);
      return;
    }
    Direction left = rotate(direction, -1);
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = rotate(direction, 1);
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  /**
   * Turns {@code direction} by {@code steps} eighths clockwise (negative = anticlockwise).
   * Delphi's monster walk helpers retry the two neighbouring compass points when the
   * straight step is blocked; the direction codes are cyclic (DR_UP..DR_UPLEFT = 0..7).
   */
  private static Direction rotate(Direction direction, int steps) {
    return Direction.fromCode(Math.floorMod(direction.code() + steps, 8));
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
    return MoveResult.rejected(player.snapshot(), reason);
  }

  private AttackResult rejectAttack(Player player, WorldEvent.AttackRejection reason) {
    emit(player, new WorldEvent.AttackRejected(player.id, reason));
    return AttackResult.rejected(player.snapshot(), reason);
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

  private static int seedMakeIndex(long highWater) {
    // GetItemNumber wraps at High(Integer)/2-1; a persisted high-water beyond that restarts at 1.
    if (highWater <= 0 || highWater >= Integer.MAX_VALUE / 2 - 1) return 1;
    return (int) highWater + 1;
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
    return modified
        ? new PlayerState(state.characterId(), state.ability(), updated, state.equipment(),
            state.gold())
        : state;
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
    private final int baseFeature;
    private final int status;
    private final WorldEventSink sink;
    private final List<BackpackItem> backpack;
    private final int gender;
    private final int job;
    private Equipment equipment;
    /** {@code m_Abil}: the naked character, before any worn gear is applied. */
    private Ability baseAbility;
    private EquipmentBonus bonus = EquipmentBonus.none();
    private Position position;
    private Direction direction;
    /** {@code m_WAbil}: the working ability including equipment. */
    private Ability ability;
    private long lastAttackAt = Long.MIN_VALUE / 4;
    private long lastSavedAt;
    /** {@code m_dwDeathTick}: 0 while alive, the death timestamp otherwise. */
    private long diedAt;
    /** {@code m_nGold}: the wallet, restored from the character record at login. */
    private long gold;
    /**
     * {@code m_boRevival}: recalculated by {@code RecalcAbilitys} from the worn set — a
     * revival-capable ring/weapon grants the death-defying branch in {@code TBaseObject.Run}.
     */
    private boolean revival;
    /** {@code m_dwRevivalTick}: timestamp of the last ring revival (cooldown start). */
    private long revivalTick;
    /**
     * {@code m_sScriptLable}: the merchant dialog label the player last selected
     * ({@code CM_MERCHANTDLGSELECT}); the repair path branches on it. Empty until a label is
     * chosen, which means "normal repair" — Delphi compares against '@s_repair' only.
     */
    private String merchantLabel = "";
    /** {@code m_dwHPMPTick}: the reference point the regeneration counters advance from. */
    private long lastRegenAt;
    /** {@code m_nHealthTick} / {@code m_nSpellTick}. */
    private long healthTicks;
    private long spellTicks;

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
        Equipment equipment,
        int job,
        WorldEventSink sink) {
      this.id = id;
      this.characterId = characterId;
      this.name = name;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.baseFeature = feature;
      this.status = status;
      this.baseAbility = ability;
      this.ability = ability;
      this.backpack = new ArrayList<>(backpack);
      this.equipment = equipment;
      this.sink = sink;
      // MakeHumanFeature packs hair/dress/weapon appearance; the low bit of each byte is the
      // gender, so the caller's feature value already carries it (Grobal2.pas:2729).
      this.gender = feature == 0 ? 0 : (feature >>> 24) & 1;
      // The job is not part of the Feature word; the gate passes the character record's
      // btJob through so the level curves (RecalcLevelAbilitys) pick the right branch.
      this.job = job;
    }

    /** Total weight carried in the bag — {@code TBaseObject.RecalcBagWeight} (ObjBase.pas:18533). */
    private int bagWeight() {
      int total = 0;
      for (BackpackItem item : backpack) total += item.item().weight();
      return total;
    }

    /**
     * {@code RecalcLevelAbilitys} sets the base limit from job and level, then
     * {@code RecalcAbilitys} adds the worn set's bonuses (ObjBase.pas:1889, 2818).
     */
    private int maxWearWeight() {
      return LevelAbilities.maxWearWeight(job, ability.level()) + bonus.maxWearWeightBonus();
    }

    private int maxHandWeight() {
      return LevelAbilities.maxHandWeight(job, ability.level()) + bonus.maxHandWeightBonus();
    }

    /** The full {@code TAbility} weight block: current totals plus the recalculated maxima. */
    private WeightLimits weights() {
      return weightsAtLevel(ability.level());
    }

    /**
     * The same block for an explicit level. Awarding enough experience to cross several
     * levels at once emits one {@code RM_ABILITY} per level, and each has to carry the
     * limits of *that* level rather than the final one.
     */
    private WeightLimits weightsAtLevel(int level) {
      return new WeightLimits(
          bagWeight(), LevelAbilities.maxWeight(job, level) + bonus.maxWeightBonus(),
          bonus.wearWeight(), LevelAbilities.maxWearWeight(job, level) + bonus.maxWearWeightBonus(),
          bonus.handWeight(), LevelAbilities.maxHandWeight(job, level) + bonus.maxHandWeightBonus());
    }

    /**
     * {@code TBaseObject.GetFeature} (ObjBase.pas:19992) overlaid on the appearance the
     * character domain supplied at login.
     *
     * <p>Delphi rebuilds the whole word from {@code m_UseItems} every time, so an unequipped
     * player ends up with {@code dress = weapon = gender}. This engine instead keeps the
     * caller's byte for a slot that holds nothing, because the character record already
     * carries the dress/weapon shapes chosen at creation and the world has no other source
     * for them. Once a slot is filled the worn {@code Shape} wins, which is what makes a
     * take-on visibly change the avatar.
     */
    private int feature() {
      int dress = equipment.at(EquipmentSlot.DRESS)
          .map(item -> (item.item().shape() * 2 + gender) & 0xff)
          .orElse((baseFeature >>> 24) & 0xff);
      int weapon = equipment.at(EquipmentSlot.WEAPON)
          .map(item -> (item.item().shape() * 2 + gender) & 0xff)
          .orElse((baseFeature >>> 8) & 0xff);
      // Hair and the low race-image byte are never touched by equipment.
      return (dress << 24) | (baseFeature & 0x00ff0000) | (weapon << 8) | (baseFeature & 0xff);
    }

    /**
     * {@code GetFeatureEx} (ObjBase.pas:19982) = {@code MakeWord(HorseType, DressEffType)}.
     * Mounts and dress effects are not modelled, so both halves stay zero.
     */
    private int featureEx() {
      return 0;
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

    /**
     * Combat and experience act on the working ability ({@code m_WAbil}); the durable
     * character record behind it ({@code m_Abil}) has to follow, otherwise a save would
     * write back pre-combat values.
     */
    @Override
    public void setAbility(Ability ability) {
      this.ability = ability;
      this.baseAbility = rebase(ability);
    }

    /**
     * Strips the equipment contribution back out of a working ability so the naked
     * {@code m_Abil} can be persisted and re-derived on the next login.
     *
     * <p>HP/MP are clamped into the naked maxima. Only {@code StdMode 63} charms raise MaxHP
     * and that slot is disabled in the shipped {@code CheckUserItems}, so the clamp cannot
     * actually bite today; it exists so a future HP-granting item degrades predictably
     * instead of tripping the Ability invariants.
     */
    private Ability rebase(Ability working) {
      int maxHp = Math.max(1, working.maxHp() - bonus.hp());
      int maxMp = Math.max(0, working.maxMp() - bonus.mp());
      return new Ability(
          Math.min(working.hp(), maxHp),
          maxHp,
          Math.min(working.mp(), maxMp),
          maxMp,
          Math.max(0, working.minDc() - bonus.minDc()),
          Math.max(0, working.maxDc() - bonus.maxDc()),
          Math.max(0, working.minAc() - bonus.minAc()),
          Math.max(0, working.maxAc() - bonus.maxAc()),
          working.level(),
          working.experience(),
          working.maxExperience());
    }

    private PlayerState state() {
      // Persist the naked ability: worn bonuses are re-derived by RecalcAbilitys on load.
      return new PlayerState(characterId, baseAbility, backpack, equipment, gold);
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, name, WorldObjectType.PLAYER, map.id(), position, direction, feature(), status, ability);
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
