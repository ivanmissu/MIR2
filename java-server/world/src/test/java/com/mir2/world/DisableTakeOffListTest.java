package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The 禁止取下物品列表 {@code LoadDisableTakeOffList}/{@code InDisableTakeOffList} (M2Share.pas). */
class DisableTakeOffListTest {

  private static StdItem weapon(String name) {
    return new StdItem(name, 5, 0, 10, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void emptyListLocksNothing() {
    assertTrue(DisableTakeOffList.empty().isEmpty());
    assertFalse(DisableTakeOffList.empty().contains(weapon("屠龙")));
  }

  @Test
  void matchesByNameCaseInsensitively() {
    DisableTakeOffList list = DisableTakeOffList.of(Set.of("屠龙", "Legend Sword"));
    assertTrue(list.contains(weapon("屠龙")));
    assertTrue(list.contains(weapon("legend sword")));
    assertTrue(list.contains(weapon("LEGEND SWORD")));
    assertFalse(list.contains(weapon("裁决")));
  }

  @Test
  void nullItemNeverMatches() {
    DisableTakeOffList list = DisableTakeOffList.of(Set.of("屠龙"));
    assertFalse(list.contains(null));
  }

  @Test
  void parseSkipsBlankAndCommentLines() {
    String text = String.join("\n",
        "; this is a comment",
        "",
        "屠龙\t45",
        "裁决 / 46",
        "  怒斩,47  ",
        "; trailing comment");
    DisableTakeOffList list = DisableTakeOffList.parse(new StringReader(text));
    assertEquals(3, list.size());
    assertTrue(list.contains(weapon("屠龙")));
    assertTrue(list.contains(weapon("裁决")));
    assertTrue(list.contains(weapon("怒斩")));
  }

  @Test
  void parseAcceptsNameWithoutIndex() {
    DisableTakeOffList list = DisableTakeOffList.parse(new StringReader("屠龙\n"));
    assertEquals(1, list.size());
    assertTrue(list.contains(weapon("屠龙")));
  }

  @Test
  void parseEmptyYieldsEmptyList() {
    assertTrue(DisableTakeOffList.parse(new StringReader("; only a comment\n\n")).isEmpty());
  }
}
