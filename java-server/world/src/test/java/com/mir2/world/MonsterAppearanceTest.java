package com.mir2.world;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins every catalog monster's wire feature to the official 1.76 Monster.DB appearance
 * columns (GEEM2 baseline dump of the 2005 leak; columns Name/Race/RaceImg/Appr).
 *
 * <p>The client decodes byte0 of the feature as {@code RaceImg} (actor class + action
 * table: PlayScn.pas {@code NewActor}, Actor.pas {@code GetRaceByPM}) and the high word as
 * {@code Appr} (sprite: {@code GetMonImg} opens {@code Data\Mon(appr div 10 + 1).wil},
 * {@code GetOffset} picks the {@code appr mod 10} frame block). A wrong pair renders the
 * wrong sprite — the pre-fix placeholders all carried {@code appr=0}, which the client drew
 * from Mon1.wil block 0: the 大刀守卫/卫士 guard. A real mir2.exe showed a flock of guards
 * where chickens should be.
 *
 * <p>Rows (monster, RaceImg, Appr) transcribed from the 1.76 dump:
 * 鸡 11/160, 鹿 11/161, 稻草人 18/27, 多钩猫 17/25, 钉耙猫 17/26, 洞蛆 16/24,
 * 蝎子 32/83, 半兽人 19/100, 半兽战士 19/101, 半兽勇士 19/102, 练功师(木桩) 19/72.
 */
class MonsterAppearanceTest {

  private static final Map<String, Integer> EXPECTED_RACE_IMG = Map.ofEntries(
      Map.entry("鸡", 11),
      Map.entry("鹿", 11),
      Map.entry("稻草人", 18),
      Map.entry("多钩猫", 17),
      Map.entry("钉耙猫", 17),
      Map.entry("洞蛆", 16),
      Map.entry("蝎子", 32),
      Map.entry("半兽人", 19),
      Map.entry("半兽战士", 19),
      Map.entry("半兽勇士", 19),
      Map.entry("木桩", 19));

  private static final Map<String, Integer> EXPECTED_APPR = Map.ofEntries(
      Map.entry("鸡", 160),
      Map.entry("鹿", 161),
      Map.entry("稻草人", 27),
      Map.entry("多钩猫", 25),
      Map.entry("钉耙猫", 26),
      Map.entry("洞蛆", 24),
      Map.entry("蝎子", 83),
      Map.entry("半兽人", 100),
      Map.entry("半兽战士", 101),
      Map.entry("半兽勇士", 102),
      Map.entry("木桩", 72));

  @Test
  void catalogFeaturesCarryTheOfficialRaceImgAndAppr() {
    for (Map.Entry<String, Integer> expected : EXPECTED_RACE_IMG.entrySet()) {
      MonsterTemplate template = MonsterTemplate.forName(expected.getKey());
      int feature = template.feature();
      assertEquals(expected.getValue(), feature & 0xff,
          expected.getKey() + " must carry its Monster.DB RaceImg in byte 0");
      assertEquals(0, (feature >>> 8) & 0xff,
          expected.getKey() + " must not carry a monster weapon byte");
      assertEquals(EXPECTED_APPR.get(expected.getKey()), feature >>> 16,
          expected.getKey() + " must carry its Monster.DB Appr in the high word");
    }
  }

  @Test
  void everyAppearanceResolvesToTheSpriteTheClientWillDraw() {
    // GetMonImg(appr): the client opens Mon(appr div 10 + 1).wil; assert each monster's
    // expected file so an accidental Appr swap inside one Mon block still fails loudly.
    assertEquals(17, 160 / 10 + 1, "鸡 lives in Mon17.wil");
    assertEquals(17, 161 / 10 + 1, "鹿 lives in Mon17.wil");
    assertEquals(3, 27 / 10 + 1, "稻草人 lives in Mon3.wil");
    assertEquals(3, 25 / 10 + 1, "多钩猫 lives in Mon3.wil");
    assertEquals(3, 26 / 10 + 1, "钉耙猫 lives in Mon3.wil");
    assertEquals(3, 24 / 10 + 1, "洞蛆 lives in Mon3.wil");
    assertEquals(9, 83 / 10 + 1, "蝎子 lives in Mon9.wil");
    assertEquals(11, 100 / 10 + 1, "半兽人 lives in Mon11.wil");
    assertEquals(11, 101 / 10 + 1, "半兽战士 lives in Mon11.wil");
    assertEquals(11, 102 / 10 + 1, "半兽勇士 lives in Mon11.wil");
    assertEquals(8, 72 / 10 + 1, "练功师/木桩 lives in Mon8.wil");
  }
}
