package com.mir2.persistence;

import com.mir2.character.Character;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC persistence boundary for accounts, character selection data and durable world state. */
public final class SqliteStore implements AutoCloseable,
    com.mir2.auth.AccountStore,
    com.mir2.character.CharacterStore,
    PlayerStateStore {

  private final Connection connection;

  public SqliteStore(String jdbcUrl) {
    try {
      connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl));
      connection.setAutoCommit(true);
      initialise();
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  public SqliteStore(Connection connection) {
    this.connection = Objects.requireNonNull(connection);
    try {
      connection.setAutoCommit(true);
      initialise();
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  private void initialise() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("PRAGMA foreign_keys = ON");
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS accounts (
            username TEXT PRIMARY KEY,
            password_digest BLOB NOT NULL,
            created_at INTEGER NOT NULL
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS characters (
            id TEXT PRIMARY KEY,
            account TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE,
            name TEXT NOT NULL COLLATE BINARY,
            job INTEGER NOT NULL,
            level INTEGER NOT NULL,
            gender INTEGER NOT NULL DEFAULT 0,
            hair INTEGER NOT NULL DEFAULT 0,
            dress_shape INTEGER NOT NULL DEFAULT 0,
            weapon_shape INTEGER NOT NULL DEFAULT 0,
            UNIQUE(account, name)
          )
          """);
    }

    // Upgrade databases created by the W02 schema in place.
    ensureColumn("characters", "gender", "INTEGER NOT NULL DEFAULT 0");
    ensureColumn("characters", "hair", "INTEGER NOT NULL DEFAULT 0");
    ensureColumn("characters", "dress_shape", "INTEGER NOT NULL DEFAULT 0");
    ensureColumn("characters", "weapon_shape", "INTEGER NOT NULL DEFAULT 0");

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS character_state (
            character_id TEXT PRIMARY KEY REFERENCES characters(id) ON DELETE CASCADE,
            hp INTEGER NOT NULL,
            max_hp INTEGER NOT NULL,
            mp INTEGER NOT NULL,
            max_mp INTEGER NOT NULL,
            min_dc INTEGER NOT NULL,
            max_dc INTEGER NOT NULL,
            min_ac INTEGER NOT NULL,
            max_ac INTEGER NOT NULL,
            level INTEGER NOT NULL,
            experience INTEGER NOT NULL
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS character_inventory (
            character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
            slot INTEGER NOT NULL,
            name TEXT NOT NULL,
            looks INTEGER NOT NULL,
            PRIMARY KEY(character_id, slot)
          )
          """);
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_characters_account ON characters(account)");
      statement.executeUpdate("""
          INSERT OR IGNORE INTO character_state(
            character_id, hp, max_hp, mp, max_mp, min_dc, max_dc, min_ac, max_ac, level, experience)
          SELECT id, 100, 100, 20, 20, 3, 8, 0, 2, level, 0 FROM characters
          """);
    }
  }

  private void ensureColumn(String table, String column, String definition) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (result.next()) columns.add(result.getString("name"));
    }
    if (columns.add(column)) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
      }
    }
  }

  @Override
  public synchronized void save(com.mir2.auth.Account account) {
    saveAccount(account.username(), account.passwordDigest());
  }

  @Override
  public synchronized Optional<com.mir2.auth.Account> find(String username) {
    return passwordDigest(username)
        .map(digest -> new com.mir2.auth.Account(username, digest));
  }

  public synchronized void saveAccount(String username, byte[] digest) {
    requireUsername(username);
    Objects.requireNonNull(digest, "digest");
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO accounts(username, password_digest, created_at) VALUES(?, ?, ?)
        """)) {
      statement.setString(1, username);
      statement.setBytes(2, digest.clone());
      statement.setLong(3, System.currentTimeMillis());
      statement.executeUpdate();
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  public synchronized Optional<byte[]> passwordDigest(String username) {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT password_digest FROM accounts WHERE username = ?")) {
      statement.setString(1, username);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  @Override
  public synchronized Character save(Character character) {
    Objects.requireNonNull(character, "character");
    try {
      return transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO characters(
              id, account, name, job, level, gender, hair, dress_shape, weapon_shape)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
          statement.setString(1, character.id().toString());
          statement.setString(2, character.account());
          statement.setString(3, character.name());
          statement.setInt(4, character.job());
          statement.setInt(5, character.level());
          statement.setInt(6, character.gender());
          statement.setInt(7, character.hair());
          statement.setInt(8, character.dressShape());
          statement.setInt(9, character.weaponShape());
          statement.executeUpdate();
        }
        insertInitialState(character.id(), character.level());
        return character;
      });
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  private void insertInitialState(UUID characterId, int level) throws SQLException {
    Ability defaults = Ability.defaultPlayer();
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO character_state(
          character_id, hp, max_hp, mp, max_mp, min_dc, max_dc, min_ac, max_ac, level, experience)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      bindAbility(statement, characterId, defaults, level);
      statement.executeUpdate();
    }
  }

  @Override
  public synchronized List<Character> list(String account) {
    List<Character> characters = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT id, name, job, level, gender, hair, dress_shape, weapon_shape
        FROM characters WHERE account = ? ORDER BY rowid
        """)) {
      statement.setString(1, account);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          characters.add(new Character(
              UUID.fromString(result.getString("id")),
              account,
              result.getString("name"),
              result.getInt("job"),
              result.getInt("level"),
              result.getInt("gender"),
              result.getInt("hair"),
              result.getInt("dress_shape"),
              result.getInt("weapon_shape")));
        }
      }
      return List.copyOf(characters);
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  @Override
  public synchronized void delete(String account, UUID id) {
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM characters WHERE account = ? AND id = ?")) {
      statement.setString(1, account);
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1) throw new NoSuchElementException("character not found");
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  @Override
  public synchronized Optional<PlayerState> load(UUID characterId) {
    Objects.requireNonNull(characterId, "characterId");
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT hp, max_hp, mp, max_mp, min_dc, max_dc, min_ac, max_ac, level, experience
        FROM character_state WHERE character_id = ?
        """)) {
      statement.setString(1, characterId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return Optional.empty();
        Ability ability = new Ability(
            result.getInt("hp"),
            result.getInt("max_hp"),
            result.getInt("mp"),
            result.getInt("max_mp"),
            result.getInt("min_dc"),
            result.getInt("max_dc"),
            result.getInt("min_ac"),
            result.getInt("max_ac"),
            result.getInt("level"),
            result.getLong("experience"));
        return Optional.of(new PlayerState(characterId, ability, loadBackpack(characterId)));
      }
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  private List<BackpackItem> loadBackpack(UUID characterId) throws SQLException {
    List<BackpackItem> backpack = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT name, looks FROM character_inventory
        WHERE character_id = ? ORDER BY slot
        """)) {
      statement.setString(1, characterId.toString());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          backpack.add(new BackpackItem(result.getString("name"), result.getInt("looks")));
        }
      }
    }
    return List.copyOf(backpack);
  }

  @Override
  public synchronized void save(PlayerState state) {
    Objects.requireNonNull(state, "state");
    try {
      transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE characters SET level = ? WHERE id = ?")) {
          statement.setInt(1, state.ability().level());
          statement.setString(2, state.characterId().toString());
          if (statement.executeUpdate() != 1) {
            throw new NoSuchElementException("character not found: " + state.characterId());
          }
        }
        upsertAbility(state.characterId(), state.ability());
        replaceBackpack(state.characterId(), state.backpack());
        return null;
      });
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  private void upsertAbility(UUID characterId, Ability ability) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO character_state(
          character_id, hp, max_hp, mp, max_mp, min_dc, max_dc, min_ac, max_ac, level, experience)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(character_id) DO UPDATE SET
          hp = excluded.hp,
          max_hp = excluded.max_hp,
          mp = excluded.mp,
          max_mp = excluded.max_mp,
          min_dc = excluded.min_dc,
          max_dc = excluded.max_dc,
          min_ac = excluded.min_ac,
          max_ac = excluded.max_ac,
          level = excluded.level,
          experience = excluded.experience
        """)) {
      bindAbility(statement, characterId, ability, ability.level());
      statement.executeUpdate();
    }
  }

  private static void bindAbility(
      PreparedStatement statement, UUID characterId, Ability ability, int level) throws SQLException {
    statement.setString(1, characterId.toString());
    statement.setInt(2, ability.hp());
    statement.setInt(3, ability.maxHp());
    statement.setInt(4, ability.mp());
    statement.setInt(5, ability.maxMp());
    statement.setInt(6, ability.minDc());
    statement.setInt(7, ability.maxDc());
    statement.setInt(8, ability.minAc());
    statement.setInt(9, ability.maxAc());
    statement.setInt(10, level);
    statement.setLong(11, ability.experience());
  }

  private void replaceBackpack(UUID characterId, List<BackpackItem> backpack) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM character_inventory WHERE character_id = ?")) {
      statement.setString(1, characterId.toString());
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO character_inventory(character_id, slot, name, looks) VALUES(?, ?, ?, ?)
        """)) {
      for (int slot = 0; slot < backpack.size(); slot++) {
        BackpackItem item = backpack.get(slot);
        statement.setString(1, characterId.toString());
        statement.setInt(2, slot);
        statement.setString(3, item.name());
        statement.setInt(4, item.looks());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private <T> T transaction(SqlSupplier<T> work) throws SQLException {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      T result = work.get();
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException error) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        error.addSuppressed(rollbackFailure);
      }
      throw error;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException error) {
      throw failure(error);
    }
  }

  private static void requireUsername(String username) {
    if (username == null || username.isBlank()) throw new IllegalArgumentException("blank username");
  }

  private static IllegalStateException failure(SQLException error) {
    return new IllegalStateException("SQLite persistence failure: " + error.getMessage(), error);
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws SQLException;
  }
}
