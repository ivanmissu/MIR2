package com.mir2.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Immutable, name-and-id indexed view of the vetted 1.50-compatible {@code Magic.DB} rows. */
public final class MagicCatalog {
  private static final String RESOURCE = "db/MagicDb.tsv";

  private final Map<Integer, MagicDefinition> byId;
  private final Map<String, MagicDefinition> byName;

  private MagicCatalog(Collection<MagicDefinition> definitions) {
    Map<Integer, MagicDefinition> ids = new LinkedHashMap<>();
    Map<String, MagicDefinition> names = new LinkedHashMap<>();
    for (MagicDefinition definition : definitions) {
      Objects.requireNonNull(definition, "definition");
      if (ids.putIfAbsent(definition.id(), definition) != null)
        throw new IllegalArgumentException("duplicate magic id: " + definition.id());
      if (names.putIfAbsent(definition.name(), definition) != null)
        throw new IllegalArgumentException("duplicate magic name: " + definition.name());
    }
    this.byId = Map.copyOf(ids);
    this.byName = Map.copyOf(names);
  }

  public static MagicCatalog of(Collection<MagicDefinition> definitions) {
    return new MagicCatalog(definitions);
  }

  public static MagicCatalog defaults() {
    return Holder.DEFAULTS;
  }

  public Optional<MagicDefinition> find(int id) {
    return Optional.ofNullable(byId.get(id));
  }

  public Optional<MagicDefinition> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public MagicDefinition require(int id) {
    MagicDefinition definition = byId.get(id);
    if (definition == null) throw new NoSuchElementException("unknown magic id: " + id);
    return definition;
  }

  public int size() {
    return byId.size();
  }

  public List<MagicDefinition> definitions() {
    return List.copyOf(byId.values());
  }

  static List<MagicDefinition> parse(BufferedReader reader) {
    List<MagicDefinition> definitions = new ArrayList<>();
    try {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.isBlank() || line.startsWith("#")) continue;
        // Keep the empty trailing description column.
        String[] fields = line.split("\\t", -1);
        if (fields.length != 19)
          throw new IllegalArgumentException("MagicDb row must have 19 columns: " + line);
        definitions.add(new MagicDefinition(
            integer(fields[0], line),
            fields[1],
            integer(fields[2], line),
            integer(fields[3], line),
            integer(fields[4], line),
            integer(fields[5], line),
            integer(fields[6], line),
            integer(fields[7], line),
            integer(fields[8], line),
            integer(fields[9], line),
            integer(fields[10], line),
            List.of(integer(fields[11], line), integer(fields[13], line), integer(fields[15], line)),
            List.of(integer(fields[12], line), integer(fields[14], line), integer(fields[16], line)),
            integer(fields[17], line),
            fields[18]));
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot read MagicDb", error);
    }
    return List.copyOf(definitions);
  }

  private static int integer(String value, String row) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("invalid MagicDb integer in row: " + row, error);
    }
  }

  private static MagicCatalog loadDefaults() {
    InputStream stream = MagicCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
    if (stream == null) throw new IllegalStateException("missing classpath resource: " + RESOURCE);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      return of(parse(reader));
    } catch (IOException error) {
      throw new IllegalStateException("cannot close MagicDb resource", error);
    }
  }

  private static final class Holder {
    private static final MagicCatalog DEFAULTS = loadDefaults();
  }
}
