package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * W31 G4 release gate (w27-next-plan §5): keeps {@code docs/g4-release-gate.tsv}, the
 * runner {@code scripts/g4-release-gate.sh} and the actual CLI honest about each other.
 *
 * <p>The failure modes this exists to prevent are the ones that make a release gate
 * worthless rather than merely red:
 *
 * <ul>
 *   <li>a scenario silently disappearing from the manifest (the gate then passes because it
 *       stopped checking something);</li>
 *   <li>a manifest command that no longer parses as a {@link ShadowDiffMain} argument
 *       vector — it would fail with usage error 2 and, for a negative-control row, could be
 *       mistaken for "divergence detected";</li>
 *   <li>the negative control losing its {@code exit-1} expectation, which is what proves the
 *       comparison is observing anything at all;</li>
 *   <li>the evidence document claiming G4 is signed off while the capability matrix still
 *       lists unimplemented skills / scripts / guild / siege / trade rows.</li>
 * </ul>
 */
final class G4ReleaseGateTest {

  private static final String MANIFEST_RELATIVE = "java-server/docs/g4-release-gate.tsv";
  private static final String RUNNER_RELATIVE = "java-server/scripts/g4-release-gate.sh";
  private static final String MATRIX_RELATIVE = "java-server/docs/g4-capability-matrix.tsv";
  private static final String EVIDENCE_RELATIVE =
      "java-server/docs/g0-evidence/2026-09-25-w31-g4-release-gate.md";

  private static final String HEADER =
      "id\tkind\tblocking\texpect\tcommand\tartifact\tscope\tnotes";
  private static final Set<String> KINDS = Set.of("maven", "shadowdiff");
  private static final Set<String> BLOCKING = Set.of("yes", "no");
  private static final Set<String> EXPECT = Set.of("exit-0", "exit-1");
  private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  /**
   * The scenario set the W27 plan §5 demands evidence for. Dropping a row here is a
   * deliberate act that has to touch this test, never a silent manifest edit.
   */
  private static final Set<String> REQUIRED_IDS = Set.of(
      "maven-verify",
      "shadowdiff-base",
      "shadowdiff-pve",
      "shadowdiff-ai",
      "shadowdiff-ai-negative",
      "shadowdiff-ai-matrix",
      "shadowdiff-duo-party",
      "shadowdiff-duo-death-pk",
      "shadowdiff-persistence",
      "shadowdiff-lock");

  private record Row(String id, String kind, String blocking, String expect,
      String command, String artifact, String scope, String notes) {}

  // ------------------------------------------------------------------ format

