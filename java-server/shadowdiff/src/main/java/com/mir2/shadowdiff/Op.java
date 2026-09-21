package com.mir2.shadowdiff;

import com.mir2.world.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One step of a deterministic shadow-comparison operation stream.
 *
 * <p>The op set deliberately mirrors what the real {@code mir2.exe} client can emit over the
 * wire — turn/walk/run/attack/pickup/bag/say/drop/eat/takeon/takeoff/opendoor — plus the two
 * harness-level controls {@code sleep} (fixed pacing so action-interval checks resolve the
 * same way on both servers) and {@code relog} (exercises the persistence round trip). One
 * text line is one op, so the same script file can later be pointed at a Delphi server
 * without recompiling anything.
 */
public record Op(Kind kind, Direction direction, String text, long millis) {

  public enum Kind {
    /** {@code CM_TURN}. */
    TURN,
    /** {@code CM_WALK}, one cell. */
    WALK,
    /** {@code CM_RUN}, two cells. */
    RUN,
    /** {@code CM_HIT}. */
    HIT,
    /** {@code CM_HEAVYHIT}. */
    HEAVYHIT,
    /** {@code CM_BIGHIT}. */
    BIGHIT,
    /** {@code CM_PICKUP} on the current cell. */
    PICKUP,
    /** {@code CM_QUERYBAGITEMS}; silent on an empty bag, so the drain window matters. */
    BAG,
    /** {@code CM_SAY} with the literal text (chat, whisper, shout or @/​/ commands). */
    SAY,
    /** {@code CM_DROPITEM} by item name; resolves the MakeIndex from the tracked bag. */
    DROP,
    /** {@code CM_EAT} by item name; resolves the MakeIndex from the tracked bag. */
    EAT,
    /** {@code CM_TAKEONITEM} by item name (slot comes from the wire echo of the server). */
    TAKEON,
    /** {@code CM_TAKEOFFITEM} by item name. */
    TAKEOFF,
    /** Harness pause; keeps action intervals deterministic across both servers. */
    SLEEP,
    /** Close the GAME socket and run the full login → select → enter chain again. */
    RELOG
  }

  public Op {
    if (kind == null) throw new IllegalArgumentException("op kind is required");
  }

  static Op of(Kind kind) {
    return new Op(kind, null, null, 0);
  }

  static Op directional(Kind kind, Direction direction) {
    return new Op(kind, direction, null, 0);
  }

  static Op withText(Kind kind, String text) {
    return new Op(kind, null, text, 0);
  }

  static Op sleep(long millis) {
    return new Op(Kind.SLEEP, null, null, millis);
  }

  /** Renders the op back to its one-line script form. */
  public String describe() {
    StringBuilder line = new StringBuilder(kind.name().toLowerCase(Locale.ROOT));
    if (direction != null) line.append(' ').append(direction.code());
    if (text != null) line.append(' ').append(text);
    if (kind == Kind.SLEEP) line.append(' ').append(millis);
    return line.toString();
  }

  // ------------------------------------------------------------ script parsing

  /**
   * Parses a script: one op per line, {@code #} starts a comment, blank lines ignored.
   * Directions are the Delphi {@code DR_*} codes 0..7.
   */
  public static List<Op> parseScript(String script) {
    List<Op> ops = new ArrayList<>();
    int lineNumber = 0;
    for (String rawLine : script.split("\n", -1)) {
      lineNumber++;
      String line = rawLine.strip();
      int comment = line.indexOf('#');
      if (comment >= 0) line = line.substring(0, comment).strip();
      if (line.isEmpty()) continue;
      try {
        ops.add(parseLine(line));
      } catch (RuntimeException error) {
        throw new IllegalArgumentException(
            "script line " + lineNumber + " is invalid: " + rawLine.strip(), error);
      }
    }
    return List.copyOf(ops);
  }

  private static Op parseLine(String line) {
    String[] parts = line.split("\\s+", 2);
    String keyword = parts[0].toLowerCase(Locale.ROOT);
    String argument = parts.length > 1 ? parts[1].strip() : null;
    return switch (keyword) {
      case "turn" -> directional(Kind.TURN, parseDirection(argument));
      case "walk" -> directional(Kind.WALK, parseDirection(argument));
      case "run" -> directional(Kind.RUN, parseDirection(argument));
      case "hit" -> directional(Kind.HIT, parseDirection(argument));
      case "heavyhit" -> directional(Kind.HEAVYHIT, parseDirection(argument));
      case "bighit" -> directional(Kind.BIGHIT, parseDirection(argument));
      case "pickup" -> of(Kind.PICKUP);
      case "bag" -> of(Kind.BAG);
      case "say" -> withText(Kind.SAY, requireText(argument, "say"));
      case "drop" -> withText(Kind.DROP, requireText(argument, "drop"));
      case "eat" -> withText(Kind.EAT, requireText(argument, "eat"));
      case "takeon" -> withText(Kind.TAKEON, requireText(argument, "takeon"));
      case "takeoff" -> withText(Kind.TAKEOFF, requireText(argument, "takeoff"));
      case "sleep" -> sleep(Long.parseLong(requireText(argument, "sleep")));
      case "relog" -> of(Kind.RELOG);
      default -> throw new IllegalArgumentException("unknown op: " + keyword);
    };
  }

  private static Direction parseDirection(String argument) {
    if (argument == null) throw new IllegalArgumentException("direction 0..7 is required");
    return Direction.fromCode(Integer.parseInt(argument));
  }

  private static String requireText(String argument, String keyword) {
    if (argument == null || argument.isEmpty())
      throw new IllegalArgumentException(keyword + " requires an argument");
    return argument;
  }

  /**
   * The built-in deterministic smoke script: an empty-bag query, a fixed patrol square
   * (turns, walks, a run), attacks into empty cells spaced beyond the shared CM_HIT
   * interval, a pickup on a bare cell, chat with the online-count command, a drop of an
   * item the character cannot own yet (deterministic {@code SM_DROPITEM_FAIL}) and one
   * relog that proves the persisted state restores identically on both servers.
   */
  public static List<Op> defaultScript() {
    return parseScript("""
        # --- entry state ---
        bag
        say @who
        # --- patrol square (walk 4 cells, face each heading first) ---
        turn 2
        walk 2
        walk 2
        turn 4
        walk 4
        walk 4
        turn 6
        walk 6
        walk 6
        turn 0
        walk 0
        walk 0
        run 2
        # --- melee into empty cells; sleeps clear the shared CM_HIT interval ---
        hit 2
        sleep 1200
        heavyhit 4
        sleep 1200
        bighit 6
        sleep 1200
        # --- pickup on a bare cell (deterministic +FAIL) ---
        pickup
        # --- item path without any items: deterministic SM_DROPITEM_FAIL ---
        drop 木剑
        say hello-shadow
        # --- persistence round trip ---
        relog
        bag
        walk 4
        turn 0
        """);
  }
}
