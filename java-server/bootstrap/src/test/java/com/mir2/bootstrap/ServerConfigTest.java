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
    assertNull(config.bootstrapUser());
  }

  @Test
  void environmentOverridesAllDeploymentValues() {
    ServerConfig config = ServerConfig.from(Map.of(
        "MIR2_DATABASE", "/tmp/custom.db",
        "MIR2_LOGIN_PORT", "17000",
        "MIR2_SELECT_PORT", "17100",
        "MIR2_GAME_PORT", "17200",
        "MIR2_ADVERTISED_HOST", "192.0.2.10",
        "MIR2_SERVER_NAME", "TestServer",
        "MIR2_BOOTSTRAP_USER", "admin",
        "MIR2_BOOTSTRAP_PASSWORD", "secret"));
    assertEquals(17000, config.ports().login());
    assertEquals("192.0.2.10", config.advertisedHost());
    assertEquals("admin", config.bootstrapUser());
  }

  @Test
  void partialCredentialsAndInvalidPortsFailFast() {
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_BOOTSTRAP_USER", "admin")));
    assertThrows(IllegalArgumentException.class,
        () -> ServerConfig.from(Map.of("MIR2_LOGIN_PORT", "wrong")));
  }
}