  @org.junit.jupiter.api.Test
  void manifestIsWellFormed() {
    List<Row> rows = rows();
    assertFalse(rows.isEmpty(), "the release gate manifest must not be empty");
    Set<String> ids = new HashSet<>();
    Set<String> artifacts = new HashSet<>();
    List<String> problems = new ArrayList<>();
    for (Row row : rows) {
      if (!ID.matcher(row.id()).matches()) problems.add("bad id: " + row.id());
      if (!ids.add(row.id())) problems.add("duplicate id: " + row.id());
      if (!artifacts.add(row.artifact()))
        problems.add(row.id() + ": duplicate artifact " + row.artifact());
      if (!KINDS.contains(row.kind())) problems.add(row.id() + ": bad kind " + row.kind());
      if (!BLOCKING.contains(row.blocking()))
        problems.add(row.id() + ": bad blocking " + row.blocking());
      if (!EXPECT.contains(row.expect())) problems.add(row.id() + ": bad expect " + row.expect());
      if (row.scope().isBlank()) problems.add(row.id() + ": scope must state what it proves");
      if (row.notes().isBlank()) problems.add(row.id() + ": notes must not be empty");
      if (row.kind().equals("maven")) {
        if (!row.command().startsWith("mvn "))
          problems.add(row.id() + ": maven rows must invoke mvn");
        if (!row.artifact().endsWith(".log"))
          problems.add(row.id() + ": maven rows leave a log artifact (<id>.log)");
        if (!row.artifact().equals(row.id() + ".log"))
          problems.add(row.id() + ": the runner names maven logs <id>.log");
      }
      if (row.kind().equals("shadowdiff")) {
        if (!row.command().contains("-jar {{jar}}"))
          problems.add(row.id() + ": shadowdiff rows must run the fat JAR via -jar {{jar}}");
        if (!row.command().contains("{{report}}"))
          problems.add(row.id() + ": shadowdiff rows must write their report under {{report}}");
      }
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  @org.junit.jupiter.api.Test
  void manifestCoversEveryScenarioThePlanRequires() {
    Set<String> ids = new LinkedHashSet<>();
    for (Row row : rows()) ids.add(row.id());
    Set<String> missing = new LinkedHashSet<>(REQUIRED_IDS);
    missing.removeAll(ids);
    assertTrue(missing.isEmpty(),
        () -> "release gate rows removed without updating the gate test: " + missing);
  }

  // ------------------------------------------------------------------ CLI truth

  @org.junit.jupiter.api.Test
  void everyShadowdiffCommandParsesAsARealArgumentVector() {
    List<String> problems = new ArrayList<>();
    for (Row row : rows()) {
      if (!row.kind().equals("shadowdiff")) continue;
      String[] args = shadowdiffArgs(row);
      try {
        Map<String, String> options = ShadowDiffMain.Args.parse(args);
        if (!options.containsKey("embedded"))
          problems.add(row.id() + ": the release gate only runs embedded Java↔Java rows"
              + " (a remote Delphi row needs its own evidence process)");
        if (!options.containsKey("report-dir"))
          problems.add(row.id() + ": --report-dir is mandatory so the run leaves evidence");
        String reportDir = options.getOrDefault("report-dir", "");
        if (!reportDir.endsWith("/" + row.artifact()))
          problems.add(row.id() + ": --report-dir must end in the declared artifact "
              + row.artifact() + " (got " + reportDir + ")");
      } catch (RuntimeException parseFailure) {
        problems.add(row.id() + ": " + parseFailure.getMessage());
      }
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  /**
   * The negative control is the row that proves the rest are not vacuous, so its shape is
   * asserted explicitly: mismatched seeds and an {@code exit-1} expectation.
   */
  @org.junit.jupiter.api.Test
  void theNegativeControlStillDemandsDivergence() {
    Row row = rows().stream()
        .filter(candidate -> candidate.id().equals("shadowdiff-ai-negative"))
        .findFirst()
        .orElseGet(() -> fail("the AI negative control row disappeared from the manifest"));
    assertEquals("exit-1", row.expect(),
        "a negative control that accepts exit 0 proves nothing");
    Map<String, String> options = ShadowDiffMain.Args.parse(shadowdiffArgs(row));
    assertTrue(options.containsKey("seed") && options.containsKey("right-seed"),
        "the negative control needs both seeds");
    assertFalse(options.get("seed").equals(options.get("right-seed")),
        "the negative control's seeds must differ, otherwise the two worlds agree legitimately");
    for (Row other : rows()) {
      if (other.id().equals(row.id()) || !other.kind().equals("shadowdiff")) continue;
      assertEquals("exit-0", other.expect(),
          other.id() + ": only the negative control may expect a non-zero exit");
    }
  }

  @org.junit.jupiter.api.Test
  void aiMatrixRowCoversTheFirstTenSlice() {
    Row row = rows().stream()
        .filter(candidate -> candidate.id().equals("shadowdiff-ai-matrix"))
        .findFirst()
        .orElseGet(() -> fail("the AI matrix row disappeared from the manifest"));
    assertTrue(ShadowDiffMain.Args.parse(shadowdiffArgs(row)).containsKey("ai-all"),
        "the matrix row must use --ai-all so every first-ten template is compared");
    assertEquals(10, ShadowDiffMain.AI_MONSTER_KINDS.size(),
        "the first-ten AI slice changed size — re-scope the matrix row and its evidence");
  }

  // ------------------------------------------------------------------ runner

  @org.junit.jupiter.api.Test
  void theRunnerIsDataDrivenByTheManifest() {
    Path runner = repoRoot().resolve(RUNNER_RELATIVE);
    assertTrue(Files.isRegularFile(runner), "missing runner script " + RUNNER_RELATIVE);
    String source = read(runner);
    assertTrue(source.contains("docs/g4-release-gate.tsv"),
        "the runner must read the manifest instead of hard-coding scenarios");
    for (String placeholder : List.of("{{jar}}", "{{report}}", "{{repo}}")) {
      assertTrue(source.contains(placeholder),
          "the runner must substitute the " + placeholder + " placeholder used by the manifest");
    }
    // Any scenario named in the runner body would be a second source of truth.
    for (Row row : rows()) {
      if (row.id().equals("maven-verify")) continue;
      assertFalse(source.contains(row.id()),
          "the runner hard-codes scenario " + row.id() + "; it must come from the manifest only");
    }
  }

  // ------------------------------------------------------------------ verdict honesty

  /**
   * The gate may only be described as "G4 signed off" once the capability matrix stops
   * listing unimplemented rows. While skills / merchant scripts / guild / siege / trade are
   * still open, the evidence document must say so in as many words.
   */
  @org.junit.jupiter.api.Test
  void theEvidenceDoesNotClaimG4WhileTheMatrixStillHasGaps() {
    long unimplemented = readLines(repoRoot().resolve(MATRIX_RELATIVE)).stream()
        .filter(line -> !line.isBlank() && !line.startsWith("#"))
        .filter(line -> line.split("\t", -1).length >= 6)
        .filter(line -> line.split("\t", -1)[5].equals("unimplemented"))
        .count();
    Path evidence = repoRoot().resolve(EVIDENCE_RELATIVE);
    assertTrue(Files.isRegularFile(evidence), "missing evidence document " + EVIDENCE_RELATIVE);
    String text = read(evidence);
    if (unimplemented > 0) {
      assertTrue(text.contains("未签发"),
          "the capability matrix still lists " + unimplemented
              + " unimplemented rows, so the evidence must state G4 未签发");
    }
    assertTrue(text.toLowerCase(Locale.ROOT).contains("g4-release-gate.tsv"),
        "the evidence must point at the manifest it was produced from");
  }

  // ------------------------------------------------------------------ helpers

  private static String[] shadowdiffArgs(Row row) {
    String command = row.command()
        .replace("{{jar}}", "/tmp/mir2-shadowdiff.jar")
        .replace("{{report}}", "/tmp/g4-report")
        .replace("{{repo}}", "/tmp/repo");
    List<String> tokens = new ArrayList<>(List.of(command.trim().split("\\s+")));
    int jarIndex = tokens.indexOf("-jar");
    if (jarIndex < 0 || jarIndex + 2 > tokens.size())
      return fail(row.id() + ": shadowdiff command has no -jar <path> segment");
    return tokens.subList(jarIndex + 2, tokens.size()).toArray(String[]::new);
  }

  private static List<Row> rows() {
    List<Row> rows = new ArrayList<>();
    boolean headerSeen = false;
    for (String line : readLines(repoRoot().resolve(MANIFEST_RELATIVE))) {
      if (line.isBlank() || line.startsWith("#")) continue;
      if (!headerSeen) {
        assertEquals(HEADER, line,
            "manifest header changed — update the runner and this gate together");
        headerSeen = true;
        continue;
      }
      String[] cells = line.split("\t", -1);
      assertEquals(8, cells.length, "expected 8 tab-separated columns: " + line);
      rows.add(new Row(cells[0], cells[1], cells[2], cells[3],
          cells[4], cells[5], cells[6], cells[7]));
    }
    assertTrue(headerSeen, "manifest has no header row");
    return rows;
  }

  private static List<String> readLines(Path path) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static Path repoRoot() {
    String configured = System.getProperty("mir2.repo.root");
    if (configured != null) {
      Path root = Path.of(configured);
      if (Files.isRegularFile(root.resolve(MANIFEST_RELATIVE))) return root;
      fail("mir2.repo.root does not contain the release gate manifest: " + root);
    }
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path current = dir; current != null; current = current.getParent()) {
      if (Files.isRegularFile(current.resolve(MANIFEST_RELATIVE))
          && Files.isRegularFile(current.resolve(MATRIX_RELATIVE))) return current;
    }
    return fail("cannot locate the repository root above " + dir);
  }
}
