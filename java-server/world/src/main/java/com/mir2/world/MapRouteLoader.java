package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the route rows at the end of legacy {@code Envir/MapInfo.txt}.
 *
 * <p>{@code TFrmDB.LoadMapInfo} accepts rows in the form
 * {@code source-map source-x source-y -> destination-map destination-x destination-y}, with
 * spaces, commas, tabs, {@code -} and {@code >} all treated as separators. This loader keeps that
 * route grammar without attempting to translate MapInfo's unrelated map-flag and quest sections.
 * It also accepts a dedicated route-only file with the same rows.
 */
public final class MapRouteLoader {
  private static final Charset GBK = Charset.forName("GBK");

  private MapRouteLoader() {}

  /** Loads a route file, preferring UTF-8 and falling back to GBK when UTF-8 is malformed. */
  public static List<MapRoute> load(Path file) throws IOException {
    Objects.requireNonNull(file, "file");
    Path root = file.toAbsolutePath().normalize();
    List<MapRoute> routes = new ArrayList<>();
    // LocalDB.LoadMapInfo resolves every LoadMapInfo directive below Envir/MapInfo, not relative
    // to a previously included file. Preserve that behaviour and reject cycles explicitly.
    loadInto(root, root.getParent().resolve("MapInfo"), new LinkedHashSet<>(), routes);
    return List.copyOf(routes);
  }

  private static void loadInto(
      Path file, Path includeDirectory, Set<Path> visited, List<MapRoute> routes) throws IOException {
    Path normalized = file.toAbsolutePath().normalize();
    if (!visited.add(normalized)) {
      throw new IOException("cyclic LoadMapInfo include: " + normalized);
    }
    byte[] bytes = Files.readAllBytes(normalized);
    String[] lines = decode(bytes).split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      String include = includeName(lines[index]);
      if (include != null) {
        Path included = includeDirectory.resolve(include).normalize();
        if (!included.startsWith(includeDirectory)) {
          throw malformed(normalized, index + 1, lines[index], "LoadMapInfo path escapes MapInfo directory");
        }
        loadInto(included, includeDirectory, visited, routes);
        continue;
      }
      MapRoute route = parseLine(lines[index], index + 1, normalized);
      if (route != null) routes.add(route);
    }
  }

  /** Returns the filename from a LocalDB {@code LoadMapInfo filename} directive, if present. */
  private static String includeName(String raw) throws IOException {
    String line = raw == null ? "" : raw.trim();
    if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")) return null;
    int comment = line.indexOf(';');
    if (comment >= 0) line = line.substring(0, comment).trim();
    if (line.isEmpty()) return null;
    String[] words = line.split("\\s+", 2);
    if (!words[0].equalsIgnoreCase("loadmapinfo")) return null;
    if (words.length != 2 || words[1].isBlank()) {
      throw new IOException("LoadMapInfo directive requires a filename: " + raw);
    }
    return words[1].trim();
  }

  /** Parses one source-compatible route row; comments, headers and blank rows return {@code null}. */
  static MapRoute parseLine(String raw, int lineNumber, Path file) throws IOException {
    String line = raw == null ? "" : raw.trim();
    if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")) return null;
    int comment = line.indexOf(';');
    if (comment >= 0) line = line.substring(0, comment).trim();
    if (line.isEmpty()) return null;

    // LocalDB.pas:GetValidStr3 uses these exact punctuation characters as route delimiters.
    String[] values = line.split("[\\s,>\\-]+", -1);
    if (values.length != 6) {
      throw malformed(file, lineNumber, raw,
          "expected: sourceMap sourceX sourceY -> destinationMap destinationX destinationY");
    }
    try {
      return new MapRoute(values[0], new Position(parseCoordinate(values[1]), parseCoordinate(values[2])),
          values[3], new Position(parseCoordinate(values[4]), parseCoordinate(values[5])));
    } catch (IllegalArgumentException error) {
      throw malformed(file, lineNumber, raw, error.getMessage());
    }
  }

  private static int parseCoordinate(String value) {
    int coordinate = Integer.parseInt(value);
    if (coordinate < 0) throw new IllegalArgumentException("coordinates must not be negative");
    return coordinate;
  }

  private static IOException malformed(Path file, int line, String raw, String reason) {
    return new IOException("invalid map route at " + file + ":" + line + " (" + reason + "): " + raw);
  }

  private static String decode(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException malformedUtf8) {
      return GBK.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
  }
}
