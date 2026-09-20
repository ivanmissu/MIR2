package com.mir2.bootstrap;

import com.mir2.gate.GatePorts;
import com.mir2.gate.LegacyGateHandler;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Validated process configuration loaded from environment variables. */
public record ServerConfig(
    Path database,
    GatePorts ports,
    String advertisedHost,
    String serverName,
    Path mapFile,
    String mapId,
    int spawnX,
    int spawnY,
    int worldTickMillis,
    int monsterCount,
    String monsterKind,
    Path monGenFile,
    Path mapDirectory,
    Path mapRoutesFile,
    String bootstrapUser,
    String bootstrapPassword,
    int maxConnectionsPerIp,
    int connectionAttemptsPerWindow,
    int connectionAttemptWindowSeconds,
    int idleTimeoutSeconds,
    int saveIntervalSeconds) {

  /** Compatibility constructor for embedded tests and load-test callers. */
  public ServerConfig(Path database, GatePorts ports, String advertisedHost, String serverName,
      Path mapFile, String mapId, int spawnX, int spawnY, int worldTickMillis, int monsterCount,
      String monsterKind, String bootstrapUser, String bootstrapPassword) {
    this(database, ports, advertisedHost, serverName, mapFile, mapId, spawnX, spawnY,
        worldTickMillis, monsterCount, monsterKind, null, null, null, bootstrapUser, bootstrapPassword,
        128, 300, 60, 900, 600);
  }

  public ServerConfig {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(ports, "ports");
    advertisedHost = requireText(advertisedHost, "advertised host");
    serverName = requireText(serverName, "server name");
    mapId = requireText(mapId, "map id");
    if (serverName.indexOf('/') >= 0) throw new IllegalArgumentException("server name must not contain '/'");
    if (spawnX < 0 || spawnX > 0xffff || spawnY < 0 || spawnY > 0xffff)
      throw new IllegalArgumentException("spawn coordinates must be unsigned 16-bit values");
    if (worldTickMillis < 1 || worldTickMillis > 10_000)
      throw new IllegalArgumentException("world tick interval must be between 1 and 10000 milliseconds");
    if (monsterCount < 0 || monsterCount > 1_000)
      throw new IllegalArgumentException("monster count must be between 0 and 1000");
    if (maxConnectionsPerIp < 1 || connectionAttemptsPerWindow < 1
        || connectionAttemptWindowSeconds < 1 || idleTimeoutSeconds < 1)
      throw new IllegalArgumentException("access-layer limits and timeouts must be positive");
    if (saveIntervalSeconds < 1)
      throw new IllegalArgumentException("save interval must be a positive number of seconds");
    monsterKind = requireText(monsterKind, "monster kind");
    try {
      com.mir2.world.MonsterTemplate.forName(monsterKind);
    } catch (IllegalArgumentException unsupported) {
      throw new IllegalArgumentException(
          "monster kind must be a supported template name (e.g. chicken, orc, scarecrow)", unsupported);
    }
    if ((bootstrapUser == null) != (bootstrapPassword == null))
      throw new IllegalArgumentException("bootstrap user and password must be configured together");
    if (bootstrapUser != null && (bootstrapUser.isBlank() || bootstrapPassword.isEmpty()))
      throw new IllegalArgumentException("bootstrap credentials must not be blank");
  }

  public static ServerConfig fromEnvironment() {
    return from(System.getenv());
  }

  static ServerConfig from(Map<String, String> environment) {
    return new ServerConfig(
        Path.of(value(environment, "MIR2_DATABASE", "data/mir2.db")),
        new GatePorts(
            port(environment, "MIR2_LOGIN_PORT", GatePorts.DEFAULT_LOGIN),
            port(environment, "MIR2_SELECT_PORT", GatePorts.DEFAULT_SELECT),
            port(environment, "MIR2_GAME_PORT", GatePorts.DEFAULT_GAME)),
        value(environment, "MIR2_ADVERTISED_HOST", "127.0.0.1"),
        value(environment, "MIR2_SERVER_NAME", "MIR2"),
        nullablePath(environment.get("MIR2_MAP_FILE")),
        value(environment, "MIR2_MAP_ID", "0"),
        nonNegativeInt(environment, "MIR2_SPAWN_X", 10),
        nonNegativeInt(environment, "MIR2_SPAWN_Y", 10),
        positiveInt(environment, "MIR2_WORLD_TICK_MS", 50),
        nonNegativeInt(environment, "MIR2_MONSTER_COUNT", 0),
        value(environment, "MIR2_MONSTER_KIND", "chicken"),
        nullablePath(environment.get("MIR2_MONGEN_FILE")),
        nullablePath(environment.get("MIR2_MAP_DIRECTORY")),
        nullablePath(environment.get("MIR2_MAP_ROUTES_FILE")),
        nullable(environment.get("MIR2_BOOTSTRAP_USER")),
        nullable(environment.get("MIR2_BOOTSTRAP_PASSWORD")),
        positiveInt(environment, "MIR2_MAX_CONNECTIONS_PER_IP", 128),
        positiveInt(environment, "MIR2_CONNECTION_ATTEMPTS_PER_WINDOW", 300),
        positiveInt(environment, "MIR2_CONNECTION_ATTEMPT_WINDOW_SECONDS", 60),
        positiveInt(environment, "MIR2_IDLE_TIMEOUT_SECONDS", 900),
        // g_Config.dwSaveHumanRcdTime defaults to 10 minutes (M2Share.pas).
        positiveInt(environment, "MIR2_SAVE_INTERVAL_SECONDS", 600));
  }

  /** Resolves the configured melee monster used to populate the PoC map. */
  public com.mir2.world.MonsterTemplate monsterTemplate() {
    return com.mir2.world.MonsterTemplate.forName(monsterKind);
  }

  public LegacyGateHandler.Config gateConfig() {
    return new LegacyGateHandler.Config(advertisedHost, ports.select(), ports.game(), serverName);
  }

  private static int port(Map<String, String> environment, String key, int fallback) {
    String raw = value(environment, key, Integer.toString(fallback));
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be a TCP port", error);
    }
  }

  private static String value(Map<String, String> environment, String key, String fallback) {
    String value = environment.get(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static int positiveInt(Map<String, String> environment, String key, int fallback) {
    int result = nonNegativeInt(environment, key, fallback);
    if (result == 0) throw new IllegalArgumentException(key + " must be a positive integer");
    return result;
  }

  private static int nonNegativeInt(Map<String, String> environment, String key, int fallback) {
    String raw = value(environment, key, Integer.toString(fallback));
    try {
      int result = Integer.parseInt(raw);
      if (result < 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be a non-negative integer", error);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static Path nullablePath(String value) {
    String normalized = nullable(value);
    return normalized == null ? null : Path.of(normalized);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }
}
