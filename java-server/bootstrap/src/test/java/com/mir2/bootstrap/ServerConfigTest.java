package com.mir2.bootstrap;

import java.nio.file.Path;
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
}
