package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mir2.protocol.ProtocolConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W27 G4 gate: keeps {@code docs/g4-capability-matrix.tsv} honest against the sources it
 * claims to audit — Delphi {@code Grobal2.pas}/{@code M2Share.pas}, Java
 * {@link ProtocolConstants}, and the two wire entry points
 * ({@link GameProtocolAdapter#isSupported}, {@code LegacyGateHandler} dispatch).
 *
 * <p>The rules below exist so that a new protocol constant, a newly handled message, or a
 * newly emitted server message can never land without an explicit capability status — the
 * exact failure mode the W27 plan forbids ("不以'有常量'视为完成").
 */
final class G4CapabilityMatrixTest {

  private static final String MATRIX_RELATIVE = "java-server/docs/g4-capability-matrix.tsv";
  private static final String GROBAL_RELATIVE = "GameOfMir/Common/Grobal2.pas";
  private static final String M2SHARE_RELATIVE = "GameOfMir/M2Server/M2Share.pas";
  private static final String ADAPTER_RELATIVE =
      "java-server/gate/src/main/java/com/mir2/gate/GameProtocolAdapter.java";
  private static final String GATE_RELATIVE =
      "java-server/gate/src/main/java/com/mir2/gate/LegacyGateHandler.java";

  private static final Set<String> SECTIONS =
      Set.of("CM", "SM", "SKILL", "MERCHANT", "GUILD", "SIEGE", "DEAL");
  private static final Set<String> STATUSES = Set.of(
      "implemented", "needs-client-check", "protocol-only", "logic-only", "unimplemented");
  private static final Set<String> JAVA_CONSTANT_VALUES = Set.of("yes", "no", "n/a");
  private static final Set<String> WIRE_VALUES = Set.of("none", "gate-login", "gate-game", "n/a");
  private static final Set<String> LABEL_SECTIONS = Set.of("MERCHANT", "GUILD", "SIEGE");
  private static final Set<String> JOBS = Set.of("warrior", "wizard", "taoist");

  private static final Pattern DELPHI_CONST =
      Pattern.compile("^\\s*((?:CM|SM|SKILL)_[A-Za-z0-9_]+)\\s*=\\s*(\\d+)", Pattern.MULTILINE);
  private static final Pattern LABEL_CONST =
      Pattern.compile("^\\s*(s\\w+)\\s*=\\s*'([~@][^']*)';", Pattern.MULTILINE);

  /** One parsed matrix row. */
  record Row(String section, String key, String code, String javaConstant, String wire,
      String status, String evidence, String delphiRef, String notes) {
    boolean isLabelKey() {
      return key.startsWith("@") || key.startsWith("~@");
    }
  }

  // ---------------------------------------------------------------- format

  @Test
  void matrixIsWellFormed() {
    List<Row> rows = matrix();
    assertFalse(rows.isEmpty(), "matrix has no data rows");
    Set<String> seen = new HashSet<>();
    List<String> problems = new ArrayList<>();
    for (Row row : rows) {
      if (!SECTIONS.contains(row.section())) problems.add("unknown section: " + row.section());
      if (!STATUSES.contains(row.status()))
        problems.add(row.section() + "/" + row.key() + ": unknown status " + row.status());
      if (!JAVA_CONSTANT_VALUES.contains(row.javaConstant()))
        problems.add(row.section() + "/" + row.key() + ": bad java_constant " + row.javaConstant());
      if (!WIRE_VALUES.contains(row.wire()))
        problems.add(row.section() + "/" + row.key() + ": bad wire " + row.wire());
      if (!row.code().equals("-")) {
        try {
          Integer.parseInt(row.code());
        } catch (NumberFormatException error) {
          problems.add(row.section() + "/" + row.key() + ": non-numeric code " + row.code());
        }
      }
      if (!seen.add(row.section() + "" + row.key()))
        problems.add("duplicate row key: " + row.section() + "/" + row.key());
      if (row.isLabelKey() && !LABEL_SECTIONS.contains(row.section()))
        problems.add(row.key() + ": label row outside " + LABEL_SECTIONS);
      // Status ↔ wire ↔ java_constant coherence. n/a columns (skill/label/capability rows)
      // only carry the wire/evidence half of the rules.
      boolean wired = !row.wire().equals("none") && !row.wire().equals("n/a");
      boolean evidenced = !row.evidence().equals("-") && !row.evidence().isBlank();
      switch (row.status()) {
        case "implemented", "needs-client-check" -> {
          if (!wired) problems.add(row.section() + "/" + row.key() + ": " + row.status() + " but wire=" + row.wire());
          if (!evidenced) problems.add(row.section() + "/" + row.key() + ": " + row.status() + " without evidence");
        }
        case "protocol-only" -> {
          if (!row.javaConstant().equals("yes"))
            problems.add(row.section() + "/" + row.key() + ": protocol-only needs java_constant=yes");
          if (wired) problems.add(row.section() + "/" + row.key() + ": protocol-only but wired");
        }
        case "logic-only" -> {
          if (!row.javaConstant().equals("no"))
            problems.add(row.section() + "/" + row.key() + ": logic-only needs java_constant=no");
          if (!wired) problems.add(row.section() + "/" + row.key() + ": logic-only but wire=none");
        }
        case "unimplemented" -> {
          if (row.javaConstant().equals("yes"))
            problems.add(row.section() + "/" + row.key() + ": unimplemented needs java_constant!=yes");
          if (wired) problems.add(row.section() + "/" + row.key() + ": unimplemented but wired");
        }
        default -> throw new IllegalStateException(row.status());
      }
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  @Test
  void evidenceReferencesPointToRealArtifacts() {
    Set<String> testClasses = testClassNames();
    List<String> problems = new ArrayList<>();
    for (Row row : matrix()) {
      if (row.evidence().equals("-")) continue;
      for (String token : row.evidence().split(",")) {
        String ref = token.trim();
        if (ref.endsWith(".md")) {
          if (!Files.isRegularFile(repoRoot().resolve("java-server/docs").resolve(ref)))
            problems.add(row.section() + "/" + row.key() + ": missing doc java-server/docs/" + ref);
        } else if (!testClasses.contains(ref)) {
          problems.add(row.section() + "/" + row.key() + ": unknown test class " + ref);
        }
      }
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  // ------------------------------------------------------- CM/SM completeness

  @Test
  void everyDelphiCmConstantHasAConsistentRow() {
    assertSectionMatchesDelphi("CM");
  }

  @Test
  void everyDelphiSmConstantHasAConsistentRow() {
    assertSectionMatchesDelphi("SM");
  }

  private void assertSectionMatchesDelphi(String section) {
    Map<String, Integer> delphi = delphiConstants(section);
    Map<String, Integer> javaConstants = javaConstants(section);
    Map<String, Row> rows = rowsByKey(section);
    Set<String> missing = new TreeSet<>(delphi.keySet());
    missing.removeAll(rows.keySet());
    assertTrue(missing.isEmpty(), "matrix misses Delphi " + section + " constants: " + missing);
    Set<String> extra = new TreeSet<>(rows.keySet());
    extra.removeAll(delphi.keySet());
    assertTrue(extra.isEmpty(), "matrix lists unknown Delphi " + section + " constants: " + extra);
    List<String> problems = new ArrayList<>();
    for (Row row : rows.values()) {
      if (Integer.parseInt(row.code()) != delphi.get(row.key()))
        problems.add(row.key() + ": matrix code " + row.code() + " != Delphi " + delphi.get(row.key()));
      boolean inJava = javaConstants.containsKey(row.key());
      if (inJava && !row.javaConstant().equals("yes"))
        problems.add(row.key() + ": ProtocolConstants defines it but java_constant=" + row.javaConstant());
      if (!inJava && row.javaConstant().equals("yes"))
        problems.add(row.key() + ": java_constant=yes but ProtocolConstants has no such field");
      if (inJava && javaConstants.get(row.key()).intValue() != delphi.get(row.key()).intValue())
        problems.add(row.key() + ": ProtocolConstants renumbered to " + javaConstants.get(row.key()));
    }
    // A new Java constant without a matrix row is caught here (the reverse direction).
    Set<String> javaOnly = new TreeSet<>(javaConstants.keySet());
    javaOnly.removeAll(delphi.keySet());
    assertTrue(javaOnly.isEmpty(), "ProtocolConstants has " + section + " fields unknown to Delphi: " + javaOnly);
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  // --------------------------------------------------------------- wire gate

  @Test
  void cmWireColumnMatchesTheActualGameEntryPoint() throws Exception {
    Method supported = GameProtocolAdapter.class.getDeclaredMethod("isSupported", int.class);
    supported.setAccessible(true);
    List<String> problems = new ArrayList<>();
    for (Row row : rowsByKey("CM").values()) {
      boolean gameHandled;
      try {
        gameHandled = (boolean) supported.invoke(null, Integer.parseInt(row.code()));
      } catch (InvocationTargetException error) {
        throw new IllegalStateException(error);
      }
      if (gameHandled != row.wire().equals("gate-game"))
        problems.add(row.key() + ": isSupported(" + row.code() + ")=" + gameHandled
            + " but wire=" + row.wire());
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  @Test
  void cmWireColumnMatchesTheActualLoginEntryPoint() {
    String source = readUtf8(GATE_RELATIVE);
    Set<Integer> loginHandled = new HashSet<>();
    Matcher matcher =
        Pattern.compile("(?:case|==)\\s*ProtocolConstants\\.(CM_\\w+)").matcher(source);
    while (matcher.find()) loginHandled.add(constant(matcher.group(1)));
    assertFalse(loginHandled.isEmpty(), "no dispatch found in LegacyGateHandler");
    List<String> problems = new ArrayList<>();
    for (Row row : rowsByKey("CM").values()) {
      boolean handled = loginHandled.contains(Integer.parseInt(row.code()));
      if (handled != row.wire().equals("gate-login"))
        problems.add(row.key() + ": login dispatch " + handled + " but wire=" + row.wire());
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  @Test
  void smWireColumnMatchesActualEmissions() {
    Set<Integer> game = emittedCodes(ADAPTER_RELATIVE);
    Set<Integer> login = emittedCodes(GATE_RELATIVE);
    Set<Integer> overlap = new HashSet<>(game);
    overlap.retainAll(login);
    assertTrue(overlap.isEmpty(),
        "same SM code emitted by both entry points; wire column cannot express that: " + overlap);
    List<String> problems = new ArrayList<>();
    for (Row row : rowsByKey("SM").values()) {
      int code = Integer.parseInt(row.code());
      String expected =
          game.contains(code) ? "gate-game" : login.contains(code) ? "gate-login" : "none";
      if (!row.wire().equals(expected))
        problems.add(row.key() + ": emissions say wire=" + expected + " but matrix has " + row.wire());
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  private Set<Integer> emittedCodes(String relativeSource) {
    Set<Integer> codes = new HashSet<>();
    Matcher matcher =
        Pattern.compile("ProtocolConstants\\.(SM_\\w+)").matcher(readUtf8(relativeSource));
    while (matcher.find()) codes.add(constant(matcher.group(1)));
    assertFalse(codes.isEmpty(), "no SM emissions found in " + relativeSource);
    return codes;
  }

  // --------------------------------------------------------------- SKILL gate

  @Test
  void skillSectionCoversEveryDelphiSkillForAllThreeJobs() {
    Map<String, Integer> skills = delphiConstants("SKILL");
    assertEquals(59, skills.size(), "Grobal2.pas SKILL_* set changed; audit before expanding");
    Map<String, Row> rows = rowsByKey("SKILL");
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Integer> skill : skills.entrySet()) {
      for (String job : JOBS) {
        Row row = rows.get(skill.getKey() + ":" + job);
        if (row == null) {
          problems.add("missing " + skill.getKey() + ":" + job);
          continue;
        }
        if (Integer.parseInt(row.code()) != skill.getValue())
          problems.add(row.key() + ": code " + row.code() + " != SKILL id " + skill.getValue());
      }
    }
    Set<String> extra = new TreeSet<>(rows.keySet());
    for (Map.Entry<String, Integer> skill : skills.entrySet())
      for (String job : JOBS) extra.remove(skill.getKey() + ":" + job);
    if (!extra.isEmpty()) problems.add("unknown skill rows: " + extra);
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  // ------------------------------------------------------------- label gate

  @Test
  void merchantGuildSiegeRowsCoverEveryDelphiSpecialLabel() {
    String m2share = readGbk(M2SHARE_RELATIVE);
    int begin = m2share.indexOf("sSL_SENDMSG");
    int end = m2share.indexOf("sHIREGUARDOK");
    assertTrue(begin >= 0 && end > begin, "M2Share special-label block moved; re-audit the gate");
    Set<String> delphi = new TreeSet<>();
    Matcher matcher = LABEL_CONST.matcher(m2share.substring(begin, end + 64));
    while (matcher.find()) delphi.add(matcher.group(2));
    assertTrue(delphi.size() >= 40, "label block shrank unexpectedly: " + delphi.size());

    Map<String, String> labelOwners = new LinkedHashMap<>(); // label -> section/key
    List<String> problems = new ArrayList<>();
    for (Row row : matrix()) {
      if (!LABEL_SECTIONS.contains(row.section()) || !row.isLabelKey()) continue;
      if (labelOwners.put(row.key(), row.section()) != null)
        problems.add(row.key() + ": claimed by two sections");
    }
    Set<String> missing = new TreeSet<>(delphi);
    missing.removeAll(labelOwners.keySet());
    if (!missing.isEmpty()) problems.add("Delphi labels without a matrix row: " + missing);
    Set<String> extra = new TreeSet<>(labelOwners.keySet());
    extra.removeAll(delphi);
    if (!extra.isEmpty()) problems.add("matrix labels unknown to M2Share: " + extra);
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  // ------------------------------------------------------- capability cross-ref

  @Test
  void guildAndDealRowsReferenceExistingCmCodes() {
    Set<String> codes = new TreeSet<>();
    for (Row row : rowsByKey("CM").values()) codes.add(row.code());
    List<String> problems = new ArrayList<>();
    for (Row row : matrix()) {
      if (!(row.section().equals("GUILD") || row.section().equals("DEAL"))
          || row.isLabelKey() || row.code().equals("-")) continue;
      if (!codes.contains(row.code()))
        problems.add(row.section() + "/" + row.key() + ": code " + row.code() + " has no CM row");
    }
    assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
  }

  // ------------------------------------------------------------------ parsing

  private static Path repoRoot() {
    String configured = System.getProperty("mir2.repo.root");
    if (configured != null) {
      Path root = Path.of(configured);
      if (Files.isRegularFile(root.resolve(GROBAL_RELATIVE))) return root;
      fail("mir2.repo.root does not contain the Delphi sources: " + root);
    }
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path current = dir; current != null; current = current.getParent()) {
      if (Files.isRegularFile(current.resolve(GROBAL_RELATIVE))
          && Files.isRegularFile(current.resolve(MATRIX_RELATIVE))) return current;
    }
    return fail("cannot locate the repository root above " + dir
        + " (expected " + GROBAL_RELATIVE + " and " + MATRIX_RELATIVE + ")");
  }

  private static List<Row> matrix() {
    List<String> lines;
    try {
      lines = Files.readAllLines(repoRoot().resolve(MATRIX_RELATIVE), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    List<Row> rows = new ArrayList<>();
    boolean headerSeen = false;
    for (String line : lines) {
      if (line.isBlank() || line.startsWith("#")) continue;
      if (!headerSeen) {
        assertEquals("section\tkey\tcode\tjava_constant\twire\tstatus\tevidence\tdelphi_ref\tnotes",
            line, "matrix header changed — update the gate and the schema comment together");
        headerSeen = true;
        continue;
      }
      String[] columns = line.split("\t", -1);
      assertEquals(9, columns.length, "row must have 9 columns: " + line);
      rows.add(new Row(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5],
          columns[6], columns[7], columns[8]));
    }
    assertTrue(headerSeen, "matrix header missing");
    return rows;
  }

  private static Map<String, Row> rowsByKey(String section) {
    Map<String, Row> rows = new LinkedHashMap<>();
    for (Row row : matrix()) if (row.section().equals(section)) rows.put(row.key(), row);
    return rows;
  }

  private static Map<String, Integer> delphiConstants(String prefix) {
    Map<String, Integer> constants = new LinkedHashMap<>();
    Matcher matcher = DELPHI_CONST.matcher(readGbk(GROBAL_RELATIVE));
    while (matcher.find())
      if (matcher.group(1).startsWith(prefix + "_"))
        constants.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
    assertFalse(constants.isEmpty(), "no Delphi constants for " + prefix);
    return constants;
  }

  private static Map<String, Integer> javaConstants(String prefix) {
    Map<String, Integer> constants = new HashMap<>();
    for (Field field : ProtocolConstants.class.getFields()) {
      if (field.getName().startsWith(prefix + "_") && field.getType() == int.class) {
        try {
          constants.put(field.getName(), field.getInt(null));
        } catch (IllegalAccessException error) {
          throw new IllegalStateException(error);
        }
      }
    }
    assertFalse(constants.isEmpty(), "no ProtocolConstants for " + prefix);
    return constants;
  }

  private static int constant(String name) {
    try {
      return ProtocolConstants.class.getField(name).getInt(null);
    } catch (NoSuchFieldException | IllegalAccessException error) {
      return fail("source references unknown ProtocolConstants." + name, error);
    }
  }

  private static Set<String> testClassNames() {
    Path root = repoRoot().resolve("java-server");
    Set<String> names = new HashSet<>();
    try (Stream<Path> paths = Files.walk(root)) {
      names = new HashSet<>(paths
          .filter(path -> path.toString().contains("/src/test/java/"))
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith("Test.java"))
          .map(name -> name.substring(0, name.length() - ".java".length()))
          .toList());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    assertFalse(names.isEmpty());
    return names;
  }

  private static String readGbk(String relative) {
    try {
      return Files.readString(repoRoot().resolve(relative), Charset.forName("GBK"));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String readUtf8(String relative) {
    try {
      return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
