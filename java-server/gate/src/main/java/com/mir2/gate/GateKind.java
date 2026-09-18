package com.mir2.gate;
public enum GateKind { LOGIN(7000), SELECT(7100), GAME(7200); private final int defaultPort; GateKind(int p){defaultPort=p;} public int defaultPort(){return defaultPort;} }
