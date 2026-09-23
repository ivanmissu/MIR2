package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses legacy {@code MapInfo.txt} files (LocalDB.pas), which combine the map catalogue
 * with the inter-map connection-point ("route") table:
 *
 * <ul>
 *   <li>{@code ;} comment lines and blank lines are skipped;
 *   <li>{@code loadmapinfo <file>} includes another file; like {@code LoadSubMapInfo} the
 *       include is resolved from the {@code MapInfo/} subdirectory of the data directory
 *       (a same-directory fallback is kept for convenience);
 *   <li>{@code [id description serverIndex flags...]} defines a map. Optional {@code |}
 *       aliasing ({@code [D016|D015 半兽古墓三层]}) names the logical id before the pipe and
 *       the {@code .map} file alias after it; quoted descriptions keep embedded spaces;
 *       flags (SAFE, DARK, DAY, FIGHT, NORECONNECT, NOCHAT, QUIZ, NODRUG, NOTHROWITEM, ...)
 *       are parsed into {@link MapFlags};
 *   <li>any other non-empty line is a route
 *       {@code srcMap srcX srcY -> dstMap dstX dstY}, tokenised exactly like the Delphi
 *       {@code GetValidStr3} chains: the first three fields split on space/comma/tab, the
 *       destination map additionally splits on {@code -} and {@code >} (which is how the
 *       {@code ->} arrow disappears), and the last field cuts trailing {@code ;} comments.
 *       Non-numeric coordinate fields fall back to {@code 0} as {@code Str_ToInt(x, 0)} does.
 * </ul>
 */
public final class MapInfoLoader {
  private static final Charset GBK = Charset.forName("GBK");
  private static final String INCLUDE_PREFIX = "loadmapinfo";
  private static final String INCLUDE_SUBDIRECTORY = "MapInfo";

  private MapInfoLoader() {}

  /** One {@code [...]} map-definition row. */
  public record MapDefinition(String id, String fileAlias, String description, MapFlags flags) {
    public MapDefinition {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("map id must not be blank");
      Objects.requireNonNull(description, "description");
      Objects.requireNonNull(flags, "flags");
    }

    public MapDefinition(String id, String fileAlias, String description) {
      this(id, fileAlias, description, MapFlags.DEFAULT);
    }

    /** Name of the {@code .map} file this definition loads (alias wins, like the classic alias form). */
    public String mapFileName() {
      return fileAlias == null ? id : fileAlias;
    }
  }

  /** One connection-point route line: srcMap srcX srcY -> dstMap dstX dstY. */
  public record RouteLine(String sourceMapId, int sourceX, int sourceY,
      String destinationMapId, int destinationX, int destinationY) {
    public RouteLine {
      if (sourceMapId == null || sourceMapId.isBlank())
        throw new IllegalArgumentException("route source map id must not be blank");
      if (destinationMapId == null || destinationMapId.isBlank())
        throw new IllegalArgumentException("route destination map id must not be blank");
    }

    public TeleportRoute toTeleportRoute() {
      return new TeleportRoute(sourceMapId, new Position(sourceX, sourceY),
          destinationMapId, new Position(destinationX, destinationY));
    }
  }

  /** Parse result: validated rows plus human-readable notes about every skipped line. */
  public record MapInfoDocument(List<MapDefinition> maps, List<RouteLine> routes,
      List<String> diagnostics) {
    public MapInfoDocument {
      maps = List.copyOf(maps);
      routes = List.copyOf(routes);
      diagnostics = List.copyOf(diagnostics);
    }
  }

