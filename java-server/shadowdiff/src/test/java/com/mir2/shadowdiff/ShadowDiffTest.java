package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShadowDiffTest {

  private static StateSnapshot state(int x, int y, int hp, List<String> bag) {
    return new StateSnapshot("0", x, y, 4, hp, 15, 15, 15, 1, 0, bag, List.of());
  }

  private static OpObservation observation(String op, List<String> acks,
      List<String> messages, StateSnapshot state) {
    return new OpObservation(op, acks, messages, state);
  }

  @Test
  void identicalStreamsPass() {
    OpObservation a = observation("walk 4", List.of("+GOOD"), List.of("SM_WALK"),
        state(10, 11, 15, List.of()));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R", List.of(a), List.of(a), false);
    assertTrue(result.passed());
    assertEquals(ShadowDiff.Severity.MATCH, result.entries().get(0).severity());
  }

  @Test
  void stateDriftFails() {
    OpObservation left = observation("walk 4", List.of("+GOOD"), List.of("SM_WALK"),
        state(10, 11, 15, List.of()));
    OpObservation right = observation("walk 4", List.of("+GOOD"), List.of("SM_WALK"),
        state(10, 12, 15, List.of()));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    ShadowDiff.Entry entry = result.entries().get(0);
    assertEquals(ShadowDiff.Severity.STATE, entry.severity());
    assertTrue(entry.details().stream().anyMatch(detail -> detail.startsWith("cell:")),
        entry.details().toString());
  }

  @Test
  void ackVerdictFlipFailsEvenWhenStateConverges() {
    StateSnapshot same = state(10, 10, 15, List.of());
    OpObservation left = observation("walk 4", List.of("+GOOD"), List.of(), same);
    OpObservation right = observation("walk 4", List.of("+FAIL"), List.of(), same);
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    assertEquals(ShadowDiff.Severity.ACKS, result.entries().get(0).severity());
  }

  @Test
  void messageSetDriftIsInformationalUnlessStrict() {
    StateSnapshot same = state(10, 10, 15, List.of());
    OpObservation left = observation("bag", List.of(), List.of("SM_BAGITEMS"), same);
    OpObservation right = observation("bag", List.of(), List.of(), same);

    ShadowDiff.Result lenient = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertTrue(lenient.passed());
    assertEquals(ShadowDiff.Severity.MESSAGES, lenient.entries().get(0).severity());

    ShadowDiff.Result strict = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), true);
    assertFalse(strict.passed());
  }

  @Test
  void messageOrderIsNormalisedBecauseBroadcastsInterleave() {
    StateSnapshot same = state(10, 10, 15, List.of());
    OpObservation left = observation("walk 4", List.of("+GOOD"),
        List.of("SM_WALK", "SM_TURN"), same);
    OpObservation right = observation("walk 4", List.of("+GOOD"),
        List.of("SM_TURN", "SM_WALK"), same);
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), true);
    assertTrue(result.passed());
  }

  @Test
  void bagContentDriftIsAStateDifference() {
    OpObservation left = observation("pickup", List.of("+GOOD"), List.of("SM_ADDITEM"),
        state(10, 10, 15, List.of("鸡肉 10/10")));
    OpObservation right = observation("pickup", List.of("+GOOD"), List.of("SM_ADDITEM"),
        state(10, 10, 15, List.of()));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertEquals(ShadowDiff.Severity.STATE, result.entries().get(0).severity());
    assertTrue(result.entries().get(0).details().stream()
        .anyMatch(detail -> detail.startsWith("bag:")));
  }

  @Test
  void mismatchedStreamLengthsAreAHarnessError() {
    OpObservation a = observation("bag", List.of(), List.of(), state(1, 1, 15, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> ShadowDiff.compare("L", "R", List.of(a, a), List.of(a), false));
  }
}
