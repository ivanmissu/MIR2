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
    String bootstrapUser,
    String bootstrapPassword) {

  public ServerConfig {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(ports, "ports");
    advertisedHost = requireText(advertisedHost, "advertised host");
    serverName = requireText(serverName, "server name");
    if (serverName.indexOf('/') >= 0) throw new IllegalArgumentException("server name must not contain '/'");
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
        nullable(environment.get("MIR2_BOOTSTRAP_USER")),
        nullable(environment.get("MIR2_BOOTSTRAP_PASSWORD")));
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

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }
}
