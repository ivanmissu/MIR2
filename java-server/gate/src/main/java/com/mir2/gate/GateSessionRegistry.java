package com.mir2.gate;

import com.mir2.character.Character;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges the Java auth token to the positive Integer certification used by mir2.exe. */
public final class GateSessionRegistry {
  public record SelectedCharacter(UUID id, String name, int feature) {
    public SelectedCharacter(UUID id, String name) {
      this(id, name, 0);
    }

    public SelectedCharacter {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
    }

    private static SelectedCharacter from(Character character) {
      return new SelectedCharacter(character.id(), character.name(), character.feature());
    }
  }

  public record Session(String account, String authToken, SelectedCharacter selectedCharacter) {
    public Session {
      Objects.requireNonNull(account, "account");
      Objects.requireNonNull(authToken, "authToken");
    }

    private Session select(Character character) {
      return new Session(account, authToken, SelectedCharacter.from(character));
    }
  }

  private final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public int register(String account, String authToken) {
    Session session = new Session(account, authToken, null);
    for (;;) {
      int certification = random.nextInt(1, Integer.MAX_VALUE);
      if (sessions.putIfAbsent(certification, session) == null) return certification;
    }
  }

  public Session require(String account, int certification) {
    Session session = sessions.get(certification);
    if (session == null || !session.account().equals(account)) {
      throw new SecurityException("invalid certification");
    }
    return session;
  }

  /** Records the character approved by CM_SELCHR so a GAME login cannot substitute another name. */
  public Session select(String account, int certification, Character character) {
    Objects.requireNonNull(character, "character");
    if (!account.equals(character.account())) {
      throw new SecurityException("character does not belong to account");
    }
    return sessions.compute(certification, (ignored, current) -> {
      if (current == null || !current.account().equals(account)) {
        throw new SecurityException("invalid certification");
      }
      return current.select(character);
    });
  }

  /** Validates all three values carried across LOGIN, SELECT, and GAME connections. */
  public Session requireGame(String account, String characterName, int certification) {
    Session session = require(account, certification);
    if (session.selectedCharacter() == null
        || !session.selectedCharacter().name().equals(characterName)) {
      throw new SecurityException("character was not selected for this session");
    }
    return session;
  }

  public void remove(int certification) {
    sessions.remove(certification);
  }
}
