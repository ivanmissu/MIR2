package com.mir2.persistence;

import com.mir2.character.Character;
import java.sql.*;
import java.util.*;

/** Small JDBC persistence boundary for the P1 account/character slice. */
public final class SqliteStore implements AutoCloseable, com.mir2.auth.AccountStore, com.mir2.character.CharacterStore {
  private final Connection connection;
  public SqliteStore(String jdbcUrl) { try { connection=DriverManager.getConnection(Objects.requireNonNull(jdbcUrl)); connection.setAutoCommit(true); initialise(); } catch(SQLException e){throw failure(e);} }
  public SqliteStore(Connection connection) { this.connection=Objects.requireNonNull(connection); try{initialise();}catch(SQLException e){throw failure(e);} }
  private void initialise() throws SQLException { try(Statement s=connection.createStatement()){s.executeUpdate("PRAGMA foreign_keys = ON");s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY, password_digest BLOB NOT NULL, created_at INTEGER NOT NULL)");s.executeUpdate("CREATE TABLE IF NOT EXISTS characters (id TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE, name TEXT NOT NULL COLLATE BINARY, job INTEGER NOT NULL, level INTEGER NOT NULL, UNIQUE(account,name))");s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_characters_account ON characters(account)");} }
  @Override public synchronized void save(com.mir2.auth.Account account) { saveAccount(account.username(), account.passwordDigest()); }
  @Override public synchronized Optional<com.mir2.auth.Account> find(String username) { return passwordDigest(username).map(d -> new com.mir2.auth.Account(username, d)); }
  public synchronized void saveAccount(String username, byte[] digest) { require(username); try(PreparedStatement p=connection.prepareStatement("INSERT INTO accounts(username,password_digest,created_at) VALUES(?,?,?)")){p.setString(1,username);p.setBytes(2,digest.clone());p.setLong(3,System.currentTimeMillis());p.executeUpdate();}catch(SQLException e){throw failure(e);} }
  public synchronized Optional<byte[]> passwordDigest(String username) { try(PreparedStatement p=connection.prepareStatement("SELECT password_digest FROM accounts WHERE username=?")){p.setString(1,username);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(r.getBytes(1)):Optional.empty();}}catch(SQLException e){throw failure(e);} }
  @Override public synchronized Character save(Character c) { try(PreparedStatement p=connection.prepareStatement("INSERT INTO characters(id,account,name,job,level) VALUES(?,?,?,?,?)")){p.setString(1,c.id().toString());p.setString(2,c.account());p.setString(3,c.name());p.setInt(4,c.job());p.setInt(5,c.level());p.executeUpdate();return c;}catch(SQLException e){throw failure(e);} }
  @Override public synchronized List<Character> list(String account) {List<Character> result=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT id,name,job,level FROM characters WHERE account=? ORDER BY rowid")){p.setString(1,account);try(ResultSet r=p.executeQuery()){while(r.next())result.add(new Character(UUID.fromString(r.getString(1)),account,r.getString(2),r.getInt(3),r.getInt(4)));}return List.copyOf(result);}catch(SQLException e){throw failure(e);} }
  @Override public synchronized void delete(String account,UUID id) {try(PreparedStatement p=connection.prepareStatement("DELETE FROM characters WHERE account=? AND id=?")){p.setString(1,account);p.setString(2,id.toString());if(p.executeUpdate()!=1)throw new NoSuchElementException("character not found");}catch(SQLException e){throw failure(e);} }
  @Override public synchronized void close(){try{connection.close();}catch(SQLException e){throw failure(e);}}
  private static void require(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("blank username");}
  private static IllegalStateException failure(SQLException e){return new IllegalStateException("SQLite persistence failure: "+e.getMessage(),e);}
}
