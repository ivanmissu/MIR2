package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
