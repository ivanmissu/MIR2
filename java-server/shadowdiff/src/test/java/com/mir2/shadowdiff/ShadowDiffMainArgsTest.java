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
