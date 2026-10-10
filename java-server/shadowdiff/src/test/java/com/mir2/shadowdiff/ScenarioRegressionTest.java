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
