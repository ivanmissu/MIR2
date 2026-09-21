package com.mir2.gate;

import com.mir2.character.Character;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges the Java auth token to the positive Integer certification used by mir2.exe. */
public final class GateSessionRegistry {
  /** {@code job} is Delphi's {@code m_btJob}; the world needs it for the level-up curves. */
  public record SelectedCharacter(UUID id, String name, int feature, int job) {
    public SelectedCharacter(UUID id, String name) {
      this(id, name, 0, 0);
    }

    /** Compatibility overload predating the job field; defaults to jWarr. */
    public SelectedCharacter(UUID id, String name, int feature) {
      this(id, name, feature, 0);
    }

    public SelectedCharacter {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      if (job < 0 || job > 2) throw new IllegalArgumentException("job must be 0..2");
    }

    private static SelectedCharacter from(Character character) {
      return new SelectedCharacter(
          character.id(), character.name(), character.feature(), character.job());
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

  /**
   * Validates all three values carried across LOGIN, SELECT, and GAME connections. This does
   * <b>not</b> consume the certification: a fast relog can leave the previous player object
   * mid-teardown in {@link com.mir2.world.WorldEngine}, so {@code LegacyGateHandler} retries a
   * fresh GAME connection with the very same certification until the world actually admits it
   * (see {@code Mir2Bot#openGameSession}'s {@code ENTER_ATTEMPTS} loop). Consuming the
   * certification is {@link #remove} instead, called once by the caller only after a retry
   * has actually succeeded.
   */
  public Session requireGame(String account, String characterName, int certification) {
    Session session = require(account, certification);
    if (session.selectedCharacter() == null
        || !session.selectedCharacter().name().equals(characterName)) {
      throw new SecurityException("character was not selected for this session");
    }
    return session;
  }

  /**
   * Drops a certification so it can never be validated again, whether by {@link #require},
   * {@link #select}, or {@link #requireGame}. Callers invoke this once a certification has
   * been spent on a successful GAME entry (the Delphi certification/session ticket in
   * {@code M2Server/IdSrvClient.pas} is likewise single-admission: {@code DelSession} tears
   * it down once its purpose is served) and also from connection-teardown paths so an
   * authenticated-but-abandoned certification cannot be replayed later. Idempotent: removing
   * an already-removed or unknown certification is a silent no-op.
   */
  public void remove(int certification) {
    sessions.remove(certification);
  }
}
