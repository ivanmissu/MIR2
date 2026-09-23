package com.mir2.world;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W18 pinning suite for the authoritative data import: it locks the Java world model to
 * the official 1.76 GEEM2 baseline dump row by row, so a regression in the extractor, the
 * loaders or the template wiring fails deterministically.
 *
 * <p>Sources (see file headers): {@code db/MonsterDb.tsv} (378 rows),
 * {@code db/StdItemsDb.tsv} (686 rows), {@code db/MonItems/} (10 drop tables).
 */
class Geem2BaselineImportTest {

  /** (template display name, Monster.DB row name) for the eleven engine-supported monsters. */
  private static final Map<String, String> SUPPORTED = Map.ofEntries(
      Map.entry("鸡", "鸡"),
      Map.entry("鹿", "鹿"),
      Map.entry("稻草人", "稻草人"),
      Map.entry("多钩猫", "多钩猫"),
      Map.entry("钉耙猫", "钉耙猫"),
      Map.entry("洞蛆", "洞蛆"),
      Map.entry("蝎子", "蝎子"),
      Map.entry("半兽人", "半兽人"),
      Map.entry("半兽勇士", "半兽勇士"),
      Map.entry("半兽战士", "半兽战士"),
      Map.entry("木桩", "练功师"));

  @Test
  void monsterDbCarriesTheFullBaselineAndTheSupportedRowsAreExact() {
    assertEquals(378, MonsterDb.all().size(), "the import must keep the full Monster.DB");

    MonsterDb.Row chicken = MonsterDb.byName("鸡").orElseThrow();
    assertEquals(new MonsterDb.Row("鸡", 51, 11, 160, 2, 0, 0, 9, 5, 0, 0, 0, 1, 1, 0, 0,
        10, 3, 1400, 1, 0, 3000), chicken);

    MonsterDb.Row trainer = MonsterDb.byName("练功师").orElseThrow();
    assertEquals(55, trainer.race(), "TRAINER race id (M2Share.pas:152)");
    assertEquals(9999, trainer.hp(), "the official dummy is a 9999-HP punching bag");
    assertEquals(1, trainer.exp());
  }

  @Test
  void everySupportedTemplateIsBuiltFromItsMonsterDbRow() {
    for (Map.Entry<String, String> entry : SUPPORTED.entrySet()) {
      MonsterTemplate template = MonsterTemplate.forName(entry.getKey());
      MonsterDb.Row row = MonsterDb.byName(entry.getValue()).orElseThrow();

      assertEquals(row.hp(), template.ability().maxHp(), entry.getKey() + " HP");
      assertEquals(row.hp(), template.ability().hp(), entry.getKey() + " starts full");
      assertEquals(row.dc(), template.ability().minDc(), entry.getKey() + " DC min");
      assertEquals(row.dcMax(), template.ability().maxDc(), entry.getKey() + " DC max");
      // Delphi: AC := MakeLong(wAC, wAC) — a flat value, not a range.
      assertEquals(row.ac(), template.ability().minAc(), entry.getKey() + " AC is flat (min)");
      assertEquals(row.ac(), template.ability().maxAc(), entry.getKey() + " AC is flat (max)");
      assertEquals(row.level(), template.ability().level(), entry.getKey() + " level");
      assertEquals(row.exp(), template.experience(), entry.getKey() + " fight exp");
      assertEquals(MonsterDb.clampActionInterval(row.walkSpd()), template.walkIntervalMillis(),
          entry.getKey() + " walk interval (LoadMonsterDB's 200 ms floor applied)");
      assertEquals(MonsterDb.clampActionInterval(row.attackSpd()), template.attackIntervalMillis(),
          entry.getKey() + " attack interval (ditto)");
      assertEquals(MonsterTemplate.packFeature(row.raceImg(), row.monsterWeapon(), row.appr()),
          template.feature(), entry.getKey() + " wire feature");
    }
  }

