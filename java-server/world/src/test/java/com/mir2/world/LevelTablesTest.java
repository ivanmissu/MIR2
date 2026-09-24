package com.mir2.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W14: the level tables transcribed from {@code g_dwOldNeedExps} (M2Share.pas:2216) and
 * {@code TBaseObject.RecalcLevelAbilitys} (ObjBase.pas:1869).
 */
class LevelTablesTest {

  @Test
  void experienceTableMatchesTheShippedNeedExpsArray() {
    // Spot checks across the whole shipped array, including its irregular jumps.
    assertEquals(100L, LevelExperience.forLevel(1));
    assertEquals(200L, LevelExperience.forLevel(2));
    assertEquals(2_500L, LevelExperience.forLevel(9));
    // Level 10 breaks the smooth ramp: 2,500 -> 6,000.
    assertEquals(6_000L, LevelExperience.forLevel(10));
    assertEquals(140_000L, LevelExperience.forLevel(20));
    assertEquals(2_000_000L, LevelExperience.forLevel(30));
    assertEquals(12_000_000L, LevelExperience.forLevel(40));
    // 45 -> 46 is the notorious 4x wall before the plateau.
    assertEquals(120_000_000L, LevelExperience.forLevel(45));
    assertEquals(480_000_000L, LevelExperience.forLevel(46));
    assertEquals(3_000_000_000L, LevelExperience.forLevel(48));
    assertEquals(4_000_000_000L, LevelExperience.forLevel(50));
  }

  @Test
  void experienceTablePlateausAtFourBillionAndClampsAboveMaxLevel() {
    // Levels 51..500 all carry the same 4,000,000,000 entry in the shipped array.
    assertEquals(4_000_000_000L, LevelExperience.forLevel(51));
    assertEquals(4_000_000_000L, LevelExperience.forLevel(255));
    assertEquals(4_000_000_000L, LevelExperience.forLevel(LevelExperience.MAX_LEVEL));
    // GetLevelExp clamps anything above MAXLEVEL to the last array entry.
    assertEquals(4_000_000_000L, LevelExperience.forLevel(LevelExperience.MAX_LEVEL + 1));
    assertEquals(4_000_000_000L, LevelExperience.forLevel(LevelExperience.MAX_UP_LEVEL));
    // The plateau overflows a signed int, which is why the table is kept in longs.
    assertTrue(LevelExperience.forLevel(50) > Integer.MAX_VALUE);
    // Level 0 has no Delphi entry; the server always normalises to 1 first.
    assertEquals(100L, LevelExperience.forLevel(0));
    assertThrows(IllegalArgumentException.class, () -> LevelExperience.forLevel(-1));
  }

  @Test
  void warriorCurveMatchesRecalcLevelAbilitys() {
    int job = LevelAbilities.JOB_WARRIOR;
    // MaxHP = 14 + Round((L/4 + 4.5 + L/20) * L)
    assertEquals(19, LevelAbilities.maxHp(job, 1));
    assertEquals(24, LevelAbilities.maxHp(job, 2));
    assertEquals(89, LevelAbilities.maxHp(job, 10));
    assertEquals(989, LevelAbilities.maxHp(job, 50));
    // MaxMP = 11 + Round(L * 3.5) — the warrior's mana barely grows.
    assertEquals(15, LevelAbilities.maxMp(job, 1));
    assertEquals(46, LevelAbilities.maxMp(job, 10));
    assertEquals(186, LevelAbilities.maxMp(job, 50));
    // DC = MakeLong(_MAX(L div 5 - 1, 1), _MAX(1, L div 5)): the warrior floors its minimum
    // at 1, unlike the other two jobs, so levels 1..9 all sit at 1-1.
    assertEquals(1, LevelAbilities.minDc(job, 1));
    assertEquals(1, LevelAbilities.maxDc(job, 1));
    assertEquals(1, LevelAbilities.minDc(job, 10));
    assertEquals(2, LevelAbilities.maxDc(job, 10));
    assertEquals(9, LevelAbilities.minDc(job, 50));
    assertEquals(10, LevelAbilities.maxDc(job, 50));
    // AC = MakeLong(0, L div 7): only the warrior has natural defence.
    assertEquals(0, LevelAbilities.maxAc(job, 6));
    assertEquals(1, LevelAbilities.maxAc(job, 7));
    assertEquals(7, LevelAbilities.maxAc(job, 50));
    // Weight limits: 50 + Round((L/3)*L), 15 + Round((L/20)*L), 12 + Round((L/13)*L).
    assertEquals(183, LevelAbilities.maxWeight(job, 20));
    assertEquals(35, LevelAbilities.maxWearWeight(job, 20));
    assertEquals(43, LevelAbilities.maxHandWeight(job, 20));
  }

