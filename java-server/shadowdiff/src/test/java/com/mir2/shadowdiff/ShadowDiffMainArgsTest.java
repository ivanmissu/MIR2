package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.world.MonsterBehavior;
import com.mir2.world.MonsterTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the CLI's value-less switches. This is a regression guard, not decoration: while
 * bringing up {@code --ai} the flag was not registered, so the parser treated it as a
 * key expecting a value, ate the following token and rejected
 * {@code --ai --monsters 4} with "unexpected argument: 4".
 */
class ShadowDiffMainArgsTest {

  @Test
  void valuelessFlagsDoNotSwallowTheFollowingArgument() {
    Map<String, String> options = ShadowDiffMain.Args.parse(
        new String[] {"--embedded", "--ai", "--monsters", "4", "--strict-messages"});

    assertEquals("true", options.get("ai"));
    assertEquals("true", options.get("embedded"));
    assertEquals("true", options.get("strict-messages"));
    assertEquals("4", options.get("monsters"), "--monsters must keep its own value");
  }

  @Test
  void everySwitchTheRunnerTreatsAsAFlagIsRegisteredAsOne() {
    // containsKey() checks in run()/runEmbedded() only work for registered flags.
    assertTrue(ShadowDiffMain.Args.FLAGS.containsAll(
        java.util.List.of("embedded", "help", "pve", "ai", "ai-all", "strict-messages",
            "persistence", "lock", "skills")),
        "the W30 scenario switches must be registered or they eat the next argument");
  }

  @Test
  void skillsFlagRemainsValuelessAndCanKeepTheSeedArguments() {
    Map<String, String> options = ShadowDiffMain.Args.parse(
        new String[] {"--embedded", "--skills", "--seed", "20260922", "--right-seed", "99999"});
    assertEquals("true", options.get("skills"));
    assertEquals("20260922", options.get("seed"));
    assertEquals("99999", options.get("right-seed"));
  }

  @Test
  void skillCaseIsAValuedOptionThatDefaultsToTheHealingScenario() {
    Map<String, String> options = ShadowDiffMain.Args.parse(new String[] {
        "--embedded", "--skills", "--skill-case", "spacemove", "--seed", "20260922"});
    assertEquals("true", options.get("skills"));
    assertEquals("spacemove", options.get("skill-case"),
        "--skill-case must consume its value rather than leaking it as a positional");
    assertEquals(ShadowDiffMain.SkillCase.HEALING, ShadowDiffMain.SkillCase.parse("healing"));
    assertEquals(ShadowDiffMain.SkillCase.FIREWALL, ShadowDiffMain.SkillCase.parse("firewall"));
    assertEquals(ShadowDiffMain.SkillCase.SPACEMOVE, ShadowDiffMain.SkillCase.parse("spacemove"));
    assertEquals(ShadowDiffMain.SkillCase.FIREBOOM, ShadowDiffMain.SkillCase.parse("fireboom"));
    assertEquals(ShadowDiffMain.SkillCase.LIGHTFLOWER,
        ShadowDiffMain.SkillCase.parse("lightflower"));
    assertEquals(ShadowDiffMain.SkillCase.SNOWWIND, ShadowDiffMain.SkillCase.parse("snowwind"));
    assertEquals(ShadowDiffMain.SkillCase.SHIELD, ShadowDiffMain.SkillCase.parse("shield"));
    assertEquals(ShadowDiffMain.SkillCase.KILLUNDEAD, ShadowDiffMain.SkillCase.parse("killundead"));
    assertEquals(ShadowDiffMain.SkillCase.GHOSTSHIELD,
        ShadowDiffMain.SkillCase.parse("ghostshield"));
    assertEquals(ShadowDiffMain.SkillCase.HOLYARMOUR,
        ShadowDiffMain.SkillCase.parse("holyarmour"));
  }

  /**
   * W52: the three area-spell cases each pin the setup their script was written against —
   * the {@code NeedL1} the Magic.DB row requires and the monster kind whose damage arm the
   * case is meant to observe.
   */
  @Test
  void areaSkillCasesPinTheirLevelRequirementsAndDummyKind() {
    assertEquals(25, ShadowDiffMain.SkillCase.FIREBOOM.level(),
        "爆裂火焰 NeedL1=22 — the level-25 case clears it with the W51 setup");
    assertEquals(31, ShadowDiffMain.SkillCase.LIGHTFLOWER.level(),
        "地狱雷光 NeedL1=30 — below 31 the cast would be rejected as LEVEL_TOO_LOW");
    assertEquals(35, ShadowDiffMain.SkillCase.SNOWWIND.level(),
        "冰咆哮 NeedL1=35 — the case must sit exactly on the row's own requirement");
    assertEquals("trainer", ShadowDiffMain.SkillCase.FIREBOOM.monsterKind());
    assertEquals("trainer", ShadowDiffMain.SkillCase.SNOWWIND.monsterKind());
    assertEquals("scarecrow", ShadowDiffMain.SkillCase.LIGHTFLOWER.monsterKind(),
        "only the undead scarecrow exposes the LA_UNDEAD full-power arm of 地狱雷光");
    assertTrue(MonsterTemplate.forName("scarecrow").undead(),
        "the lightflower case depends on the Monster.DB Undead column staying set");
    assertFalse(MonsterTemplate.forName("trainer").undead(),
        "the stationary dummy must stay a living target for the other cases");
  }

