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
        worldMaps = List.of(GameMap.empty(config.mapId(), "PoC empty map", 256, 256));
      } else {
        Path mapFile = config.mapFile().toAbsolutePath().normalize();
        worldMaps = List.of(Mir2MapLoader.load(config.mapId(), mapFile));
      }
      initialMap = worldMaps.stream().filter(map -> map.id().equals(config.mapId())).findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "spawn map '" + config.mapId() + "' is not among the loaded maps" + worldMaps.stream()
                  .map(GameMap::id).collect(java.util.stream.Collectors.joining(", ", " [", "]"))));
      Position spawn = new Position(config.spawnX(), config.spawnY());
      if (!initialMap.isTerrainWalkable(spawn))
        throw new IllegalArgumentException("configured spawn is outside the map or blocked: " + spawn);
      world = new WorldEngine(
          new WorldEngine.Config(Duration.ofMillis(config.worldTickMillis()), 12, 10_000,
              900, 5_000, 180_000, 200, config.saveIntervalSeconds() * 1_000L),
          worldMaps,
          store,
          store.itemDatabase());
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
          + ", monsters=" + config.monsterCount() + "x" + config.monsterTemplate().name());
    } catch (IOException | RuntimeException error) {
      close();
      throw error;
    }
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
      GameMap map = maps.stream()
          .filter(candidate -> definition.mapName().equalsIgnoreCase(candidate.title())
              || definition.mapName().equalsIgnoreCase(candidate.id()))
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
    for (int radius = 2; radius < Math.max(map.width(), map.height()) && placed < config.monsterCount(); radius++) {
      for (int dx = -radius; dx <= radius && placed < config.monsterCount(); dx++) {
        for (int dy = -radius; dy <= radius && placed < config.monsterCount(); dy++) {
          if (Math.abs(dx) != radius && Math.abs(dy) != radius) continue;
          Position candidate = new Position(spawn.x() + dx, spawn.y() + dy);
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
    }
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