  @Test
  void wizardAndTaoistCurvesDifferInHealthManaAndDefence() {
    int wizard = LevelAbilities.JOB_WIZARD;
    int taoist = LevelAbilities.JOB_TAOIST;
    // The mage is the frailest and by far the deepest mana pool.
    assertEquals(16, LevelAbilities.maxHp(wizard, 1));
    assertEquals(271, LevelAbilities.maxHp(wizard, 50));
    assertEquals(1_333, LevelAbilities.maxMp(wizard, 50));
    // The Taoist sits between the two on both pools.
    assertEquals(17, LevelAbilities.maxHp(taoist, 1));
    assertEquals(556, LevelAbilities.maxHp(taoist, 50));
    assertEquals(701, LevelAbilities.maxMp(taoist, 50));
    assertTrue(LevelAbilities.maxHp(wizard, 50) < LevelAbilities.maxHp(taoist, 50));
    assertTrue(LevelAbilities.maxHp(taoist, 50) < LevelAbilities.maxHp(LevelAbilities.JOB_WARRIOR, 50));
    // Neither caster gets natural AC; both use L div 7 for DC with a zero floor.
    assertEquals(0, LevelAbilities.maxAc(wizard, 50));
    assertEquals(0, LevelAbilities.maxAc(taoist, 50));
    assertEquals(0, LevelAbilities.minDc(wizard, 7));
    assertEquals(1, LevelAbilities.maxDc(wizard, 7));
    assertEquals(6, LevelAbilities.minDc(taoist, 50));
    assertEquals(7, LevelAbilities.maxDc(taoist, 50));
    // RecalcLevelAbilitys mirrors that caster range into MC for wizards and SC for Taoists.
    assertEquals(6, LevelAbilities.minMc(wizard, 50));
    assertEquals(7, LevelAbilities.maxMc(wizard, 50));
    assertEquals(0, LevelAbilities.maxSc(wizard, 50));
    assertEquals(6, LevelAbilities.minSc(taoist, 50));
    assertEquals(7, LevelAbilities.maxSc(taoist, 50));
    // Taoist MAC is a separate Round(Level/6) curve.
    assertEquals(4, LevelAbilities.minMac(taoist, 50));
    assertEquals(9, LevelAbilities.maxMac(taoist, 50));
  }

  @Test
  void curveRebuildKeepsExperienceAndClampsCurrentPools() {
    // A wounded level-10 warrior demoted to level 1 must have HP/MP re-clamped downwards.
    Ability wounded = new Ability(80, 89, 40, 46, 1, 2, 0, 1, 10, 5_000);
    Ability shrunk = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 1, wounded);
    assertEquals(19, shrunk.maxHp());
    assertEquals(19, shrunk.hp(), "current HP must be clamped into the smaller maximum");
    assertEquals(15, shrunk.maxMp());
    assertEquals(15, shrunk.mp());
    assertEquals(5_000, shrunk.experience(), "the curve must not touch accumulated experience");
    // Growing the other way leaves the current pools alone — Delphi only clamps, never heals.
    Ability grown = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 20, wounded);
    assertEquals(224, grown.maxHp());
    assertEquals(80, grown.hp());
  }

  @Test
  void abilityConsumesExactlyOneLevelThresholdAndStopsAtMaxUpLevel() {
    // GetExp: Dec(Exp, MaxExp); Inc(Level); MaxExp := GetLevelExp(new level).
    Ability ready = Ability.defaultPlayer().addExperience(250);
    assertTrue(ready.readyToLevel());
    Ability levelled = ready.consumeLevelExperience();
    assertEquals(2, levelled.level());
    assertEquals(150, levelled.experience(), "only one threshold is deducted per level-up");
    assertEquals(200, levelled.maxExperience());
    assertFalse(levelled.readyToLevel(), "150 of 200 is not enough for a second level");
    // MAXUPLEVEL caps the level but the experience is still consumed.
    Ability capped = new Ability(1, 1, 0, 0, 0, 1, 0, 0,
        LevelExperience.MAX_UP_LEVEL, 5_000_000_000L, 4_000_000_000L);
    Ability afterCap = capped.consumeLevelExperience();
    assertEquals(LevelExperience.MAX_UP_LEVEL, afterCap.level());
    assertEquals(1_000_000_000L, afterCap.experience());
  }

  @Test
  void defaultPlayerMatchesTheCharacterCreationBlock() {
    // UsrEngn.pas:503: Level 1, DC 1-2, AC 0, HP/MP 15, Exp 0, MaxExp 100.
    Ability fresh = Ability.defaultPlayer();
    assertEquals(1, fresh.level());
    assertEquals(15, fresh.hp());
    assertEquals(15, fresh.maxHp());
    assertEquals(15, fresh.mp());
    assertEquals(15, fresh.maxMp());
    assertEquals(1, fresh.minDc());
    assertEquals(2, fresh.maxDc());
    assertEquals(0, fresh.maxAc());
    assertEquals(0, fresh.maxMac());
    assertEquals(1, fresh.minMc());
    assertEquals(2, fresh.maxMc());
    assertEquals(1, fresh.minSc());
    assertEquals(2, fresh.maxSc());
    assertEquals(0, fresh.experience());
    assertEquals(100, fresh.maxExperience());
  }
}
