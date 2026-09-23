package com.mir2.persistence;

import com.mir2.character.Character;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.Equipment;
import com.mir2.world.EquipmentSlot;
import com.mir2.world.ItemDatabase;
import com.mir2.world.PlayerState;
import com.mir2.world.StdItem;
import com.mir2.world.StdItems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreTest {
  @Test
  void characterAbilityAppearanceAndBackpackSurviveStoreReopen() throws Exception {
    Path file = Files.createTempFile("mir2-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    Character character = new Character(
        characterId, "hero", "传奇", 1, 1, 1, 3, 2, 4);
    Ability ability = new Ability(37, 120, 11, 30, 4, 10, 1, 3, 2, 9_001);
    // A partially worn sword plus full meat: exercises makeIndex, dura and duraMax columns.
    BackpackItem wornSword = new BackpackItem(StdItems.woodenSword(), 101, 7, 20);
    BackpackItem meat = BackpackItem.of(StdItems.chickenMeat(), 102);
    PlayerState playerState = new PlayerState(characterId, ability, List.of(wornSword, meat));

    try (SqliteStore store = new SqliteStore(url)) {
      store.saveAccount("hero", new byte[] {1, 2});
      store.save(character);
      store.save(playerState);
      assertEquals(102, store.itemMakeIndexHighWater());
    }

    try (SqliteStore reopened = new SqliteStore(url)) {
      assertArrayEquals(new byte[] {1, 2}, reopened.passwordDigest("hero").orElseThrow());
      Character restoredCharacter = reopened.list("hero").getFirst();
      assertEquals(2, restoredCharacter.level(), "world level must remain in sync with selection data");
      assertEquals(character.feature(), restoredCharacter.feature());
      assertEquals(playerState, reopened.load(characterId).orElseThrow());
      assertEquals(StdItems.woodenSword(), reopened.itemDatabase().find("木剑").orElseThrow());

      reopened.delete("hero", characterId);
      assertTrue(reopened.load(characterId).isEmpty(), "world rows must cascade with character deletion");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void seedsTheCatalogAndKeepsUnknownItemsVisibleAsPlaceholders() throws Exception {
    Path file = Files.createTempFile("mir2-catalog-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    try (SqliteStore store = new SqliteStore(url)) {
      store.saveAccount("a", new byte[] {1});
      store.save(new Character(characterId, "a", "收藏家", 0, 1, 1, 0, 0, 0));

      ItemDatabase catalog = store.itemDatabase();
      assertEquals(StdItems.chickenMeat(), catalog.find("鸡肉").orElseThrow());
      assertEquals(StdItems.woodenSword(), catalog.find("木剑").orElseThrow());
      // W18: the seeded catalog is the full 1.76 StdItems.DB import; 屠龙 is real now,
      // so the unknown-name placeholder case uses a name the DB genuinely lacks.
      assertTrue(catalog.find("屠龙").isPresent(), "the import carries the classic endgame items too");
      assertTrue(catalog.find("根本不存在之剑").isEmpty(), "the catalog must not invent items");

      // A bag item without a catalog entry survives a round trip as a placeholder template.
      BackpackItem relic = new BackpackItem(StdItem.placeholder("根本不存在之剑", 8), 900, 5, 5);
      store.save(new PlayerState(characterId, Ability.defaultPlayer(), List.of(relic)));
    }
    try (SqliteStore reopened = new SqliteStore(url)) {
      BackpackItem restored = reopened.load(characterId).orElseThrow().backpack().getFirst();
      assertEquals("根本不存在之剑", restored.name());
      assertEquals(8, restored.looks());
      assertEquals(900, restored.makeIndex());
      assertEquals(5, restored.dura());
      assertEquals(StdItem.placeholder("根本不存在之剑", 8), restored.item());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void upgradesW18CatalogRowsBySplittingReservedFromNeedIdentify() throws Exception {
    Path file = Files.createTempFile("mir2-w18-catalog-", ".db");
    String url = "jdbc:sqlite:" + file;
    StdItem prayerBlade = StdItems.require("祈祷之刃");
    try (var connection = DriverManager.getConnection(url);
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE std_items (
            name TEXT PRIMARY KEY,
            std_mode INTEGER NOT NULL,
            shape INTEGER NOT NULL,
            weight INTEGER NOT NULL,
            ani_count INTEGER NOT NULL,
            source INTEGER NOT NULL,
            need_identify INTEGER NOT NULL,
            looks INTEGER NOT NULL,
            dura_max INTEGER NOT NULL,
            ac INTEGER NOT NULL,
            mac INTEGER NOT NULL,
            dc INTEGER NOT NULL,
            mc INTEGER NOT NULL,
            sc INTEGER NOT NULL,
            need INTEGER NOT NULL,
            need_level INTEGER NOT NULL,
            price INTEGER NOT NULL)
          """);
      try (var insert = connection.prepareStatement("""
          INSERT INTO std_items(
            name, std_mode, shape, weight, ani_count, source, need_identify, looks,
            dura_max, ac, mac, dc, mc, sc, need, need_level, price)
          VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """)) {
        insert.setString(1, prayerBlade.name());
        insert.setInt(2, prayerBlade.stdMode());
        insert.setInt(3, prayerBlade.shape());
        insert.setInt(4, prayerBlade.weight());
        insert.setInt(5, prayerBlade.aniCount());
        insert.setInt(6, prayerBlade.source());
        // W18 stored the DB's Reserved byte in need_identify because the model had no
        // separate Reserved field yet.
        insert.setInt(7, prayerBlade.reserved());
        insert.setInt(8, prayerBlade.looks());
        insert.setLong(9, prayerBlade.duraMax());
        insert.setLong(10, prayerBlade.ac());
        insert.setLong(11, prayerBlade.mac());
        insert.setLong(12, prayerBlade.dc());
        insert.setLong(13, prayerBlade.mc());
        insert.setLong(14, prayerBlade.sc());
        insert.setLong(15, prayerBlade.need());
        insert.setLong(16, prayerBlade.needLevel());
        insert.setLong(17, prayerBlade.price());
        insert.executeUpdate();
      }
    }

    try (SqliteStore upgraded = new SqliteStore(url)) {
      StdItem restored = upgraded.itemDatabase().find("祈祷之刃").orElseThrow();
      assertEquals(8, restored.reserved());
      assertEquals(0, restored.needIdentify(), "NeedIdentify must be restored to the Delphi loader default");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void accountAndCharacterAreSeparated() {
    try (SqliteStore store = new SqliteStore("jdbc:sqlite::memory:")) {
      store.saveAccount("a", new byte[] {3});
      assertTrue(store.passwordDigest("b").isEmpty());
      assertThrows(NoSuchElementException.class,
          () -> store.delete("a", UUID.randomUUID()));
      assertThrows(NoSuchElementException.class,
          () -> store.save(PlayerState.initial(UUID.randomUUID())));
    }
  }

  @Test
  void upgradesTheW02CharacterSchemaAndBackfillsDefaultWorldState() throws Exception {
    Path file = Files.createTempFile("mir2-old-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    try (var connection = DriverManager.getConnection(url);
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE accounts (
            username TEXT PRIMARY KEY, password_digest BLOB NOT NULL, created_at INTEGER NOT NULL)
          """);
      statement.executeUpdate("""
          CREATE TABLE characters (
            id TEXT PRIMARY KEY,
            account TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE,
            name TEXT NOT NULL COLLATE BINARY,
            job INTEGER NOT NULL,
            level INTEGER NOT NULL,
            UNIQUE(account, name))
          """);
      statement.executeUpdate("INSERT INTO accounts VALUES('old', X'01', 1)");
      statement.executeUpdate("INSERT INTO characters VALUES('" + characterId
          + "', 'old', '旧角色', 2, 7)");
    }

    try (SqliteStore upgraded = new SqliteStore(url)) {
      Character character = upgraded.list("old").getFirst();
      assertEquals(0, character.gender());
      assertEquals(0, character.hair());
      assertEquals(0, character.dressShape());
      assertEquals(0, character.weaponShape());
      PlayerState state = upgraded.load(characterId).orElseThrow();
      assertEquals(7, state.ability().level());
      assertEquals(Ability.defaultPlayer().maxHp(), state.ability().maxHp());
      assertFalse(state.backpack().iterator().hasNext());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void upgradesW03InventoryRowsInPlaceAndKeepsTheirItems() throws Exception {
    Path file = Files.createTempFile("mir2-w03-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    try (var connection = DriverManager.getConnection(url);
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE accounts (
            username TEXT PRIMARY KEY, password_digest BLOB NOT NULL, created_at INTEGER NOT NULL)
          """);
      statement.executeUpdate("""
          CREATE TABLE characters (
            id TEXT PRIMARY KEY,
            account TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE,
            name TEXT NOT NULL COLLATE BINARY,
            job INTEGER NOT NULL,
            level INTEGER NOT NULL,
            gender INTEGER NOT NULL DEFAULT 0,
            hair INTEGER NOT NULL DEFAULT 0,
            dress_shape INTEGER NOT NULL DEFAULT 0,
            weapon_shape INTEGER NOT NULL DEFAULT 0,
            UNIQUE(account, name))
          """);
      statement.executeUpdate("""
          CREATE TABLE character_state (
            character_id TEXT PRIMARY KEY REFERENCES characters(id) ON DELETE CASCADE,
            hp INTEGER NOT NULL, max_hp INTEGER NOT NULL, mp INTEGER NOT NULL, max_mp INTEGER NOT NULL,
            min_dc INTEGER NOT NULL, max_dc INTEGER NOT NULL, min_ac INTEGER NOT NULL, max_ac INTEGER NOT NULL,
            level INTEGER NOT NULL, experience INTEGER NOT NULL)
          """);
      // The W03 schema: slots carried only name and looks.
      statement.executeUpdate("""
          CREATE TABLE character_inventory (
            character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
            slot INTEGER NOT NULL,
            name TEXT NOT NULL,
            looks INTEGER NOT NULL,
            PRIMARY KEY(character_id, slot))
          """);
      statement.executeUpdate("INSERT INTO accounts VALUES('old', X'01', 1)");
      statement.executeUpdate("INSERT INTO characters VALUES('" + characterId
          + "', 'old', '老兵', 0, 3, 0, 0, 0, 0)");
      statement.executeUpdate("INSERT INTO character_state VALUES('" + characterId
          + "', 60, 100, 15, 20, 3, 8, 0, 2, 3, 500)");
      statement.executeUpdate("INSERT INTO character_inventory VALUES('" + characterId
          + "', 0, '鸡肉', 41)");
    }

    try (SqliteStore upgraded = new SqliteStore(url)) {
      BackpackItem restored = upgraded.load(characterId).orElseThrow().backpack().getFirst();
      // W03 rows predate the catalog join and the make index: the item stays visible with
      // a full template from the seeded catalog and an unassigned make index of zero.
      assertEquals("鸡肉", restored.name());
      assertEquals(0, restored.makeIndex());
      assertEquals(StdItems.chickenMeat(), restored.item());

      // Persisting a stabilised make index (the engine renumbers on restore) round trips.
      BackpackItem stabilised = restored.withMakeIndex(55);
      upgraded.save(new PlayerState(characterId, Ability.defaultPlayer(), List.of(stabilised)));
      assertEquals(55, upgraded.load(characterId).orElseThrow().backpack().getFirst().makeIndex());
      assertEquals(55, upgraded.itemMakeIndexHighWater());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void wornEquipmentRoundTripsAndPreW12DatabasesGainTheTableOnOpen() throws Exception {
    Path file = Files.createTempFile("mir2-w12-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    BackpackItem dress = new BackpackItem(StdItems.woodenSword(), 201, 18, 20);
    BackpackItem bagged = BackpackItem.of(StdItems.chickenMeat(), 202);

    try (SqliteStore store = new SqliteStore(url)) {
      store.saveAccount("w12", new byte[] {9});
      store.save(new Character(characterId, "w12", "铁匠", 0, 5, 0, 1, 0, 0));

      Equipment equipment = Equipment.empty().with(EquipmentSlot.WEAPON, dress);
      store.save(new PlayerState(
          characterId, Ability.defaultPlayer(), List.of(bagged), equipment));
      // The high-water mark has to consider worn items too, or a relog would re-issue 201.
      assertEquals(202, store.itemMakeIndexHighWater());
    }

    try (SqliteStore reopened = new SqliteStore(url)) {
      PlayerState restored = reopened.load(characterId).orElseThrow();
      BackpackItem worn = restored.equipment().at(EquipmentSlot.WEAPON).orElseThrow();
      assertEquals("木剑", worn.name());
      assertEquals(201, worn.makeIndex());
      assertEquals(18, worn.dura(), "partial durability must survive the round trip");
      assertEquals(StdItems.woodenSword(), worn.item());
      assertEquals(List.of("鸡肉"), restored.backpack().stream().map(BackpackItem::name).toList());

      // Taking the item off again clears the row instead of leaving a stale slot behind.
      reopened.save(restored.withEquipment(Equipment.empty()));
      assertTrue(reopened.load(characterId).orElseThrow().equipment().isEmpty());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void goldRoundTripsAndPreW15DatabasesDefaultToAnEmptyWallet() throws Exception {
    Path file = Files.createTempFile("mir2-w15-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();

    // A W14-era database: character_state exists but has no gold column yet.
    try (SqliteStore legacy = new SqliteStore(url)) {
      legacy.saveAccount("w15", new byte[] {7});
      legacy.save(new Character(characterId, "w15", "商人", 0, 5, 0, 1, 0, 0));
    }
    try (java.sql.Connection raw = DriverManager.getConnection(url);
        Statement statement = raw.createStatement()) {
      statement.executeUpdate("ALTER TABLE character_state DROP COLUMN gold");
    }

    // Opening the legacy file upgrades it in place; the wallet defaults to zero.
    try (SqliteStore upgraded = new SqliteStore(url)) {
      PlayerState restored = upgraded.load(characterId).orElseThrow();
      assertEquals(0, restored.gold());

      // A repair's deduction and the resulting durability both survive a reopen.
      BackpackItem sword = new BackpackItem(StdItems.woodenSword(), 301, 12, 18);
      upgraded.save(new PlayerState(
          characterId, Ability.defaultPlayer(), List.of(sword), Equipment.empty(), 4_500));
    }
    try (SqliteStore reopened = new SqliteStore(url)) {
      PlayerState restored = reopened.load(characterId).orElseThrow();
      assertEquals(4_500, restored.gold());
      assertEquals(18, restored.backpack().getFirst().duraMax());
      assertEquals(restored.withGold(0), new PlayerState(
          restored.characterId(), restored.ability(), restored.backpack(),
          restored.equipment(), 0));
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
