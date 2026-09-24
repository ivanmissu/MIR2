package com.mir2.gate;

import java.util.Locale;

/**
 * Delphi's {@code TBlockIPMethod} (LoginGate/GateShare.pas:8) — what a gate does to an address
 * that just tripped a limit. Applied identically at both enforcement points: the admission
 * check (LoginGate/Main.pas:539-557) and the oversized-packet kick (RunGate/Main.pas:986-1001).
 */
public enum BlockMethod {
  /**
   * {@code mDisconnect} (the shipped default): close this one connection and nothing else. The
   * address stays welcome — a client that reconnects politely is served.
   */
  DISCONNECT,
  /**
   * {@code mBlock}: add the address to the volatile temp ban list, then {@code CloseConnect} —
   * which drops <em>every</em> connection currently held by that address, not just this one.
   * The ban is lost when the gate restarts.
   */
  BLOCK,
  /**
   * {@code mBlockList}: same immediate effect as {@link #BLOCK}, but the entry goes on the
   * permanent list that Delphi's {@code SaveBlockIPList} writes to {@code BlockIPList.txt},
   * where it is prefix-matched forever after.
   */
  BLOCK_LIST;

  /** Parses the {@code BlockMethod} config value; unknown/blank input keeps the default. */
  public static BlockMethod parse(String raw) {
    if (raw == null || raw.isBlank()) return DISCONNECT;
    String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (normalised) {
      case "DISCONNECT", "0" -> DISCONNECT;
      case "BLOCK", "1" -> BLOCK;
      case "BLOCK_LIST", "BLOCKLIST", "2" -> BLOCK_LIST;
      default -> throw new IllegalArgumentException(
          "block method must be one of disconnect, block, block-list (got: " + raw + ")");
    };
  }
}
