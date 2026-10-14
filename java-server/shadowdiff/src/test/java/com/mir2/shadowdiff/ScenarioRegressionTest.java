package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The W30 interaction/persistence regression scenarios (W27 plan §4), driven through the
 * real CLI entry point so every layer is exercised exactly the way the evidence commands
 * run it: {@code ShadowDiffMain.run} with the same argument vectors, real sockets, real
 * SQLite files, the harness seeding included.
 *
 * <p>The two heavy duo built-ins (party and death-pk) assert the full §4 coverage; the
 * custom-script entry proves the {@code --script} + {@code {p2}} substitution path; the two
 * seeded solo scenarios pin the item lifecycle and the equipment lock. Every test boots two
 * complete embedded servers, which is exactly the "same operation stream, two servers"
 * contract the harness exists to verify.
 */
class ScenarioRegressionTest {

  @Test
  void persistenceScenarioRestoresGoldWornAndBagIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("persistence");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--persistence", "--strict-messages",
        "--report-dir", report.toString()});
    Path reportFile = report.resolve("shadow-report.md");
    String markdown = Files.exists(reportFile)
        ? Files.readString(reportFile, StandardCharsets.UTF_8) : "<shadow report missing>";
    assertEquals(0, status,
        "the seeded item lifecycle must survive the relog identically: "
            + markdown.replace('\n', ' '));
  }

  @Test
  void lockScenarioRefusesTheTakeOffObservably(@TempDir Path reports) throws Exception {
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--lock", "--strict-messages",
        "--report-dir", reports.resolve("lock").toString()});
    assertEquals(0, status, "the DisableTakeOffList refusal must be identical on both sides");
  }

  @Test
  void skillsScenarioComparesSpellManaDelayedHealAndTraining(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--strict-messages", "--seed", "20260922",
        "--report-dir", report.toString()});
    Path reportFile = report.resolve("shadow-report.md");
    String markdown = Files.exists(reportFile)
        ? Files.readString(reportFile, StandardCharsets.UTF_8) : "<shadow report missing>";
    assertEquals(0, status,
        "the pre-seeded Taoist spell path must match through delayed impact and relog: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 29 20 20"));
    assertTrue(markdown.contains("mp="),
        "the trace must retain the immediate mana spend observation");
    assertTrue(markdown.contains("skills=[magic=29 level=0 train=3 key=0]"),
        "the SM_MAGIC_LVEXP-derived training snapshot must survive the relog");
  }

  @Test
  void skillsScenarioWrongMagicSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    Path reportFile = report.resolve("shadow-report.md");
    String markdown = Files.exists(reportFile)
        ? Files.readString(reportFile, StandardCharsets.UTF_8) : "<shadow report missing>";
    assertEquals(1, status,
        "a different MAGIC stream must produce a visible spell-state divergence: "
            + markdown.replace('\n', ' '));
  }

  @Test
  void skillsFireWallScenarioBurnsTheDummyIdenticallyOnBothServers(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-firewall");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "firewall", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "the 火墙 cross must burn, expire and train identically on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 22 18 20"));
    assertTrue(markdown.contains("struck other dmg=8 hp=9991/9999"),
        "the first MAGIC-stream burn on the dummy must be observed on the wire");
    assertTrue(markdown.contains("skills=[magic=22 level=0 train=3 key=0]"),
        "the cast must train the skill and the row must survive the relog");
  }

  @Test
  void skillsFireWallWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-firewall-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "firewall",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different MAGIC stream must perturb the burn damage: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsSpaceMoveScenarioRelocatesTheCasterIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-spacemove");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "spacemove", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "the 瞬息移动 relocation must match on both sides: " + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 21 20 20"));
    assertTrue(markdown.contains("cell=(248,208)"),
        "the seeded level-3 gate must pass, so the caster must actually leave (20,20)");
  }

  @Test
  void skillsSpaceMoveWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-spacemove-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "spacemove",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different SPACE_MOVE stream must land the caster elsewhere: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsFireBoomScenarioStrikesBothRingArmsIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-fireboom");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "fireboom", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "both 爆裂火焰 squares must strike the same dummies for the same shared power: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 23 20 19") && markdown.contains("spell 23 20 21"),
        "both casts of the ring's north and south arms must be in the script");
    assertTrue(markdown.contains("struck other dmg=12 hp=9987/9999"),
        "the first shared-power roll reaches five dummies and must be observed on the wire");
    assertTrue(markdown.contains("skills=[magic=23 level=0 train=4 key=0]"),
        "both casts must train 爆裂火焰 and the row must survive the relog");
  }

  @Test
  void skillsFireBoomWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-fireboom-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "fireboom",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different MAGIC stream must perturb the shared blast power: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsLightFlowerScenarioKillsTheUndeadRingIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-lightflower");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "lightflower", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "the caster-centred 地狱雷光 must kill the undead ring identically on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 24 20 20"));
    assertTrue(markdown.contains("struck other dmg=37 hp=0/20"),
        "the LA_UNDEAD arm must take the full shared power, not power div 10");
    assertTrue(markdown.contains("exp +15 total=120"),
        "eight 稻草人 kills must award the same experience on both servers");
    assertTrue(markdown.contains("mp=514/572"),
        "the second cast must spend its mana even though the square is empty (29 MP again)");
    assertTrue(markdown.contains("skills=[magic=24 level=0 train=3 key=0]"),
        "the second, empty-square cast must not train: training stays at the first cast's value");
  }

  @Test
  void skillsLightFlowerWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-lightflower-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "lightflower",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status,
        "a different seed must perturb the undead damage and the seeded drop rolls: "
            + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsSnowWindScenarioStrikesBothRingArmsIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-snowwind");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "snowwind", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "both 冰咆哮 squares must strike the same dummies for the same shared power: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 33 20 19") && markdown.contains("spell 33 20 21"));
    assertTrue(markdown.contains("struck other dmg=23 hp=9976/9999"),
        "the W44 row must reuse the MagBigExplosion shape with its own radius and power");
    assertTrue(markdown.contains("skills=[magic=33 level=0 train=4 key=0]"));
  }

  @Test
  void skillsSnowWindWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-snowwind-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "snowwind",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different MAGIC stream must perturb the 冰咆哮 power: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsShieldScenarioActivatesRefusesAndExpiresIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-shield");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "shield", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "魔法盾 activation, duplicate refusal, and expiry must match on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 31 20 20"));
    assertTrue(markdown.contains("skills=[magic=31 level=0 train=6 key=0]"),
        "the shield casts must train the skill and survive the relog");
  }

  @Test
  void skillsShieldWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-shield-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "shield",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different MAGIC stream must perturb the shield duration: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsKillUndeadScenarioKillsTargetScarecrowIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-killundead");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "killundead", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "圣言术 instant-kill against the scarecrow must match on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 32 20 19 2") && markdown.contains("spell 32 20 21 6"));
    assertTrue(markdown.contains("combat=[death other at=(20,21), exp +15 total=15]"),
        "the instant kill of scarecrow #6 must be observed on the wire");
    assertTrue(markdown.contains("skills=[magic=32 level=1 train=3 key=0]"),
        "the successful kill must train the skill and survive relog");
  }

  @Test
  void skillsKillUndeadWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-killundead-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "killundead",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status, "a different TURN_UNDEAD stream must perturb the instant-kill outcome: "
        + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsGhostShieldScenarioBurnsCharmsAndExpiryLadderIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-ghostshield");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "ghostshield", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "幽灵盾 charm burn, MAC window and expiry ladder must match on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 14 20 20"));
    assertTrue(markdown.contains("mac=2-10"),
        "the worn-charm cast must lift the upper MAC bound by 2 + Level div 7 = 5");
    assertTrue(markdown.contains("worn=[护身符 9600/10000]"),
        "four accepted casts must burn four 100-dura charges, observable per cast");
    assertTrue(markdown.contains("skills=[magic=14 level=0 train=6 key=0]"),
        "every charm-backed cast trains (the fizzle does not) and the row survives the relog");
    assertTrue(markdown.contains("mp=142/146"),
        "the missing-charm fizzle spends its 4 MP and never refunds it");
  }

  @Test
  void skillsGhostShieldWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-ghostshield-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "ghostshield",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status,
        "a different DAMAGE stream must perturb the duration roll (the expiry ladder turns it "
            + "into a mac-word STATE diff) and the training rolls: "
            + readReport(report).replace('\n', ' '));
  }

  @Test
  void skillsHolyArmourScenarioLiftsTheAcBoundIdentically(@TempDir Path reports)
      throws Exception {
    Path report = reports.resolve("skills-holyarmour");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "holyarmour", "--strict-messages",
        "--seed", "20260922", "--report-dir", report.toString()});
    String markdown = readReport(report);
    assertEquals(0, status,
        "神圣战甲术 charm burn, AC window and expiry ladder must match on both sides: "
            + markdown.replace('\n', ' '));
    assertTrue(markdown.contains("spell 15 20 20"));
    assertTrue(markdown.contains("ac=0-5"),
        "the btType=0 arm lifts the upper AC bound (a Taoist's naked AC is 0-0)");
    assertTrue(markdown.contains("mac=2-5"),
        "神圣战甲术 must leave the magic defence word alone — the twin case's mac=2-10 is the "
            + "only thing that keeps the two btType arms apart");
    assertTrue(markdown.contains("worn=[护身符 9600/10000]"));
    assertTrue(markdown.contains("skills=[magic=15 level=0 train=6 key=0]"),
        "every charm-backed cast trains and the row survives the relog");
  }

  @Test
  void skillsHolyArmourWrongSeedIsDetected(@TempDir Path reports) throws Exception {
    Path report = reports.resolve("skills-holyarmour-negative");
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "holyarmour",
        "--seed", "20260922", "--right-seed", "99999",
        "--report-dir", report.toString()});
    assertEquals(1, status,
        "a different DAMAGE stream must perturb the duration roll into an ac-word STATE diff: "
            + readReport(report).replace('\n', ' '));
  }

  @Test
  void unknownSkillCaseIsAUsageErrorBeforeAnyServerBoots(@TempDir Path reports) {
    assertThrows(IllegalArgumentException.class, () -> ShadowDiffMain.run(new String[] {
        "--embedded", "--skills", "--skill-case", "summon",
        "--report-dir", reports.resolve("bogus").toString()}));
    assertFalse(Files.exists(reports.resolve("bogus")),
        "a rejected case name must not leave a report behind");
  }

  private static String readReport(Path report) throws java.io.IOException {
    Path reportFile = report.resolve("shadow-report.md");
    return Files.exists(reportFile)
        ? Files.readString(reportFile, StandardCharsets.UTF_8) : "<shadow report missing>";
  }

  @Test
  void duoCustomScriptRunsTheGroupProtocolThroughTwoSessions(@TempDir Path tmp)
      throws Exception {
    Path script = tmp.resolve("duo-custom.txt");
    Files.writeString(script, """
        # group protocol over the wire, ending with the member walking out via groupmode 0
        p1 groupmode 1
        p1 groupcreate {p2}
        p2 groupmode 1
        p1 groupcreate {p2}
        p1 bag
        p2 bag
        p2 groupmode 0
        p1 bag
        p2 bag
        """, StandardCharsets.UTF_8);
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--duo", "custom", "--script", script.toString(),
        "--strict-messages", "--report-dir", tmp.resolve("reports").toString()});
    assertEquals(0, status, "both per-player streams must match across the two servers");
    assertTrue(Files.exists(tmp.resolve("reports/p1/shadow-report.md")),
        "the duo runner writes one report per player");
    assertTrue(Files.exists(tmp.resolve("reports/p2/shadow-report.md")));
  }

  @Test
  void duoDeathPkScenarioMatchesOnBothServers(@TempDir Path reports) throws Exception {
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--duo", "death-pk", "--strict-messages",
        "--report-dir", reports.resolve("death-pk").toString()});
    assertEquals(0, status,
        "murder, 死亡自动退队, PK points and both relogin paths must match exactly");
  }

  @Test
  void duoPartyScenarioMatchesOnBothServers(@TempDir Path reports) throws Exception {
    int status = ShadowDiffMain.run(new String[] {
        "--embedded", "--duo", "party", "--strict-messages",
        "--report-dir", reports.resolve("party").toString()});
    assertEquals(0, status,
        "group formation, the shared kill with drop pickup, the 12-cell share boundary, "
            + "the kick and both relogs must match exactly");
  }
}
