package com.mir2.bootstrap;

import com.mir2.gate.GatePorts;
import com.mir2.gate.LegacyGateHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated process configuration loaded from environment variables. */
public record ServerConfig(
    Path database,
    GatePorts ports,
    String advertisedHost,
    String serverName,
    Path mapFile,
    Path mapInfoFile,
    String mapId,
    int spawnX,
    int spawnY,
    int worldTickMillis,
    int monsterCount,
    String monsterKind,
    Path monGenFile,
    String bootstrapUser,
    String bootstrapPassword,
    int maxConnectionsPerIp,
    int connectionBurstLimit1s,
    int connectionBurstLimit3s,
    int idleTimeoutSeconds,
    int saveIntervalSeconds,
    long testGold,
    Long worldSeed,
    int safeZoneSize,
    boolean spawnConfigured,
    List<NpcPlacement> npcPlacements,
    Path disableTakeOffFile,
    com.mir2.world.WorldClock.Mode worldClockMode,
    Path blockIpFile,
    com.mir2.gate.BlockMethod blockMethod,
    int maxClientPacketSize,
    int normalClientPacketSize,
    int maxClientMessagesPerRead,
    boolean kickOnOversizePacket) {

  /**
   * One decorative NPC to stand near the spawn point: a {@code MIR2_NPC_LIST} entry
   * {@code name:appearance:dx:dy}, where {@code appearance} is the Npc.wil sprite index and
   * {@code dx}/{@code dy} the offset from the spawn cell (negative allowed).
   */
  public record NpcPlacement(String name, int appearance, int dx, int dy) {
    public NpcPlacement {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("npc name must not be blank");
      }
      if (appearance < 0 || appearance > 0xffff) {
        throw new IllegalArgumentException("npc appearance must be a 16-bit value");
      }
    }
  }

  /** The builtin default NPCs when {@code MIR2_NPC_LIST} is unset (see deployment.md). */
  public static final List<NpcPlacement> DEFAULT_NPCS = List.of(
      new NpcPlacement("老兵", 0, 5, 0),
      new NpcPlacement("老板", 1, -5, 0),
      new NpcPlacement("商人", 2, 0, -5));

  /** Compatibility constructor for embedded tests and load-test callers. */
  public ServerConfig(Path database, GatePorts ports, String advertisedHost, String serverName,
      Path mapFile, String mapId, int spawnX, int spawnY, int worldTickMillis, int monsterCount,
      String monsterKind, String bootstrapUser, String bootstrapPassword) {
    this(database, ports, advertisedHost, serverName, mapFile, null, mapId, spawnX, spawnY,
        worldTickMillis, monsterCount, monsterKind, null, bootstrapUser, bootstrapPassword,
        50, 20, 40, 900, 600, 0, null, com.mir2.world.StartPoint.DEFAULT_SAFE_ZONE_SIZE,
        true, List.of(), null, com.mir2.world.WorldClock.Mode.SYSTEM,
        null, com.mir2.gate.BlockMethod.DISCONNECT,
        com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_SIZE,
        com.mir2.gate.PacketSizePolicy.DEFAULT_NORMAL_SIZE,
        com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_MESSAGES, true);
  }

  /** Compatibility constructor that also pins the world seed (shadow comparison harness). */
  public ServerConfig(Path database, GatePorts ports, String advertisedHost, String serverName,
      Path mapFile, String mapId, int spawnX, int spawnY, int worldTickMillis, int monsterCount,
      String monsterKind, String bootstrapUser, String bootstrapPassword, Long worldSeed) {
    this(database, ports, advertisedHost, serverName, mapFile, null, mapId, spawnX, spawnY,
        worldTickMillis, monsterCount, monsterKind, null, bootstrapUser, bootstrapPassword,
        50, 20, 40, 900, 600, 0, worldSeed, com.mir2.world.StartPoint.DEFAULT_SAFE_ZONE_SIZE,
        true, List.of(), null, com.mir2.world.WorldClock.Mode.SYSTEM,
        null, com.mir2.gate.BlockMethod.DISCONNECT,
        com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_SIZE,
        com.mir2.gate.PacketSizePolicy.DEFAULT_NORMAL_SIZE,
        com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_MESSAGES, true);
  }

  public ServerConfig {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(ports, "ports");
    advertisedHost = requireText(advertisedHost, "advertised host");
    serverName = requireText(serverName, "server name");
    mapId = requireText(mapId, "map id");
    if (serverName.indexOf('/') >= 0) throw new IllegalArgumentException("server name must not contain '/'");
    if (mapFile != null && mapInfoFile != null)
      throw new IllegalArgumentException("MIR2_MAP_FILE and MIR2_MAPINFO_FILE are mutually exclusive");
    if (spawnX < 0 || spawnX > 0xffff || spawnY < 0 || spawnY > 0xffff)
      throw new IllegalArgumentException("spawn coordinates must be unsigned 16-bit values");
    if (worldTickMillis < 1 || worldTickMillis > 10_000)
      throw new IllegalArgumentException("world tick interval must be between 1 and 10000 milliseconds");
    if (monsterCount < 0 || monsterCount > 1_000)
      throw new IllegalArgumentException("monster count must be between 0 and 1000");
    if (maxConnectionsPerIp < 1 || connectionBurstLimit1s < 1
        || connectionBurstLimit3s < 1 || idleTimeoutSeconds < 1)
      throw new IllegalArgumentException("access-layer limits and timeouts must be positive");
    Objects.requireNonNull(blockMethod, "blockMethod");
    if (normalClientPacketSize < 0)
      throw new IllegalArgumentException("normal client packet size must not be negative");
    if (maxClientPacketSize < 1 || maxClientMessagesPerRead < 1)
      throw new IllegalArgumentException("client packet limits must be positive");
    if (maxClientPacketSize < normalClientPacketSize)
      throw new IllegalArgumentException(
          "MIR2_MAX_CLIENT_PACKET_SIZE must be at least MIR2_NORMAL_CLIENT_PACKET_SIZE");
    if (saveIntervalSeconds < 1)
      throw new IllegalArgumentException("save interval must be a positive number of seconds");
    if (safeZoneSize < 0 || safeZoneSize > 100)
      throw new IllegalArgumentException("safe zone size must be between 0 and 100");
    if (testGold < 0 || testGold > com.mir2.world.PlayerState.MAX_GOLD)
      throw new IllegalArgumentException("test gold must be within 0.."
          + com.mir2.world.PlayerState.MAX_GOLD);
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
    Objects.requireNonNull(npcPlacements, "npcPlacements");
    // MANUAL is reachable only through withWorldClockMode (the shadow harness); the
    // environment parser refuses it, because an operator who set it would get a world whose
    // time never moves.
    Objects.requireNonNull(worldClockMode, "worldClockMode");
  }

  public static ServerConfig fromEnvironment() {
    return from(System.getenv());
  }

  /**
   * Returns a copy with a different start-point safe-zone radius.
   *
   * <p>Test harnesses use this to opt out: they seed the debug monster ring specifically so
   * that creatures stand next to the spawn, which a production-sized safe zone pushes out of
   * reach.
   */
  public ServerConfig withSafeZoneSize(int newSafeZoneSize) {
    return new ServerConfig(database, ports, advertisedHost, serverName, mapFile, mapInfoFile,
        mapId, spawnX, spawnY, worldTickMillis, monsterCount, monsterKind, monGenFile,
        bootstrapUser, bootstrapPassword, maxConnectionsPerIp, connectionBurstLimit1s,
        connectionBurstLimit3s, idleTimeoutSeconds, saveIntervalSeconds, testGold,
        worldSeed, newSafeZoneSize, spawnConfigured, npcPlacements, disableTakeOffFile,
        worldClockMode, blockIpFile, blockMethod, maxClientPacketSize, normalClientPacketSize,
        maxClientMessagesPerRead, kickOnOversizePacket);
  }

  /**
   * Returns a copy whose world clock runs in the given mode. Shadow-comparison harnesses
   * switch the embedded servers to {@link com.mir2.world.WorldClock.Mode#VIRTUAL} so the
   * monster AI cadences become tick-derived and therefore comparable across two processes;
   * a live server keeps {@link com.mir2.world.WorldClock.Mode#SYSTEM}.
   */
  public ServerConfig withWorldClockMode(com.mir2.world.WorldClock.Mode mode) {
    return new ServerConfig(database, ports, advertisedHost, serverName, mapFile, mapInfoFile,
        mapId, spawnX, spawnY, worldTickMillis, monsterCount, monsterKind, monGenFile,
        bootstrapUser, bootstrapPassword, maxConnectionsPerIp, connectionBurstLimit1s,
        connectionBurstLimit3s, idleTimeoutSeconds, saveIntervalSeconds, testGold,
        worldSeed, safeZoneSize, spawnConfigured, npcPlacements, disableTakeOffFile, mode,
        blockIpFile, blockMethod, maxClientPacketSize, normalClientPacketSize,
        maxClientMessagesPerRead, kickOnOversizePacket);
  }

  /**
   * Returns a copy pointed at a different {@code DisableTakeOffList.txt} (W22). The
   * equipment-lock shadow scenario uses this to boot embedded worlds whose list names the
   * very sword the seeded character wears, so {@code ClientTakeOffItems}'s refusal — and the
   * W26 「无法取下物品」 hint — can be scripted over the wire without touching a live
   * server's files. A null path keeps whatever the config already had.
   */
  public ServerConfig withDisableTakeOffFile(Path newDisableTakeOffFile) {
    return new ServerConfig(database, ports, advertisedHost, serverName, mapFile, mapInfoFile,
        mapId, spawnX, spawnY, worldTickMillis, monsterCount, monsterKind, monGenFile,
        bootstrapUser, bootstrapPassword, maxConnectionsPerIp, connectionBurstLimit1s,
        connectionBurstLimit3s, idleTimeoutSeconds, saveIntervalSeconds, testGold,
        worldSeed, safeZoneSize, spawnConfigured, npcPlacements, newDisableTakeOffFile,
        worldClockMode, blockIpFile, blockMethod, maxClientPacketSize, normalClientPacketSize,
        maxClientMessagesPerRead, kickOnOversizePacket);
  }

  /**
   * The time source for the world engine. {@code MIR2_WORLD_CLOCK=virtual} derives every
   * cadence (monster walk/attack intervals, respawns, regeneration, PK decay, door sweeps,
   * day/night) from the tick counter instead of the host clock, which is what lets two
   * processes be 对拍'd with monsters that move. Production default is the wall clock.
   */
  public com.mir2.world.WorldClock worldClock() {
    return switch (worldClockMode) {
      case VIRTUAL -> com.mir2.world.WorldClock.virtual(worldTickMillis);
      // MANUAL: world time only moves when the harness asks for it (`@tick N`), so a
      // monster's Nth AI decision happens after exactly N pumped ticks on both servers.
      case MANUAL -> com.mir2.world.WorldClock.manual(worldTickMillis);
      case SYSTEM -> com.mir2.world.WorldClock.system();
    };
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
        nullablePath(environment.get("MIR2_MAPINFO_FILE")),
        value(environment, "MIR2_MAP_ID", "0"),
        nonNegativeInt(environment, "MIR2_SPAWN_X", 10),
        nonNegativeInt(environment, "MIR2_SPAWN_Y", 10),
        positiveInt(environment, "MIR2_WORLD_TICK_MS", 50),
        nonNegativeInt(environment, "MIR2_MONSTER_COUNT", 0),
        value(environment, "MIR2_MONSTER_KIND", "chicken"),
        nullablePath(environment.get("MIR2_MONGEN_FILE")),
        nullable(environment.get("MIR2_BOOTSTRAP_USER")),
        nullable(environment.get("MIR2_BOOTSTRAP_PASSWORD")),
        // nMaxConnOfIPaddr: 50 on RunGate (10 on LoginGate/SelGate, but the three gates share
        // one policy here and 50 is what keeps the 50-bot rehearsal admissible from loopback).
        positiveInt(environment, "MIR2_MAX_CONNECTIONS_PER_IP", 50),
        // nIPCountLimit1 / nIPCountLimit2 (GateShare.pas:31-32) over Delphi's fixed 1s / 3s
        // tumbling windows. The window spans are constants in the original, not config.
        positiveInt(environment, "MIR2_CONNECTION_BURST_LIMIT_1S", 20),
        positiveInt(environment, "MIR2_CONNECTION_BURST_LIMIT_3S", 40),
        positiveInt(environment, "MIR2_IDLE_TIMEOUT_SECONDS", 900),
        // g_Config.dwSaveHumanRcdTime defaults to 10 minutes (M2Share.pas).
        positiveInt(environment, "MIR2_SAVE_INTERVAL_SECONDS", 600),
        // g_Config.nTestGold defaults to 0 under boTestServer (ObjBase.pas:16360): a login
        // wallet floor for test servers, kept at the shipped no-op default.
        nonNegativeLong(environment, "MIR2_TEST_GOLD", 0),
        // Unset (the production default) = one shared, randomly seeded generator, exactly
        // like Delphi's global Random. Setting it splits randomness into independent
        // per-subsystem streams derived from this seed so two servers can be 对拍'd with
        // monsters alive; see WorldRandom.
        nullableLong(environment, "MIR2_WORLD_SEED"),
        // g_Config.nSafeZoneSize (!Setup.txt SafeZoneSize=10): the radius around every
        // StartPoint.txt entry in which monsters may not choose a player as their target.
        nonNegativeInt(environment, "MIR2_SAFE_ZONE_SIZE",
            com.mir2.world.StartPoint.DEFAULT_SAFE_ZONE_SIZE),
        environment.containsKey("MIR2_SPAWN_X") || environment.containsKey("MIR2_SPAWN_Y"),
        parseNpcList(environment.get("MIR2_NPC_LIST")),
        nullablePath(environment.get("MIR2_DISABLE_TAKEOFF_FILE")),
        // Production reads the host clock exactly as Delphi reads GetTickCount. 'virtual'
        // derives world time from the tick counter so two processes agree on every cadence;
        // it is a determinism tool for 影子对拍, not a production mode (see WorldClock).
        worldClockMode(environment.get("MIR2_WORLD_CLOCK")),
        // BlockIPList.txt (GateShare.pas:86) — permanent, prefix-matched IP bans.
        nullablePath(environment.get("MIR2_BLOCK_IP_FILE")),
        // BlockMethod (GateShare.pas:63): what to do with an address that trips a limit.
        com.mir2.gate.BlockMethod.parse(environment.get("MIR2_BLOCK_METHOD")),
        // RunGate's client read-burst guard (GateShare.pas:96-100).
        positiveInt(environment, "MIR2_MAX_CLIENT_PACKET_SIZE",
            com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_SIZE),
        nonNegativeInt(environment, "MIR2_NORMAL_CLIENT_PACKET_SIZE",
            com.mir2.gate.PacketSizePolicy.DEFAULT_NORMAL_SIZE),
        positiveInt(environment, "MIR2_MAX_CLIENT_MESSAGES_PER_READ",
            com.mir2.gate.PacketSizePolicy.DEFAULT_MAX_MESSAGES),
        // bokickOverPacketSize = True: an oversized read closes the connection. False keeps
        // it open but still discards the bytes, exactly as Delphi does.
        booleanValue(environment, "MIR2_KICK_ON_OVERSIZE_PACKET", true));
  }

  /** The admission guard for all three gates: IP bans plus Delphi's three connection limits. */
  public com.mir2.gate.AccessPolicy accessPolicy(com.mir2.gate.BlockIpList blockList) {
    return new com.mir2.gate.AccessPolicy(
        new com.mir2.gate.AccessPolicy.Config(maxConnectionsPerIp, connectionBurstLimit1s,
            connectionBurstLimit3s, java.time.Duration.ofSeconds(idleTimeoutSeconds), blockMethod),
        blockList);
  }

  /** RunGate's per-read size/count guard, applied to the game gate. */
  public com.mir2.gate.PacketSizePolicy packetSizePolicy() {
    return new com.mir2.gate.PacketSizePolicy(normalClientPacketSize, maxClientPacketSize,
        maxClientMessagesPerRead, kickOnOversizePacket);
  }

  /**
   * Parses {@code MIR2_WORLD_CLOCK}: {@code system} (default, the host clock) or
   * {@code virtual} (tick-derived world time). {@code manual} is rejected — a live server
   * has nobody to advance it.
   */
  static com.mir2.world.WorldClock.Mode worldClockMode(String raw) {
    if (raw == null || raw.isBlank()) return com.mir2.world.WorldClock.Mode.SYSTEM;
    String normalised = raw.trim().toUpperCase(java.util.Locale.ROOT);
    com.mir2.world.WorldClock.Mode mode;
    try {
      mode = com.mir2.world.WorldClock.Mode.valueOf(normalised);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "MIR2_WORLD_CLOCK must be 'system' or 'virtual' but was '" + raw.trim() + "'", unknown);
    }
    if (mode == com.mir2.world.WorldClock.Mode.MANUAL) {
      throw new IllegalArgumentException(
          "MIR2_WORLD_CLOCK=manual is not a server mode; manual clocks are advanced by a test harness");
    }
    return mode;
  }

  /**
   * Parses {@code MIR2_NPC_LIST} ("name:appearance:dx:dy," repeated). An unset or blank
   * value yields {@link #DEFAULT_NPCS}; the explicit value "none" disables the decorative
   * NPCs entirely.
   */
  static List<NpcPlacement> parseNpcList(String raw) {
    if (raw == null || raw.isBlank()) return DEFAULT_NPCS;
    String trimmed = raw.trim();
    if ("none".equalsIgnoreCase(trimmed)) return List.of();
    java.util.ArrayList<NpcPlacement> placements = new java.util.ArrayList<>();
    for (String entry : trimmed.split(",")) {
      if (entry.isBlank()) continue;
      String[] fields = entry.trim().split(":", -1);
      if (fields.length != 4) {
        throw new IllegalArgumentException(
            "MIR2_NPC_LIST entries must be name:appearance:dx:dy but got '" + entry.trim() + "'");
      }
      try {
        placements.add(new NpcPlacement(fields[0].trim(), Integer.parseInt(fields[1].trim()),
            Integer.parseInt(fields[2].trim()), Integer.parseInt(fields[3].trim())));
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException(
            "MIR2_NPC_LIST entry '" + entry.trim() + "' has a non-numeric field", error);
      }
    }
    return List.copyOf(placements);
  }

  /**
   * The randomness policy for the world engine: unseeded (Delphi's shared global generator)
   * unless {@code MIR2_WORLD_SEED} pins it, in which case each subsystem draws from its own
   * stream so two processes stay in step.
   */
  public com.mir2.world.WorldRandom worldRandom() {
    return worldSeed == null
        ? com.mir2.world.WorldRandom.unseeded()
        : com.mir2.world.WorldRandom.seeded(worldSeed);
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

  /** Parses a boolean switch, accepting Delphi's {@code TRUE}/{@code FALSE} spellings. */
  private static boolean booleanValue(Map<String, String> environment, String key, boolean fallback) {
    String raw = environment.get(key);
    if (raw == null || raw.isBlank()) return fallback;
    return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "true", "yes", "1", "on" -> true;
      case "false", "no", "0", "off" -> false;
      default -> throw new IllegalArgumentException(key + " must be a boolean (true/false)");
    };
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

  private static long nonNegativeLong(Map<String, String> environment, String key, long fallback) {
    String raw = value(environment, key, Long.toString(fallback));
    try {
      long result = Long.parseLong(raw);
      if (result < 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be a non-negative integer", error);
    }
  }

  private static Long nullableLong(Map<String, String> environment, String key) {
    String raw = nullable(environment.get(key));
    if (raw == null) return null;
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be a 64-bit integer seed", error);
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
