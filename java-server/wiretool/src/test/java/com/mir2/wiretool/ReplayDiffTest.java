package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplayDiffTest {

  @Test
  void identicalStreamsPassByteLevel() {
    ReplayDiff.Result result = ReplayDiff.compare(
        List.of(b(1, 2, 3), b(4, 5)), List.of(b(1, 2, 3), b(4, 5)), Set.of(), 0);
    assertEquals(2, result.count(ReplayDiff.Status.IDENTICAL));
    assertTrue(result.passed(false));
    assertTrue(result.passed(true));
  }

  @Test
  void contentDiffReportsFirstMismatchOffset() {
    ReplayDiff.Result result = ReplayDiff.compare(
        List.of(b(1, 2, 3, 4)), List.of(b(1, 2, 9, 4)), Set.of(), 0);
    assertEquals(1, result.count(ReplayDiff.Status.CONTENT));
    assertEquals(2, result.rows().get(0).firstDiffOffset());
    assertFalse(result.passed(false));
    assertTrue(result.passed(true)); // structural mode tolerates content drift
  }

  @Test
  void prefixFramesPointToCommonLength() {
    ReplayDiff.Result result = ReplayDiff.compare(
        List.of(b(1, 2, 3, 4)), List.of(b(1, 2)), Set.of(), 0);
    assertEquals(ReplayDiff.Status.CONTENT, result.rows().get(0).status());
    assertEquals(2, result.rows().get(0).firstDiffOffset());
  }

  @Test
  void missingAndExtraAreStructuralFailures() {
    ReplayDiff.Result missing = ReplayDiff.compare(
        List.of(b(1), b(2), b(3)), List.of(b(1), b(2)), Set.of(), 0);
    assertEquals(1, missing.count(ReplayDiff.Status.MISSING));
    assertFalse(missing.passed(true));
    assertFalse(missing.passed(false));

    ReplayDiff.Result extra = ReplayDiff.compare(
        List.of(b(1)), List.of(b(1), b(2)), Set.of(), 0);
    assertEquals(1, extra.count(ReplayDiff.Status.EXTRA));
    assertFalse(extra.passed(true));
  }

  @Test
  void skipListExcludesKnownVolatileFramesFromTheVerdict() {
    ReplayDiff.Result result = ReplayDiff.compare(
        List.of(b(1, 1), b(2, 2), b(3, 3)),
        List.of(b(1, 1), b(9, 9), b(3, 3)), Set.of(1), 0);
    assertEquals(1, result.skipped());
    assertEquals(1, result.count(ReplayDiff.Status.SKIPPED));
    assertTrue(result.passed(false));
    assertTrue(result.passed(true));
  }

  @Test
  void firstDiffHelperHandlesEqualInputs() {
    assertEquals(3, ReplayDiff.firstDiff(b(1, 2, 3), b(1, 2, 4)));
    assertEquals(3, ReplayDiff.firstDiff(b(1, 2, 3), b(1, 2, 3)));
  }

  private static byte[] b(int... values) {
    byte[] bytes = new byte[values.length];
    for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
    return bytes;
  }
}
