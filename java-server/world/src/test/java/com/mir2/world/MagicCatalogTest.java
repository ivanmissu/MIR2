package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MagicCatalogTest {
  @Test
  void loadsTheVettedClassicOneThroughThirtyThreeIntersection() {
    MagicCatalog catalog = MagicCatalog.defaults();
    assertEquals(33, catalog.size());
    assertEquals("火球术", catalog.require(1).name());
    assertEquals("治愈术", catalog.require(2).name());
    assertEquals("魔法盾", catalog.require(31).name());
    assertEquals("冰咆哮", catalog.require(33).name());
  }

  @Test
  void delphiManaFormulaUsesTrainLevelFourAndBankersRounding() {
    MagicDefinition fireball = MagicCatalog.defaults().require(1);
    assertEquals(2, fireball.manaCost(0));
    assertEquals(3, fireball.manaCost(1));
    assertEquals(4, fireball.manaCost(2));
    assertEquals(5, fireball.manaCost(3));
    assertThrows(IllegalArgumentException.class, () -> fireball.manaCost(4));
  }
}