  public static MapInfoDocument load(Path mapInfoFile) throws IOException {
    Objects.requireNonNull(mapInfoFile, "mapInfoFile");
    List<String> lines = expandIncludes(mapInfoFile, 0);
    List<MapDefinition> maps = new ArrayList<>();
    List<RouteLine> routes = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith(";")) continue;
      if (line.startsWith("[")) {
        parseMapDefinition(line, maps, diagnostics);
      } else {
        parseRoute(line, routes, diagnostics);
      }
    }
    return new MapInfoDocument(maps, routes, diagnostics);
  }

  /** Inlines {@code loadmapinfo} directives, watching out for include cycles. */
  private static List<String> expandIncludes(Path file, int depth) throws IOException {
    if (depth > 8) throw new IOException("loadmapinfo include depth exceeded: " + file);
    List<String> merged = new ArrayList<>();
    Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
    for (String line : Files.readAllLines(file, GBK)) {
      String plain = line.strip();
      if (plain.length() >= INCLUDE_PREFIX.length()
          && plain.substring(0, INCLUDE_PREFIX.length()).equalsIgnoreCase(INCLUDE_PREFIX)) {
        List<String> tokens = tokens(plain, " ,\t");
        if (tokens.size() < 2) continue;
        Path include = resolveInclude(parent, tokens.get(1));
        if (include == null) continue; // LoadSubMapInfo silently skips a missing include.
        merged.addAll(expandIncludes(include, depth + 1));
      } else {
        merged.add(line);
      }
    }
    return merged;
  }

  private static Path resolveInclude(Path parent, String name) {
    Path inSubdirectory = parent.resolve(INCLUDE_SUBDIRECTORY).resolve(name);
    if (Files.isRegularFile(inSubdirectory)) return inSubdirectory;
    Path beside = parent.resolve(name);
    return Files.isRegularFile(beside) ? beside : null;
  }

  /**
   * {@code [id description serverIndex flags...]}: bracket capture mirroring
   * {@code ArrestStringEx(line, '[', ']', ...)} with the {@code |} alias split.
   * Trailing tokens (inside or outside brackets) are parsed into {@link MapFlags}.
   */
  private static void parseMapDefinition(String line, List<MapDefinition> maps,
      List<String> diagnostics) {
    int close = line.indexOf(']');
    if (close <= 1) {
      diagnostics.add("map definition without closing ']': " + line);
      return;
    }
    String content = line.substring(1, close).strip();
    String trailing = close + 1 < line.length() ? line.substring(close + 1).strip() : "";

    String id;
    String fileAlias = null;
    List<String> tokens;
    int pipe = content.indexOf('|');
    if (pipe >= 0) {
      id = content.substring(0, pipe).strip();
      tokens = tokens(content.substring(pipe + 1).strip(), " ,\t");
      if (tokens.isEmpty()) {
        diagnostics.add("map alias definition without file name: " + line);
        return;
      }
      fileAlias = tokens.remove(0);
    } else {
      tokens = tokens(content, " ,\t");
      if (tokens.isEmpty()) {
        diagnostics.add("map definition without an id: " + line);
        return;
      }
      id = tokens.remove(0);
    }
    // Description is the first remaining token; quoted forms keep a single token.
    String description = tokens.isEmpty() ? "" : quotedToken(tokens);
    if (id.isBlank()) {
      diagnostics.add("map definition without an id: " + line);
      return;
    }

    // Combine remaining bracket tokens and trailing tokens after ']'
    List<String> flagTokens = new ArrayList<>(tokens);
    if (!trailing.isEmpty()) {
      flagTokens.addAll(tokens(trailing, " ,\t"));
    }
    MapFlags flags = parseFlags(flagTokens);

    maps.add(new MapDefinition(id, fileAlias, description, flags));
  }

  /** Visible for tests: the flag-token half of a MapInfo.txt line. */
  static MapFlags parseFlags(List<String> tokens) {
    boolean safeZone = false;
    boolean darkness = false;
    boolean dayLight = false;
    boolean fightZone = false;
    boolean fight3Zone = false;
    boolean quiz = false;
    boolean noReconnect = false;
    String noReconnectMap = "";
    boolean noChat = false;
    boolean runHuman = true;
    boolean runMon = false;
    int musicId = -1;
    int expRate = -1;
    boolean noDrug = false;
    boolean noThrowItem = false;
    boolean noDropItem = false;

    for (String token : tokens) {
      String upper = token.toUpperCase();
      if (upper.equals("SAFE")) {
        safeZone = true;
      } else if (upper.equals("DARK")) {
        darkness = true;
      } else if (upper.equals("DAY") || upper.equals("DAYLIGHT")) {
        dayLight = true;
      } else if (upper.equals("FIGHT")) {
        fightZone = true;
      } else if (upper.equals("FIGHT3")) {
        fight3Zone = true;
      } else if (upper.equals("QUIZ")) {
        quiz = true;
      } else if (upper.equals("NOCHAT")) {
        noChat = true;
      } else if (upper.equals("NODRUG")) {
        noDrug = true;
      } else if (upper.equals("NOTHROWITEM")) {
        noThrowItem = true;
      } else if (upper.equals("NODROPITEM")) {
        noDropItem = true;
      } else if (upper.equals("RUNHUMAN")) {
        runHuman = true;
      } else if (upper.equals("RUNMON")) {
        runMon = true;
      } else if (upper.startsWith("NORECONNECT")) {
        noReconnect = true;
        String param = extractParentheses(token);
        if (!param.isBlank()) noReconnectMap = param;
      } else if (upper.startsWith("MUSIC")) {
        String param = extractParentheses(token);
        musicId = parseIntOrNegative(param);
      } else if (upper.startsWith("EXPRATE")) {
        String param = extractParentheses(token);
        expRate = parseIntOrNegative(param);
      }
    }

    return new MapFlags(
        safeZone, darkness, dayLight, fightZone, fight3Zone, quiz,
        noReconnect, noReconnectMap, noChat, runHuman, runMon, musicId, expRate,
        noDrug, noThrowItem, noDropItem);
  }

  private static String extractParentheses(String token) {
    int start = token.indexOf('(');
    int end = token.lastIndexOf(')');
    if (start >= 0 && end > start) {
      return token.substring(start + 1, end).trim();
    }
    return "";
  }

  private static int parseIntOrNegative(String token) {
    try {
      return Integer.parseInt(token.strip());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Takes the description token, honouring the legacy "quoted description" form. */
  private static String quotedToken(List<String> tokens) {
    String first = tokens.remove(0);
    if (!first.startsWith("\"") || (first.length() >= 2 && first.endsWith("\""))) {
      return stripQuotes(first);
    }
    while (!tokens.isEmpty()) {
      String next = tokens.remove(0);
      if (next.endsWith("\"")) return first.substring(1) + " " + next.substring(0, next.length() - 1);
      first = first + " " + next;
    }
    return stripQuotes(first);
  }

  private static String stripQuotes(String token) {
    return token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")
        ? token.substring(1, token.length() - 1)
        : token;
  }

  /** {@code src srcX srcY -> dst dstX dstY} with Delphi's per-field delimiter chains. */
  private static void parseRoute(String line, List<RouteLine> routes, List<String> diagnostics) {
    String[] head = line.split(" ", 2);
    String firstField = head[0];
    if (firstField.isBlank() || firstField.startsWith(";")) return;
    List<String> tokens = new ArrayList<>();
    String remainder = line.strip();
    remainder = firstToken(remainder, tokens, " ,\t");      // srcMap
    remainder = firstToken(remainder, tokens, " ,\t");      // srcX
    remainder = firstToken(remainder, tokens, " ,\t");      // srcY
    remainder = firstToken(remainder, tokens, " ,->\t");    // dstMap ("->" dissolves here)
    remainder = firstToken(remainder, tokens, " ,\t");      // dstX
    remainder = firstToken(remainder, tokens, " ,;\t");     // dstY (";comment" cut off)
    if (tokens.size() < 6) {
      diagnostics.add("incomplete route line skipped: " + line);
      return;
    }
    RouteLine route = new RouteLine(tokens.get(0), parseIntOrZero(tokens.get(1)),
        parseIntOrZero(tokens.get(2)), tokens.get(3), parseIntOrZero(tokens.get(4)),
        parseIntOrZero(tokens.get(5)));
    routes.add(route);
  }

  /** Mirrors GetValidStr3: skip leading delimiters, take one token, return the remainder. */
  private static String firstToken(String line, List<String> tokens, String delimiters) {
    int start = 0;
    while (start < line.length() && delimiters.indexOf(line.charAt(start)) >= 0) start++;
    int end = start;
    while (end < line.length() && delimiters.indexOf(line.charAt(end)) < 0) end++;
    if (start < end) tokens.add(line.substring(start, end));
    return line.substring(end);
  }

  private static List<String> tokens(String line, String delimiters) {
    List<String> result = new ArrayList<>();
    while (!line.strip().isEmpty()) {
      line = firstToken(line.strip(), result, delimiters);
    }
    return result;
  }

  /** Str_ToInt(token, 0): non-numeric fields coerce to zero instead of failing the load. */
  private static int parseIntOrZero(String token) {
    try {
      return Integer.parseInt(token.strip());
    } catch (NumberFormatException notNumeric) {
      return 0;
    }
  }

}
