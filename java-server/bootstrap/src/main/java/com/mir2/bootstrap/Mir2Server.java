package com.mir2.bootstrap;

import com.mir2.auth.AuthService;
import com.mir2.character.CharacterService;
import com.mir2.gate.AccessPolicy;
import com.mir2.gate.GateServer;
import com.mir2.gate.LegacyGateHandler;
import com.mir2.gate.SessionRouter;
import com.mir2.persistence.SqliteStore;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.MapInfoLoader;
import com.mir2.world.Mir2MapLoader;
import com.mir2.world.MonGenLoader;
import com.mir2.world.MonsterSpawnDefinition;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.Position;
import com.mir2.world.StartPoint;
import com.mir2.world.WorldEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the process-wide services and their shutdown order. */
public final class Mir2Server implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(Mir2Server.class.getName());

  private final ServerConfig config;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private SqliteStore store;
  private WorldEngine world;
  private GateServer gates;

  public Mir2Server(ServerConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  public void start() throws IOException {
    if (!started.compareAndSet(false, true)) throw new IllegalStateException("server already started");
    if (closed.get()) throw new IllegalStateException("server already closed");

    try {
      Path database = config.database().toAbsolutePath().normalize();
      Path parent = database.getParent();
      if (parent != null) Files.createDirectories(parent);
      store = new SqliteStore("jdbc:sqlite:" + database);

      AuthService auth = new AuthService(store);
      if (config.bootstrapUser() != null && store.find(config.bootstrapUser()).isEmpty()) {
        auth.register(config.bootstrapUser(), config.bootstrapPassword());
        LOG.info(() -> "Created bootstrap account '" + config.bootstrapUser() + "'");
      }

      List<GameMap> worldMaps;
      GameMap initialMap;
      List<MapInfoLoader.RouteLine> pendingRoutes = List.of();
      if (config.mapInfoFile() != null) {
        Path mapInfoFile = config.mapInfoFile().toAbsolutePath().normalize();
        MapInfoLoader.MapInfoDocument mapInfo = MapInfoLoader.load(mapInfoFile);
        mapInfo.diagnostics().forEach(line -> LOG.warning("MapInfo: " + line));
        worldMaps = loadMapsFromMapInfo(mapInfoFile.getParent() == null
            ? Path.of(".") : mapInfoFile.getParent(), mapInfo);
        pendingRoutes = mapInfo.routes();
      } else if (config.mapFile() == null) {
        // No real map was configured. This featureless fallback only exists so the process
        // can boot in tests and smoke runs; a playable server must point MIR2_MAP_FILE at a
        // client .map (see docs/deployment.md).
        LOG.warning("No MIR2_MAP_FILE or MIR2_MAPINFO_FILE configured — falling back to a blank "
            + "generated map. Mount your client's Map directory and set MIR2_MAP_FILE to play "
            + "on the real 比奇省 terrain.");
        worldMaps = List.of(GameMap.empty(config.mapId(), "PoC empty map", 256, 256));
      } else {
        Path mapFile = config.mapFile().toAbsolutePath().normalize();
        if (!Files.isRegularFile(mapFile))
          throw new IllegalArgumentException("MIR2_MAP_FILE does not exist: " + mapFile
              + ". Under Docker Compose this usually means MIR2_CLIENT_MAP_DIR is unset or points"
              + " somewhere without a 0.map: set it to your client's Map directory (it is mounted"
              + " read-only at /maps), or clear MIR2_MAP_FILE to boot on the blank PoC map.");
        worldMaps = List.of(Mir2MapLoader.load(config.mapId(), mapFile));
      }
      initialMap = worldMaps.stream().filter(map -> map.id().equals(config.mapId())).findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "spawn map '" + config.mapId() + "' is not among the loaded maps" + worldMaps.stream()
                  .map(GameMap::id).collect(java.util.stream.Collectors.joining(", ", " [", "]"))));
      Position spawn = new Position(config.spawnX(), config.spawnY());
      // The classic 比奇省 start point (StartPoint.txt's 0 289 618, the fountain square).
      // When the operator did not pin a spawn and the loaded map is the real 比奇省, the
      // naked (10,10) default lands the character in the top-left mountain corner — deep
      // inside tall terrain that draws over the actor ("被地图盖住", only visible after
      // running out) — so boot picks the town square instead. A generated PoC map does not
      // contain the cell and keeps the corner default.
      if (!config.spawnConfigured() && "0".equals(initialMap.id())) {
        Position classicStart = new Position(289, 618);
        if (initialMap.contains(classicStart) && !classicStart.equals(spawn)) {
          LOG.info("Spawn not configured and map '0' looks like the real 比奇省 ("
              + initialMap.width() + "x" + initialMap.height() + ") - using the classic start "
              + "point " + classicStart + " instead of " + spawn
              + "; set MIR2_SPAWN_X/MIR2_SPAWN_Y to override.");
          spawn = classicStart;
        }
      }
      if (!initialMap.contains(spawn))
        throw new IllegalArgumentException("configured spawn lies outside map '" + initialMap.id()
            + "' (" + initialMap.width() + "x" + initialMap.height() + "): " + spawn);
      // A blocked cell is not fatal: logins go through enterPlayerNear, which walks out to the
      // nearest free cell. 289,618 on the real 比奇省 sits by the fountain and can be occupied
      // by scenery in some client revisions, so warn instead of refusing to boot.
      if (!initialMap.isTerrainWalkable(spawn))
        LOG.warning("Configured spawn " + spawn + " is blocked terrain on map '" + initialMap.id()
            + "'; players will enter at the nearest walkable cell.");
      // g_StartPoint (LocalDB.pas:LoadStartPoint): the spawn is a town square, and every start
      // point radiates a safe zone of nSafeZoneSize cells. TBaseObject.IsAttackTarget refuses
      // to let a monster pick a player standing inside one, which is what keeps a freshly
      // created character from being mobbed the moment it logs in.
      initialMap.addStartPoint(new StartPoint(spawn, config.safeZoneSize()));
      world = new WorldEngine(
          new WorldEngine.Config(Duration.ofMillis(config.worldTickMillis()), 12, 10_000,
              900, 5_000, 180_000, 200, config.saveIntervalSeconds() * 1_000L,
              config.testGold()),
          worldMaps,
          store,
          store.itemDatabase(),
          config.worldRandom());
      world.start();
      int routeCount = 0;
      for (MapInfoLoader.RouteLine routeLine : pendingRoutes) {
        try {
          if (world.addRoute(routeLine.toTeleportRoute()).get(5, TimeUnit.SECONDS)) routeCount++;
          else LOG.warning("MapInfo route skipped (unknown map): " + routeLine);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException | TimeoutException error) {
          LOG.log(Level.WARNING, "route registration failed for " + routeLine, error);
        }
      }
      if (config.monGenFile() == null) {
        spawnMonsters(initialMap, spawn);
      } else {
        spawnMonGen(worldMaps, config.monGenFile());
      }
      int npcCount = spawnNpcs(initialMap, spawn);
      final int registeredRoutes = routeCount;

      CharacterService characters = new CharacterService(store);
      LegacyGateHandler.WorldConfig worldConfig = new LegacyGateHandler.WorldConfig(
          world, config.mapId(), spawn, Direction.DOWN);
      gates = new GateServer(
          config.ports(), new SessionRouter(auth, characters), config.gateConfig(), worldConfig,
          new AccessPolicy(new AccessPolicy.Config(config.maxConnectionsPerIp(),
              config.connectionAttemptsPerWindow(),
              Duration.ofSeconds(config.connectionAttemptWindowSeconds()),
              Duration.ofSeconds(config.idleTimeoutSeconds()))));
      gates.start();
      LOG.info(() -> "MIR2 Java server started: login=" + config.ports().login()
          + ", select=" + config.ports().select() + ", game=" + config.ports().game()
          + ", advertisedHost=" + config.advertisedHost() + ", database=" + database
          + ", map=" + initialMap.id() + "(" + initialMap.width() + "x" + initialMap.height() + ")"
          + ", maps=" + worldMaps.size() + ", routes=" + registeredRoutes
          + ", worldTickMs=" + config.worldTickMillis()
          + ", monsters=" + config.monsterCount() + "x" + config.monsterTemplate().name()
          + ", npcs=" + npcCount
          + ", worldSeed=" + (config.worldSeed() == null ? "unseeded" : config.worldSeed()));
    } catch (IOException | RuntimeException error) {
      close();
      throw error;
    }
  }

  /**
   * Places the configured decorative NPCs around the spawn point — the visible half of the
   * NPC slice (TNormNpc stand-ins; dialogues/trading stay on the Market_Def red line). A
   * placement whose cell is blocked or occupied is skipped with a warning rather than
   * failing the boot, exactly like Delphi's AddNpc list handling of an unwalkable cell.
   */
  private int spawnNpcs(GameMap initialMap, Position spawn) {
    int placed = 0;
    for (ServerConfig.NpcPlacement placement : config.npcPlacements()) {
      Position where = new Position(spawn.x() + placement.dx(), spawn.y() + placement.dy());
      if (!initialMap.contains(where)) {
        LOG.warning("NPC '" + placement.name() + "' skipped: " + where
            + " lies outside map '" + initialMap.id() + "'.");
        continue;
      }
      try {
        world.spawnNpc(placement.name(), initialMap.id(), where, placement.appearance(),
            Direction.DOWN).get(5, TimeUnit.SECONDS);
        placed++;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return placed;
      } catch (ExecutionException | TimeoutException error) {
        LOG.warning("NPC '" + placement.name() + "' skipped at " + where + ": "
            + error.getCause());
      }
    }
    return placed;
  }

  /** Loads every {@code <id>.map} referenced by the document, mirroring AddMapInfo's
   *  skip-and-log behaviour for unreadable map files. */
  private static List<GameMap> loadMapsFromMapInfo(Path directory,
      MapInfoLoader.MapInfoDocument mapInfo) throws IOException {
    Map<String, GameMap> unique = new LinkedHashMap<>();
    for (MapInfoLoader.MapDefinition definition : mapInfo.maps()) {
      Path mapFile = directory.resolve(definition.mapFileName() + ".map");
      if (!Files.isRegularFile(mapFile)) {
        LOG.warning("Failure: " + mapFile + " could not be loaded.");
        continue;
      }
      GameMap map = Mir2MapLoader.load(definition.id(), mapFile);
      if (!definition.description().isBlank()) map = map.withTitle(definition.description());
      map = map.withFlags(definition.flags());
      unique.putIfAbsent(map.id(), map);
    }
    if (unique.isEmpty())
      throw new IllegalArgumentException("MapInfo yielded no loadable maps in " + directory);
    return List.copyOf(unique.values());
  }

  /**
   * Loads legacy MonGen rows and registers each as a self-replenishing spawner on every
   * matching map. The world materialises the initial population on the next tick and keeps
   * refilling losses on the row's respawn interval, mirroring {@code TUserEngine.RegenMonsters}.
   */
  private void spawnMonGen(List<GameMap> maps, Path monGenFile) throws IOException {
    int registered = 0;
    for (MonsterSpawnDefinition definition : MonGenLoader.load(monGenFile)) {
      // Captured by the stream lambda; `definition` itself is reassigned by the cap below.
      String mapName = definition.mapName();
      GameMap map = maps.stream()
          .filter(candidate -> mapName.equalsIgnoreCase(candidate.title())
              || mapName.equalsIgnoreCase(candidate.id()))
          .findFirst().orElse(null);
      if (map == null) continue;
      MonsterTemplate template;
      try {
        template = MonsterTemplate.forName(definition.monsterName());
      } catch (IllegalArgumentException unsupported) {
        LOG.warning("Skipping MonGen row with unsupported monster '" + definition.monsterName() + "'");
        continue;
      }
      if (definition.count() > 1_000) {
        LOG.warning("Capping MonGen row for '" + definition.monsterName() + "' at 1000 monsters");
        definition = new MonsterSpawnDefinition(definition.mapName(), definition.x(), definition.y(),
            definition.monsterName(), definition.range(), 1_000, definition.respawnMillis(),
            definition.missionGenRate());
      }
      try {
        world.addSpawner(template, map.id(), definition).get(5, TimeUnit.SECONDS);
        registered++;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException | TimeoutException error) {
        LOG.log(Level.WARNING, "MonGen spawner registration failed for " + definition, error);
      }
    }
    int rows = registered;
    LOG.info(() -> "Loaded MonGen " + monGenFile + ": registered " + rows + " spawners");
  }

  /** Rings monsters around the spawn point so a real client can immediately test the kill loop. */
  private void spawnMonsters(GameMap map, Position spawn) {
    if (config.monsterCount() == 0) return;
    MonsterTemplate template = config.monsterTemplate();
    int placed = 0;
    // Start outside the spawn's safe zone. Monsters may not attack anyone standing in it
    // anyway, but a ring of creatures pressed against the town square is not what the
    // original looks like -- MonGen.txt keeps its spawn points off the start squares.
    int firstRadius = config.safeZoneSize() + 1;
    int maxRadius = Math.max(map.width(), map.height());
    for (int radius = firstRadius; radius < maxRadius && placed < config.monsterCount(); radius++) {
      // Spread the group evenly around the perimeter. Scanning the bounding box row by row
      // (or even walking the ring in order) packs every monster onto a single edge.
      for (Position candidate : spreadAroundRing(spawn, radius, config.monsterCount() - placed)) {
        if (placed >= config.monsterCount()) break;
        if (!map.canWalk(candidate)) continue;
        try {
          world.spawnMonster(template, map.id(), candidate, Direction.DOWN)
              .get(5, TimeUnit.SECONDS);
          placed++;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        } catch (ExecutionException | TimeoutException error) {
          LOG.log(Level.WARNING, "monster spawn failed at " + candidate, error);
        }
      }
    }
    if (placed < config.monsterCount())
      LOG.warning("Only placed " + placed + " of " + config.monsterCount()
          + " monsters: no walkable cells left around " + spawn);
  }

  /**
   * The ring's cells reordered so that taking the first {@code wanted} of them spaces the
   * group evenly around the spawn instead of bunching it against one edge. The remaining
   * cells follow in perimeter order as fallbacks for blocked terrain.
   */
  private static List<Position> spreadAroundRing(Position centre, int radius, int wanted) {
    List<Position> perimeter = ringCells(centre, radius);
    if (wanted <= 0 || wanted >= perimeter.size()) return perimeter;
    List<Position> ordered = new java.util.ArrayList<>(perimeter.size());
    boolean[] taken = new boolean[perimeter.size()];
    for (int i = 0; i < wanted; i++) {
      int index = (int) ((long) i * perimeter.size() / wanted);
      if (taken[index]) continue;
      taken[index] = true;
      ordered.add(perimeter.get(index));
    }
    for (int i = 0; i < perimeter.size(); i++) {
      if (!taken[i]) ordered.add(perimeter.get(i));
    }
    return ordered;
  }

  /** The cells exactly {@code radius} away from {@code centre}, clockwise from the top-left. */
  private static List<Position> ringCells(Position centre, int radius) {
    List<Position> cells = new java.util.ArrayList<>(Math.max(1, radius * 8));
    int low = -radius;
    int high = radius;
    for (int dx = low; dx <= high; dx++) cells.add(new Position(centre.x() + dx, centre.y() + low));
    for (int dy = low + 1; dy <= high; dy++) cells.add(new Position(centre.x() + high, centre.y() + dy));
    for (int dx = high - 1; dx >= low; dx--) cells.add(new Position(centre.x() + dx, centre.y() + high));
    for (int dy = high - 1; dy >= low + 1; dy--) cells.add(new Position(centre.x() + low, centre.y() + dy));
    return cells;
  }

  public boolean isRunning() {
    return gates != null && gates.isRunning()
        && world != null && world.isRunning()
        && !closed.get();
  }

  public void awaitShutdown() throws InterruptedException {
    stopped.await();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (gates != null) gates.close();
    if (world != null) world.close();
    if (store != null) store.close();
    stopped.countDown();
    LOG.info("MIR2 Java server stopped");
  }
}
