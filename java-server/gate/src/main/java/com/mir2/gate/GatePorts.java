package com.mir2.gate;
public record GatePorts(int login, int select, int game) {
 public GatePorts { validate(login); validate(select); validate(game); }
 public static GatePorts defaults(){return new GatePorts(7000,7100,7200);}
 public int port(GateKind kind){return switch(kind){case LOGIN->login;case SELECT->select;case GAME->game;};}
 private static void validate(int p){if(p<1||p>65535)throw new IllegalArgumentException("invalid TCP port: "+p);}
}
