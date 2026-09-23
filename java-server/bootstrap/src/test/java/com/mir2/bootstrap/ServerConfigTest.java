package com.mir2.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
  @Test
  void defaultsAreRunnableLocally() {
    ServerConfig config = ServerConfig.from(Map.of());
    assertEquals(Path.of("data/mir2.db"), config.database());
    assertEquals(7000, config.ports().login());
    assertEquals(7100, config.ports().select());
    assertEquals(7200, config.ports().game());
    assertEquals("127.0.0.1", config.advertisedHost());
    assertNull(config.mapFile());
    assertEquals("0", config.mapId());
    assertEquals(10, config.spawnX());
    assertEquals(10, config.spawnY());
    assertEquals(50, config.worldTickMillis());
    assertEquals(0, config.monsterCount());
    assertEquals("鸡", config.monsterTemplate().name());
    assertEquals(600, config.saveIntervalSeconds());
    assertNull(config.bootstrapUser());
    // !Setup.txt SafeZoneSize=10.
    assertEquals(10, config.safeZoneSize());
  }

  @Test
  void safeZoneSizeMatchesSetupTxtAndIsOverridable() {
    assertEquals(10, ServerConfig.from(Map.of()).safeZoneSize());
    assertEquals(3, ServerConfig.from(Map.of("MIR2_SAFE_ZONE_SIZE", "3")).safeZoneSize());
    // A zero radius is legal: it turns the start-point protection off without disabling maps
    // that carry the boSAFE flag outright.
    assertEquals(0, ServerConfig.from(Map.of("MIR2_SAFE_ZONE_SIZE", "0")).safeZoneSize());
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_SAFE_ZONE_SIZE", "-1")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_SAFE_ZONE_SIZE", "101")));
  }

  @Test
  void mapInfoFileIsConfigurableAndConflictsWithTheSingleMapOverride() {
    ServerConfig config = ServerConfig.from(Map.of("MIR2_MAPINFO_FILE", "envir/MapInfo.txt"));
    assertEquals(Path.of("envir/MapInfo.txt"), config.mapInfoFile());
    assertNull(config.mapFile());
    assertThrows(IllegalArgumentException.class, () -> ServerConfig.from(Map.of(
        "MIR2_MAP_FILE", "0.map",
        "MIR2_MAPINFO_FILE", "envir/MapInfo.txt")));
  }

  @Test
  void firstTenMonsterKindsAndSaveIntervalAreConfigurable() {
    ServerConfig config = ServerConfig.from(Map.of(
        "MIR2_MONSTER_KIND", "scarecrow",
        "MIR2_SAVE_INTERVAL_SECONDS", "60"));
    assertEquals("稻草人", config.monsterTemplate().name());
    assertEquals(60, config.saveIntervalSeconds());
    assertEquals("鹿", ServerConfig.from(Map.of("MIR2_MONSTER_KIND", "鹿")).monsterTemplate().name());
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_SAVE_INTERVAL_SECONDS", "0")));
  }

  @Test
  void testGoldFloorsTheLoginWalletAndRejectsInvalidValues() {
    assertEquals(5_000, ServerConfig.from(Map.of("MIR2_TEST_GOLD", "5000")).testGold());
    // The shipped default is g_Config.nTestGold = 0: a no-op floor.
    assertEquals(0, ServerConfig.from(Map.of()).testGold());
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_TEST_GOLD", "-1")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_TEST_GOLD", Long.toString(10_000_001L))));
  }

  @Test
  void environmentOverridesAllDeploymentValues() {
    ServerConfig config = ServerConfig.from(Map.ofEntries(
        Map.entry("MIR2_DATABASE", "/tmp/custom.db"),
        Map.entry("MIR2_LOGIN_PORT", "17000"),
        Map.entry("MIR2_SELECT_PORT", "17100"),
        Map.entry("MIR2_GAME_PORT", "17200"),
        Map.entry("MIR2_ADVERTISED_HOST", "192.0.2.10"),
        Map.entry("MIR2_SERVER_NAME", "TestServer"),
        Map.entry("MIR2_MAP_FILE", "/srv/mir/maps/0.map"),
        Map.entry("MIR2_MAP_ID", "0-test"),
        Map.entry("MIR2_SPAWN_X", "100"),
        Map.entry("MIR2_SPAWN_Y", "200"),
        Map.entry("MIR2_WORLD_TICK_MS", "25"),
        Map.entry("MIR2_MONSTER_COUNT", "8"),
        Map.entry("MIR2_MONSTER_KIND", "orc"),
        Map.entry("MIR2_BOOTSTRAP_USER", "admin"),
        Map.entry("MIR2_BOOTSTRAP_PASSWORD", "secret")));
    assertEquals(17000, config.ports().login());
    assertEquals("192.0.2.10", config.advertisedHost());
    assertEquals(Path.of("/srv/mir/maps/0.map"), config.mapFile());
    assertEquals("0-test", config.mapId());
    assertEquals(100, config.spawnX());
    assertEquals(200, config.spawnY());
    assertEquals(25, config.worldTickMillis());
    assertEquals(8, config.monsterCount());
    assertEquals("半兽人", config.monsterTemplate().name());
    assertEquals("admin", config.bootstrapUser());
  }

  @Test
  void worldClockDefaultsToTheHostClockAndAcceptsVirtual() {
    // Production must keep reading the host clock, exactly like Delphi's GetTickCount.
    ServerConfig wall = ServerConfig.from(Map.of());
    assertEquals(com.mir2.world.WorldClock.Mode.SYSTEM, wall.worldClockMode());
    assertFalse(wall.worldClock().isVirtual());

    ServerConfig virtual = ServerConfig.from(Map.of("MIR2_WORLD_CLOCK", "virtual"));
    assertEquals(com.mir2.world.WorldClock.Mode.VIRTUAL, virtual.worldClockMode());
    assertTrue(virtual.worldClock().advancesWithEngineTick());
    // The clock's tick interval must track the configured world tick, otherwise "same
    // number of ticks" would stop meaning "same world time" across two servers.
    assertEquals(virtual.worldTickMillis(), virtual.worldClock().tickMillis());

    // Case-insensitive, like the other enum-ish settings.
    assertEquals(com.mir2.world.WorldClock.Mode.VIRTUAL,
        ServerConfig.from(Map.of("MIR2_WORLD_CLOCK", "VIRTUAL")).worldClockMode());
  }

  @Test
  void manualWorldClocksAreRefusedFromTheEnvironmentButReachableForHarnesses() {
    // An operator who set this would get a world whose time never moves, so the env parser
    // refuses it; the shadow harness reaches MANUAL through withWorldClockMode instead.
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_WORLD_CLOCK", "manual")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_WORLD_CLOCK", "wall-clock")));

    ServerConfig manual = ServerConfig.from(Map.of())
        .withWorldClockMode(com.mir2.world.WorldClock.Mode.MANUAL);
    assertEquals(com.mir2.world.WorldClock.Mode.MANUAL, manual.worldClockMode());
    assertFalse(manual.worldClock().advancesWithEngineTick());
  }

  @Test
  void worldSeedIsUnsetByDefaultAndSplitsTheStreamsWhenPinned() {
    // Production default: no seed => one shared generator, i.e. Delphi's global Random.
    ServerConfig unseeded = ServerConfig.from(Map.of());
    assertNull(unseeded.worldSeed());
    assertFalse(unseeded.worldRandom().isSeeded());

    ServerConfig seeded = ServerConfig.from(Map.of("MIR2_WORLD_SEED", "20260922"));
    assertEquals(20260922L, seeded.worldSeed());
    assertTrue(seeded.worldRandom().isSeeded());
    assertEquals(20260922L, seeded.worldRandom().seed().orElseThrow());

    // Negative seeds are legal (it is a 64-bit seed, not a count).
    assertEquals(-5L, ServerConfig.from(Map.of("MIR2_WORLD_SEED", "-5")).worldSeed());
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_WORLD_SEED", "not-a-number")));
  }

  @Test
  void trainerDummyIsASelectableMonsterKind() {
    // The shadow harness boots worlds with --monster-kind trainer, so the name must resolve.
    assertEquals("木桩",
        ServerConfig.from(Map.of("MIR2_MONSTER_KIND", "trainer")).monsterTemplate().name());
    assertEquals("木桩",
        ServerConfig.from(Map.of("MIR2_MONSTER_KIND", "木桩")).monsterTemplate().name());
  }

  @Test
  void partialCredentialsAndInvalidPortsFailFast() {
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_BOOTSTRAP_USER", "admin")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_LOGIN_PORT", "wrong")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_WORLD_TICK_MS", "0")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_SPAWN_X", "-1")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_MONSTER_KIND", "dragon")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_MONSTER_COUNT", "5000")));
  }

  @Test
  void spawnIsOnlyMarkedConfiguredWhenAnEnvironmentVariableNamesIt() {
    // The classic 比奇省 start-point auto-pick in Mir2Server must not override an operator
    // who pinned a spawn, and must not fire for harnesses that build configs inline (their
    // compatibility constructors always count as configured).
    assertFalse(ServerConfig.from(Map.of()).spawnConfigured());
    assertTrue(ServerConfig.from(Map.of("MIR2_SPAWN_X", "10")).spawnConfigured());
    assertTrue(ServerConfig.from(Map.of("MIR2_SPAWN_Y", "618")).spawnConfigured());
    assertTrue(ServerConfig.from(Map.of("MIR2_SPAWN_X", "10", "MIR2_SPAWN_Y", "10"))
        .spawnConfigured());
  }

  @Test
  void npcListParsesCustomEntriesAndHonoursNone() {
    assertEquals(ServerConfig.DEFAULT_NPCS, ServerConfig.parseNpcList(null));
    assertEquals(ServerConfig.DEFAULT_NPCS, ServerConfig.parseNpcList("  "));
    assertEquals(List.of(), ServerConfig.parseNpcList("none"));

    List<ServerConfig.NpcPlacement> custom = ServerConfig.parseNpcList(
        "屠夫:3:5:0, 药店老板:4:-5:2");
    assertEquals(2, custom.size());
    assertEquals(new ServerConfig.NpcPlacement("屠夫", 3, 5, 0), custom.get(0));
    assertEquals(new ServerConfig.NpcPlacement("药店老板", 4, -5, 2), custom.get(1));

    assertThrows(IllegalArgumentException.class, () -> ServerConfig.parseNpcList("屠夫:3:5"));
    assertThrows(IllegalArgumentException.class, () -> ServerConfig.parseNpcList("屠夫:x:5:0"));
  }
}
