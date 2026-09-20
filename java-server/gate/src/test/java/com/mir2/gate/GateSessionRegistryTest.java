package com.mir2.gate;

import com.mir2.character.Character;
import com.mir2.character.CharacterService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GateSessionRegistryTest {
  @Test
  void requireGameDoesNotConsumeTheCertificationByItself() {
    GateSessionRegistry sessions = new GateSessionRegistry();
    Character character = createCharacter("hero", "战士");
    int certification = sessions.register("hero", "token");
    sessions.select("hero", certification, character);

    // requireGame is used by both LOGIN/SELECT re-validation and the GAME first-packet
    // handshake; it must stay a pure check so the certification survives across the three
    // separate TCP connections of the real handshake (login -> select -> game).
    GateSessionRegistry.Session first = sessions.requireGame("hero", "战士", certification);
    assertEquals("战士", first.selectedCharacter().name());
    GateSessionRegistry.Session second = sessions.requireGame("hero", "战士", certification);
    assertEquals(first.selectedCharacter().name(), second.selectedCharacter().name());
  }

  @Test
  void removeInvalidatesTheCertificationForEveryLookup() {
    GateSessionRegistry sessions = new GateSessionRegistry();
    Character character = createCharacter("hero", "战士");
    int certification = sessions.register("hero", "token");
    sessions.select("hero", certification, character);

    sessions.remove(certification);

    assertThrows(SecurityException.class, () -> sessions.require("hero", certification));
    assertThrows(SecurityException.class,
        () -> sessions.requireGame("hero", "战士", certification));
    assertThrows(SecurityException.class,
        () -> sessions.select("hero", certification, character));
  }

  @Test
  void removeIsIdempotentForUnknownOrAlreadyRemovedCertifications() {
    GateSessionRegistry sessions = new GateSessionRegistry();
    // Never registered.
    assertDoesNotThrow(() -> sessions.remove(123456));

    int certification = sessions.register("hero", "token");
    sessions.remove(certification);
    // Removing twice must stay a silent no-op (disconnect cleanup may race a successful
    // consumption on entry, see LegacyGateHandler#serveGame).
    assertDoesNotThrow(() -> sessions.remove(certification));
  }

  @Test
  void requireGameRejectsAnUnselectedOrMismatchedCharacter() {
    GateSessionRegistry sessions = new GateSessionRegistry();
    int certification = sessions.register("hero", "token");

    // CM_SELCHR was never sent for this certification.
    assertThrows(SecurityException.class,
        () -> sessions.requireGame("hero", "战士", certification));

    sessions.select("hero", certification, createCharacter("hero", "战士"));
    assertThrows(SecurityException.class,
        () -> sessions.requireGame("hero", "法师", certification));
  }

  private static Character createCharacter(String account, String name) {
    CharacterService characters = new CharacterService();
    return characters.create(account, name, 0);
  }
}
