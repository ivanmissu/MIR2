package com.mir2.gate;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges the Java auth token to the positive Integer certification used by mir2.exe. */
public final class GateSessionRegistry {
  public record Session(String account, String authToken) {
    public Session {
      Objects.requireNonNull(account, "account");
      Objects.requireNonNull(authToken, "authToken");
    }
  }

  private final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public int register(String account, String authToken) {
    Session session = new Session(account, authToken);
    for (;;) {
      int certification = random.nextInt(1, Integer.MAX_VALUE);
      if (sessions.putIfAbsent(certification, session) == null) return certification;
    }
  }

  public Session require(String account, int certification) {
    Session session = sessions.get(certification);
    if (session == null || !session.account().equals(account)) throw new SecurityException("invalid certification");
    return session;
  }

  public void remove(int certification) {
    sessions.remove(certification);
  }
}
