package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses the classic {@code MonGen.txt} / {@code MonGen/*.txt} spawn-list syntax. */
public final class MonGenLoader {
  private MonGenLoader() {}

  /**
   * Loads the main file and recursively expands legacy {@code loadgen filename} directives.
   * Rows are: map x y "monster" range count respawnMinutes missionGenRate.
   */
  public static List<MonsterSpawnDefinition> load(Path file) throws IOException {
    return load(file.toAbsolutePath().normalize(), new ArrayList<>(), 0);
  }

  private static List<MonsterSpawnDefinition> load(
      Path file, List<MonsterSpawnDefinition> result, int depth) throws IOException {
    if (depth > 16) throw new IOException("MonGen include nesting exceeds 16: " + file);
    if (!Files.isRegularFile(file)) throw new IOException("MonGen file does not exist: " + file);
    Charset charset = StandardCharsets.UTF_8;
    String text = Files.readString(file, charset);
    // Old server installations commonly save these files as GBK. A replacement character is a
    // reliable indication that UTF-8 was the wrong choice for a file containing Chinese names.
    if (text.indexOf('\ufffd') >= 0) text = Files.readString(file, Charset.forName("GBK"));
    Path base = file.getParent() == null ? Path.of(".") : file.getParent();
    int lineNumber = 0;
    for (String raw : text.split("\\R", -1)) {
      lineNumber++;
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
      List<String> fields = tokenize(line, file, lineNumber);
      if (fields.get(0).equalsIgnoreCase("loadgen")) {
        if (fields.size() != 2) throw error(file, lineNumber, "loadgen requires one filename");
        load(base.resolve(fields.get(1)).normalize(), result, depth + 1);
        continue;
      }
      if (fields.size() < 8) throw error(file, lineNumber, "expected 8 fields, got " + fields.size());
      try {
        result.add(new MonsterSpawnDefinition(fields.get(0), Integer.parseInt(fields.get(1)),
            Integer.parseInt(fields.get(2)), fields.get(3), Integer.parseInt(fields.get(4)),
            Integer.parseInt(fields.get(5)), Math.multiplyExact(Long.parseLong(fields.get(6)), 60_000L),
            Integer.parseInt(fields.get(7))));
      } catch (RuntimeException ex) {
        throw error(file, lineNumber, ex.getMessage());
      }
    }
    return result;
  }

  private static List<String> tokenize(String line, Path file, int lineNumber) throws IOException {
    List<String> fields = new ArrayList<>();
    int i = 0;
    while (i < line.length()) {
      while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
      if (i == line.length()) break;
      if (line.charAt(i) == ';') break;
      if (line.charAt(i) == '"') {
        int end = line.indexOf('"', i + 1);
        if (end < 0) throw error(file, lineNumber, "unterminated quoted monster name");
        fields.add(line.substring(i + 1, end));
        i = end + 1;
      } else {
        int start = i;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != ';') i++;
        fields.add(line.substring(start, i));
      }
    }
    return fields;
  }

  private static IOException error(Path file, int line, String detail) {
    return new IOException(file + ":" + line + ": " + detail);
  }
}
