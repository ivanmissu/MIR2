package com.mir2.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One monster's {@code MonItems/<name>.txt} drop table, imported from the official 1.76
 * baseline (GEEM2 dump) and normalised by {@code scripts/extract-geem2-db.py} — the Java
 * counterpart of {@code TFrmDB.LoadMonitems} (LocalDB.pas:1377) producing
 * {@code TMonItem} rows.
 *
 * <p>Row format: {@code num/den<TAB>itemName<TAB>count[<TAB>annotation]}; lines starting
 * with {@code #} are comments. The Delphi roll for a row is
 * {@code Random(MaxPoint) <= SelPoint} with {@code SelPoint = num - 1} and
 * {@code MaxPoint = den} (ObjBase.pas {@code GetMonItems}); every imported row carries
 * {@code num = 1}, so the roll collapses to the "1 in {@code den}" shape that
 * {@link ItemDrop#oneIn()} models.
 *
 * <p>Two annotations may mark a row:
 * <ul>
 *   <li>{@code GOLD-DROP} — the item is {@code 金币}; this spawns ground gold piles whose
 *       pickup fills the wallet ({@code TGoldObject}). The row is kept separate from ordinary
 *       item drops (see {@link Result#goldDrops()}) because it rolls a random coin amount and
 *       never enters the backpack;
 *   <li>{@code DEAD-ROW} — the name has no {@code StdItems} entry (upstream quirk);
 *       Delphi keeps such rows and silently fails the name lookup at drop time, so the
 *       loader keeps them too, in {@link Result#deadRows()}.
 * </ul>
 *
 * <p>Duplicate rows are kept on purpose: Delphi rolls each row independently, so
 * {@code 1/1 强效太阳水} appearing three times means up to three potions per kill.
 */
public final class MonsterDropTable {

  /** One raw MonItems row. {@code annotation} is empty for ordinary item rows. */
  public record Row(int numerator, int denominator, String itemName, int count, String annotation) {

    static final String GOLD_DROP = "GOLD-DROP";
    static final String DEAD_ROW = "DEAD-ROW";

    public Row {
      if (numerator < 1) throw new IllegalArgumentException("numerator must be positive");
      if (denominator < numerator) throw new IllegalArgumentException("denominator must cover the numerator");
      if (itemName == null || itemName.isBlank()) throw new IllegalArgumentException("item name must not be blank");
      if (count < 1) throw new IllegalArgumentException("count must be positive");
      annotation = annotation == null ? "" : annotation;
    }

    boolean gold() { return annotation.equals(GOLD_DROP); }
    boolean dead() { return annotation.equals(DEAD_ROW); }
  }

  /** A wallet-gold row: {@code oneIn} chance, then {@code count div 2 + Random(count)} coins. */
  public record GoldDrop(int oneIn, int count) {
    public GoldDrop {
      if (oneIn < 1) throw new IllegalArgumentException("gold drop denominator must be positive");
      if (count < 1) throw new IllegalArgumentException("gold drop count must be positive");
    }
  }

  /** The resolved table: engine-consumable item drops plus separate wallet-gold rows. */
  public record Result(List<ItemDrop> drops, List<GoldDrop> goldDrops, List<Row> deadRows) {
    public Result {
      drops = List.copyOf(drops);
      goldDrops = List.copyOf(goldDrops);
      deadRows = List.copyOf(deadRows);
    }
  }

  /** Resource layout produced by the extractor (ASCII file names; see {@link #INDEX}). */
  public static final String RESOURCE_DIR = "/db/MonItems/";
  /** Monster name → ASCII resource file; indirection keeps non-ASCII names off the classpath. */
  public static final String INDEX = "index.tsv";

  private static volatile java.util.Map<String, String> indexCache;

  /**
   * Loads the drop table registered for {@code monsterName} through {@code index.tsv}.
   * Item rows resolve their {@code looks} icon through {@code items}; a name missing from
   * the catalogue is faithful-dead (Delphi silently drops nothing for it) and kept in
   * {@link Result#deadRows()}.
   *
   * <p>Every currently imported row has numerator 1; {@link Row#numerator()} differs only
   * if a future regeneration ever accepts a non-1 numerator, which the engine's 1-in-N
   * {@link ItemDrop} model cannot express — fail fast instead of silently skewing odds.
   */
  /** Empty table — the Delphi {@code FileExists} guard in {@code LoadMonitems} makes a monster without a drop file simply drop nothing. */
  private static final Result EMPTY = new Result(List.of(), List.of(), List.of());

  public static Result load(String monsterName, ItemDatabase items) {
    String file = index().get(monsterName);
    if (file == null) return EMPTY;
    List<ItemDrop> drops = new ArrayList<>();
    List<GoldDrop> gold = new ArrayList<>();
    List<Row> dead = new ArrayList<>();
    for (Row row : parse(open(RESOURCE_DIR + file), monsterName)) {
      if (row.numerator() != 1)
        throw new IllegalStateException(monsterName + ": unsupported drop numerator " + row.numerator());
      if (row.gold()) {
        gold.add(new GoldDrop(row.denominator(), row.count()));
        continue;
      }
      if (row.dead()) {
        dead.add(row);
        continue;
      }
      StdItem template = items.require(row.itemName());
      drops.add(new ItemDrop(row.itemName(), template.looks(), row.denominator()));
    }
    return new Result(drops, gold, dead);
  }

  static List<Row> parse(BufferedReader reader, String monsterName) {
    List<Row> rows = new ArrayList<>();
    try {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] f = line.split("\t", -1);
        String where = "MonItems/" + monsterName + ".txt:" + lineNumber;
        try {
          String[] chance = f[0].split("/", -1);
          if (f.length < 3 || chance.length != 2)
            throw new IllegalStateException("expected num/den<TAB>name<TAB>count");
          rows.add(new Row(
              Integer.parseInt(chance[0].trim()),
              Integer.parseInt(chance[1].trim()),
              f[1],
              Integer.parseInt(f[2].trim()),
              f.length > 3 ? f[3].trim() : ""));
        } catch (IllegalArgumentException e) {
          throw new IllegalStateException(where + ": malformed row: " + line, e);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading MonItems/" + monsterName + ".txt", e);
    }
    return rows;
  }

  private static java.util.Map<String, String> index() {
    java.util.Map<String, String> index = indexCache;
    if (index == null) {
      java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
      try (BufferedReader reader = open(RESOURCE_DIR + INDEX)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank() || line.startsWith("#")) continue;
          String[] f = line.split("\t", -1);
          if (f.length != 2)
            throw new IllegalStateException(RESOURCE_DIR + INDEX + ": malformed index row: " + line);
          map.put(f[0], f[1]);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("failed reading " + RESOURCE_DIR + INDEX, e);
      }
      index = java.util.Map.copyOf(map);
      indexCache = index;
    }
    return index;
  }

  private static BufferedReader open(String resource) {
    InputStream stream = MonsterDropTable.class.getResourceAsStream(resource);
    if (stream == null)
      throw new IllegalStateException("missing classpath resource " + resource
          + " (run scripts/extract-geem2-db.py)");
    return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
  }

  private MonsterDropTable() {}
}
