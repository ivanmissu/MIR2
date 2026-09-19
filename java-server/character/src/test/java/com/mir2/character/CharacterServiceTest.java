package com.mir2.character;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterServiceTest {
  @Test
  void createListDelete() {
    CharacterService service = new CharacterService();
    Character character = service.create("a", "传奇", 1);
    assertEquals(1, service.list("a").size());
    service.delete("a", character.id());
    assertTrue(service.list("a").isEmpty());
    assertThrows(NoSuchElementException.class, () -> service.delete("a", character.id()));
  }

  @Test
  void namesAreLimitedByGbkBytes() {
    CharacterService service = new CharacterService();
    assertDoesNotThrow(() -> service.create("a", "传奇英雄", 1));
    assertThrows(IllegalArgumentException.class,
        () -> service.create("a", "传奇英雄名字太长", 1));
  }

  @Test
  void creationAppearanceBuildsTheDelphiHumanFeature() {
    Character character = new CharacterService().create("a", "女战士", 0, 3, 1);
    assertEquals(1, character.gender());
    assertEquals(3, character.hair());
    // MakeHumanFeature(0, gender, gender, hair * 2 + gender)
    assertEquals(0x01070100, character.feature());
    assertThrows(IllegalArgumentException.class,
        () -> new CharacterService().create("a", "坏发型", 0, 128, 0));
  }
}