  /**
   * W53: 魔法盾 and 圣言术 cases pin their wizard setup and monster kind.
   */
  @Test
  void w53SkillCasesPinTheirLevelRequirementsAndDummyKind() {
    assertEquals(31, ShadowDiffMain.SkillCase.SHIELD.level(),
        "魔法盾 NeedL1=31 — seeded wizard level 31 clears it");
    assertEquals(39, ShadowDiffMain.SkillCase.KILLUNDEAD.level(),
        "圣言术 skill level 1 NeedL2=35, level 39 clears it and passes instant-kill gate");
    assertEquals("trainer", ShadowDiffMain.SkillCase.SHIELD.monsterKind());
    assertEquals("scarecrow", ShadowDiffMain.SkillCase.KILLUNDEAD.monsterKind(),
        "圣言术 requires an undead target (Monster.DB Undead=1)");
  }

  /**
   * W54: the two Taoist defence-buff cases pin their Taoist setup — the {@code NeedL1} of
   * their Magic.DB rows, a stationary trainer (nothing dies; the only randomness is the
   * duration and training draws) and the bag seeded with exactly one 护身符: the script's
   * opening fizzle proves the charm counts only once worn, and four casts × 100 dura must
   * never exhaust it. Every pre-W54 case keeps the empty-bag seeding it has always had.
   */
  @Test
  void w54DefenceBuffCasesPinTheirTaoistSetupAndCharmBag() {
    assertEquals(22, ShadowDiffMain.SkillCase.GHOSTSHIELD.level(),
        "幽灵盾 NeedL1=22 — the seeded Taoist sits exactly on the row's own requirement");
    assertEquals(25, ShadowDiffMain.SkillCase.HOLYARMOUR.level(),
        "神圣战甲术 NeedL1=25 — its row demands three more levels than the twin's");
    assertEquals("trainer", ShadowDiffMain.SkillCase.GHOSTSHIELD.monsterKind());
    assertEquals("trainer", ShadowDiffMain.SkillCase.HOLYARMOUR.monsterKind(),
        "the buff targets the caster; the dummy only keeps the world alive");
    assertEquals(0, ShadowDiffMain.SkillCase.GHOSTSHIELD.monsters());
    assertEquals(0, ShadowDiffMain.SkillCase.HOLYARMOUR.monsters());
    for (ShadowDiffMain.SkillCase skillCase : ShadowDiffMain.SkillCase.values()) {
      boolean w54 = skillCase == ShadowDiffMain.SkillCase.GHOSTSHIELD
          || skillCase == ShadowDiffMain.SkillCase.HOLYARMOUR;
      assertEquals(w54 ? List.of("护身符") : List.of(), skillCase.bagItems(),
          skillCase.name() + " bag seeding");
    }
  }

  @Test
  void aiMatrixIsPinnedToExactlyTheExistingP2FirstTenTemplates() {
    assertEquals(List.of(
        "chicken", "deer", "scarecrow", "hookcat", "rakecat",
        "cavemaggot", "scorpion", "orc", "orcwarrior", "orcfighter"),
        ShadowDiffMain.AI_MONSTER_KINDS);
    assertEquals(10, ShadowDiffMain.AI_MONSTER_KINDS.stream()
        .map(MonsterTemplate::forName)
        .filter(template -> template.behavior() != MonsterBehavior.STATIONARY)
        .count(), "the matrix must resolve ten live-AI templates and must not include trainer");
  }

  @Test
  void keyValueOptionsStillRequireAValue() {
    Map<String, String> options = ShadowDiffMain.Args.parse(
        new String[] {"--seed", "20260923", "--right-seed", "99999"});
    assertEquals("20260923", options.get("seed"));
    assertEquals("99999", options.get("right-seed"), "the negative control keeps its seed");

    assertThrows(IllegalArgumentException.class,
        () -> ShadowDiffMain.Args.parse(new String[] {"--seed"}));
    assertThrows(IllegalArgumentException.class,
        () -> ShadowDiffMain.Args.parse(new String[] {"garbage"}));
  }
  /**
   * A failed AI-matrix row ends up in the G4 failure fingerprint as a one-line note, so the
   * note must keep the whole cause chain (an {@code UncheckedIOException} hides the real
   * reason in its cause) and point at the first {@code com.mir2} frame — a bare
   * {@code failed reading MonItems/…} is exactly the un-actionable line this replaced.
   */
  @org.junit.jupiter.api.Test
  void failureNotesKeepTheCauseChainAndTheMir2Frame() {
    java.io.IOException root = new java.io.IOException("zip file closed");
    Throwable wrapped =
        new java.io.UncheckedIOException("failed reading MonItems/hookcat.txt", root);
    String note = ShadowDiffMain.describeFailure(wrapped);
    assertTrue(note.contains("UncheckedIOException: failed reading MonItems/hookcat.txt"), note);
    assertTrue(note.contains("<- IOException: zip file closed"),
        "the cause chain must survive into the fingerprint: " + note);
    assertTrue(note.contains("@ com.mir2."),
        "the note must point at our own frame so the failure is locatable: " + note);
    assertFalse(note.contains("\n") || note.contains("\r"),
        "the note is a single-line table cell / log line: " + note);
    assertEquals(note, note.replace('|', '/'), "the note must survive a Markdown table cell");
  }


}
