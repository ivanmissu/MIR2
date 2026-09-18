package com.mir2.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** PoC account/session service. Storage is intentionally replaceable before P1 SQLite work. */
public final class AuthService {
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();
  private final Map<String, String> sessions = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public void register(String username, String password) {
    validate(username, password);
    if (accounts.putIfAbsent(username, new Account(username, digest(password))) != null)
      throw new IllegalArgumentException("account already exists");
  }

  public String login(String username, String password) {
    Account account = accounts.get(username);
    if (account == null || !MessageDigest.isEqual(account.passwordDigest(), digest(password)))
      throw new SecurityException("invalid credentials");
    byte[] token = new byte[24]; random.nextBytes(token);
    String session = HexFormat.of().formatHex(token);
    sessions.put(session, username); return session;
  }

  public String accountFor(String session) { String account = sessions.get(session); if (account == null) throw new SecurityException("invalid session"); return account; }
  public void logout(String session) { sessions.remove(session); }
  private static byte[] digest(String password) { try { return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
  private static void validate(String u,String p) { if (u == null || u.isBlank() || u.length()>32 || p == null || p.length()<1) throw new IllegalArgumentException("invalid account"); }
}
