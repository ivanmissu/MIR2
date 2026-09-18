package com.mir2.gate;

public record GatePorts(int login, int select, int game) {
  public static final int DEFAULT_LOGIN = 7000;
  public static final int DEFAULT_SELECT = 7100;
  public static final int DEFAULT_GAME = 7200;

  public GatePorts {
    validate(login);
    validate(select);
    validate(game);
  }

  public static GatePorts defaults() {
    return new GatePorts(DEFAULT_LOGIN, DEFAULT_SELECT, DEFAULT_GAME);
  }

  public int port(GateKind kind) {
    return switch (kind) {
      case LOGIN -> login;
      case SELECT -> select;
      case GAME -> game;
    };
  }

  private static void validate(int port) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid TCP port: " + port);
  }
}
