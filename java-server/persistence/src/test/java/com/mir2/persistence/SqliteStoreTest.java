package com.mir2.persistence;

import com.mir2.character.Character;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.PlayerState;
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
    PlayerState playerState = new PlayerState(characterId, ability,
        List.of(new BackpackItem("鸡肉", 41), new BackpackItem("木剑", 5)));

    try (SqliteStore store = new SqliteStore(url)) {
      store.saveAccount("hero", new byte[] {1, 2});
      store.save(character);
      store.save(playerState);
    }

    try (SqliteStore reopened = new SqliteStore(url)) {
      assertArrayEquals(new byte[] {1, 2}, reopened.passwordDigest("hero").orElseThrow());
      Character restoredCharacter = reopened.list("hero").getFirst();
      assertEquals(2, restoredCharacter.level(), "world level must remain in sync with selection data");
      assertEquals(character.feature(), restoredCharacter.feature());
      assertEquals(playerState, reopened.load(characterId).orElseThrow());

      reopened.delete("hero", characterId);
      assertTrue(reopened.load(characterId).isEmpty(), "world rows must cascade with character deletion");
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
}
