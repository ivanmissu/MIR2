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

  @Test
  void registerRetiresTheAccountsPreviousCertification() {
    GateSessionRegistry sessions = new GateSessionRegistry();
    Character character = createCharacter("hero", "战士");
    int first = sessions.register("hero", "token");
    sessions.select("hero", first, character);

    // Since the certification outlives the GAME entry (the soft-close re-select flow needs
    // it), a fresh CM_IDPASSWORD for the same account is what retires the abandoned ticket
    // — mirroring the login server closing the older session of a reconnecting account.
    int second = sessions.register("hero", "token");

    assertNotEquals(first, second);
    assertThrows(SecurityException.class, () -> sessions.require("hero", first));
    assertEquals("战士", sessions.require("hero", second).selectedCharacter() == null
        ? null : sessions.require("hero", second).selectedCharacter().name());

    // Other accounts are untouched by the eviction.
    int other = sessions.register("other", "token");
    assertDoesNotThrow(() -> sessions.require("other", other));
  }

  private static Character createCharacter(String account, String name) {
    CharacterService characters = new CharacterService();
    return characters.create(account, name, 0);
  }
}
