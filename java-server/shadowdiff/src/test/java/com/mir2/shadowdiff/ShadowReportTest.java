package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadowReportTest {
  @TempDir
  Path tempDir;

  private static StateSnapshot state(int x, int y) {
    return new StateSnapshot("0", x, y, 4, 15, 15, 15, 15, 1, 0, List.of(), List.of());
  }

  @Test
  void writesMarkdownAndCsvWithVerdictAndDiffDetail() throws Exception {
    OpObservation left = new OpObservation("walk 4", List.of("+GOOD"),
        List.of("SM_WALK"), state(10, 11));
    OpObservation right = new OpObservation("walk 4", List.of("+FAIL"),
        List.of(), state(10, 10));
    ShadowDiff.Result result = ShadowDiff.compare("delphi", "java",
        List.of(left), List.of(right), false);

    Path markdown = ShadowReport.writeAll(tempDir, result, Op.parseScript("walk 4"));
    assertTrue(Files.isRegularFile(markdown));
    String text = Files.readString(markdown);
    assertTrue(text.contains("## 结论：FAIL ❌"), text);
    assertTrue(text.contains("`delphi`"));
    assertTrue(text.contains("`java`"));
    assertTrue(text.contains("状态差异 1 步"));
    assertTrue(text.contains("cell: (10,11) vs (10,10)"));
    assertTrue(text.contains("## 操作脚本"));

    Path csv = tempDir.resolve("shadow-report.csv");
    assertTrue(Files.isRegularFile(csv));
    List<String> lines = Files.readAllLines(csv);
    assertEquals("index,op,severity,details", lines.get(0));
    assertTrue(lines.get(1).startsWith("0,\"walk 4\",STATE,"));
  }

  @Test
  void passReportSaysPass() throws Exception {
    OpObservation same = new OpObservation("bag", List.of(), List.of("SM_BAGITEMS"),
        state(10, 10));
    ShadowDiff.Result result = ShadowDiff.compare("left", "right",
        List.of(same), List.of(same), true);
    String text = ShadowReport.markdown(result, List.of());
    assertTrue(text.contains("## 结论：PASS ✅"));
    assertTrue(text.contains("严格"));
  }
}