  @Test
  void templateDropsAreExactlyTheConvertedMonItemsRows() {
    ItemDatabase catalog = ItemDatabase.of(StdItems.defaults());
    for (Map.Entry<String, String> entry : SUPPORTED.entrySet()) {
      MonsterTemplate template = MonsterTemplate.forName(entry.getKey());
      MonsterDropTable.Result table = MonsterDropTable.load(entry.getValue(), catalog);
      assertEquals(table.drops(), template.drops(), entry.getKey() + " drop list");
      assertEquals(table.goldDrops(), template.goldDrops(), entry.getKey() + " gold drop list");
    }

    MonsterTemplate chicken = MonsterTemplate.chicken();
    assertEquals(List.of(new ItemDrop("鸡肉", StdItems.chickenMeat().looks(), 1)), chicken.drops());

    MonsterTemplate deer = MonsterTemplate.deer();
    assertEquals(List.of(
        new ItemDrop("肉", StdItems.require("肉").looks(), 1),
        new ItemDrop("肉", StdItems.require("肉").looks(), 10),
        new ItemDrop("鹿血", StdItems.require("鹿血").looks(), 10000)), deer.drops());

    // The cave maggot's ring rows are the reason the revival ring has a real source.
    assertTrue(MonsterTemplate.caveMaggot().drops().contains(
        new ItemDrop("复活戒指", StdItems.revivalRing().looks(), 10_000_000)),
        "洞蛆 keeps the 1/10M special-ring rows");

    // Wallet-gold rows are parsed separately from ordinary item rows and now feed ground piles.
    assertEquals(List.of(
        new MonsterDropTable.GoldDrop(2, 100),
        new MonsterDropTable.GoldDrop(10000, 1000)),
        MonsterTemplate.goldDropsOf("半兽人"));
    assertEquals(List.of(new MonsterDropTable.GoldDrop(1, 1160)),
        MonsterTemplate.goldDropsOf("半兽勇士"));
    assertTrue(MonsterTemplate.trainer().drops().isEmpty()
        && MonsterTemplate.goldDropsOf("练功师").isEmpty(),
        "the dummy has no MonItems file upstream, so it drops nothing");
  }

  @Test
  void stdItemsCarriesTheFullBaselineAndTheKeyRowsAreExact() {
    List<StdItem> items = StdItems.defaults();
    assertEquals(684, items.size(), "686 rows minus the two upstream duplicate names");

    StdItem meat = StdItems.chickenMeat();
    assertEquals(40, meat.stdMode(), "meat quality class");
    assertEquals(13, meat.looks());
    assertEquals(4000, meat.duraMax());
    assertEquals(80, meat.price());

    StdItem sword = StdItems.woodenSword();
    assertEquals(5, sword.stdMode());
    assertEquals(1, sword.shape());
    assertEquals(4, sword.weight());
    assertEquals(30, sword.looks());
    assertEquals(4000, sword.duraMax());
    assertEquals(2, sword.dcMin());
    assertEquals(5, sword.dcMax());
    assertEquals(0, sword.need());
    assertEquals(1, sword.needLevel());
    assertEquals(50, sword.price());

    StdItem potion = StdItems.smallHealingPotion();
    assertEquals(0, potion.stdMode());
    assertEquals(102, potion.aniCount());
    assertEquals(398, potion.looks());
    assertEquals(1, potion.duraMax());
    assertEquals(30, potion.acMin(), "restore amount sits in the AC low word");
    assertEquals(0, potion.acMax());
    assertEquals(0, potion.needLevel());
    assertEquals(40, potion.price());

    StdItem ring = StdItems.revivalRing();
    assertEquals(22, ring.stdMode());
    assertEquals(114, ring.shape());
    assertEquals(175, ring.looks());
    assertEquals(5000, ring.duraMax());
    assertEquals(0, ring.acMin());
    assertEquals(1, ring.acMax(), "AC 0-1 (防御)");
    assertEquals(0, ring.macMin());
    assertEquals(1, ring.macMax(), "MAC 0-1 (魔御)");
    assertEquals(16, ring.needLevel());
    assertEquals(20000, ring.price());

    // Duplicate names collapse to the first row of the raw import (Delphi first-hit).
    List<StdItem> raw = StdItemsDb.parse(new BufferedReader(new java.io.InputStreamReader(
        Geem2BaselineImportTest.class.getResourceAsStream("/db/StdItemsDb.tsv"),
        java.nio.charset.StandardCharsets.UTF_8)));
    StdItem firstHelmet = raw.stream().filter(item -> item.name().equals("魔法头盔")).findFirst().orElseThrow();
    assertEquals(2, raw.stream().filter(item -> item.name().equals("魔法头盔")).count(),
        "the upstream duplicate is preserved in the TSV");
    assertEquals(firstHelmet, StdItemsDb.byName("魔法头盔").orElseThrow(), "first row wins");
    // The classic endgame weapon exists in the real import.
    assertTrue(StdItemsDb.byName("屠龙").isPresent());
  }

  @Test
  void loadersRejectMalformedRows() {
    assertThrows(IllegalStateException.class, () ->
        MonsterDb.parse(new BufferedReader(new StringReader("鸡\t51\t11\n"))));
    assertThrows(IllegalStateException.class, () ->
        MonsterDb.parse(new BufferedReader(new StringReader(
            "鸡\t51\t11\t160\t2\t0\t0\t9\tx\t0\t0\t0\t1\t1\t0\t0\t10\t3\t1400\t1\t0\t3000\n"))));
    assertThrows(IllegalStateException.class, () ->
        StdItemsDb.parse(new BufferedReader(new StringReader("1\t鸡肉\t40\n"))));
    assertThrows(IllegalStateException.class, () ->
        MonsterDropTable.parse(new BufferedReader(new StringReader("one/1 鸡肉 1\n")), "鸡"));
    assertThrows(IllegalStateException.class, () ->
        MonsterDropTable.parse(new BufferedReader(new StringReader("2/1\t鸡肉\t1\n")), "鸡"));
  }
}
