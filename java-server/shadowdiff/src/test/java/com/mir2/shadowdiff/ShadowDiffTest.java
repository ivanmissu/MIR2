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

  // ------------------------------------------------------------ W30: group + name-colour state

  private static StateSnapshot duoState(int x, int y, int hp, List<String> bag,
      List<String> group, int nameColor) {
    return new StateSnapshot("0", x, y, 4, hp, 15, 15, 15, 1, 0, 0, bag, List.of(),
        List.of(), List.of(), -1, group, nameColor);
  }

  @Test
  void groupRosterDriftIsAStateFailure() {
    OpObservation left = observation("p1 groupcreate shadow02", List.of(),
        List.of("SM_CREATEGROUP_OK", "SM_GROUPMEMBERS"),
        duoState(20, 20, 15, List.of(), List.of("shadow01", "shadow02"), -1));
    OpObservation right = observation("p1 groupcreate shadow02", List.of(),
        List.of("SM_CREATEGROUP_OK", "SM_GROUPMEMBERS"),
        duoState(20, 20, 15, List.of(), List.of("shadow01"), -1));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    ShadowDiff.Entry entry = result.entries().get(0);
    assertEquals(ShadowDiff.Severity.STATE, entry.severity());
    assertTrue(entry.details().stream().anyMatch(detail -> detail.startsWith("group:")),
        entry.details().toString());
  }

  @Test
  void nameColourDriftIsAStateFailure() {
    // The wire-visible trace of the PK model: the aggressor's tint on the first blow.
    OpObservation left = observation("p2 hit 6", List.of("+GOOD"),
        List.of("SM_CHANGENAMECOLOR"), duoState(20, 20, 12, List.of(), List.of(), 47));
    OpObservation right = observation("p2 hit 6", List.of("+GOOD"),
        List.of("SM_CHANGENAMECOLOR"), duoState(20, 20, 12, List.of(), List.of(), -1));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    assertTrue(result.entries().get(0).details().stream()
        .anyMatch(detail -> detail.startsWith("nameColor:")));
  }

  @Test
  void learnedSkillTrainingDriftIsAStateFailure() {
    StateSnapshot leftState = new StateSnapshot("0", 20, 20, 4, 170, 251, 300, 300,
        31, 0, 0, List.of(), List.of(), List.of(), List.of(), 1_000_800,
        List.of(), -1, List.of(), List.of("magic=29 level=0 train=3 key=0"));
    StateSnapshot rightState = new StateSnapshot("0", 20, 20, 4, 170, 251, 300, 300,
        31, 0, 0, List.of(), List.of(), List.of(), List.of(), 1_000_800,
        List.of(), -1, List.of(), List.of("magic=29 level=0 train=1 key=0"));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(observation("tick 16", List.of(), List.of("SM_MAGIC_LVEXP"), leftState)),
        List.of(observation("tick 16", List.of(), List.of("SM_MAGIC_LVEXP"), rightState)), false);
    assertFalse(result.passed());
    assertEquals(ShadowDiff.Severity.STATE, result.entries().getFirst().severity());
    assertTrue(result.entries().getFirst().details().stream()
        .anyMatch(detail -> detail.startsWith("skills:")));
  }

  @Test
  void emptyGroupAndUnpaintedNamesStayInvisibleToTheDiffer() {
    // Pre-existing scripts must not start failing just because two new fields exist.
    StateSnapshot legacy = new StateSnapshot("0", 20, 20, 4, 15, 15, 15, 15,
        1, 0, 0, List.of(), List.of(), List.of());
    StateSnapshot w30 = new StateSnapshot("0", 20, 20, 4, 15, 15, 15, 15,
        1, 0, 0, List.of(), List.of(), List.of(), List.of(), -1, List.of(), -1);
    assertEquals(legacy, w30);
    assertFalse(legacy.describe().contains("group="));
    assertFalse(legacy.describe().contains("nameColor="));
  }

  // ------------------------------------------------------- W54: packed AC/MAC defence words

  /** The level-22 幽灵盾 caster mid-window: worn charm, trained row, one buffed defence word. */
  private static StateSnapshot defenceState(int ac, int mac) {
    return new StateSnapshot("0", 20, 20, 4, 150, 150, 138, 146, 22, 0, 0,
        List.of(), List.of("护身符 9900/10000"), List.of(), List.of(), 1_054_900,
        List.of(), -1, List.of(), List.of("magic=14 level=0 train=3 key=0"), ac, mac);
  }

  @Test
  void defenceWordDriftIsAStateFailureNotJustAMessageHint() {
    // The whole W54 observability claim: a duration-roll divergence must surface as a packed
    // defence-word STATE difference at the expiry bucket — not merely as an advisory message
    // multiset difference about who received the 恢复正常 hint. Left is still inside its
    // {50,51,52}s window (mac 2-10); right's window already lapsed (mac back to 2-5).
    OpObservation left = observation("tick 10", List.of(), List.of("SM_SYSMESSAGE"),
        defenceState(0, (10 << 16) | 2));
    OpObservation right = observation("tick 10", List.of(), List.of("SM_SYSMESSAGE"),
        defenceState(0, (5 << 16) | 2));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    ShadowDiff.Entry entry = result.entries().getFirst();
    assertEquals(ShadowDiff.Severity.STATE, entry.severity());
    assertTrue(entry.details().stream().anyMatch(detail -> detail.equals("mac: 2-10 vs 2-5")),
        entry.details().toString());
  }

  @Test
  void acWordDriftIsAStateFailureForThePhysicalArmToo() {
    // 神圣战甲术 lifts the AC word instead; the differ must treat the twin identically.
    OpObservation left = observation("tick 5", List.of(), List.of("SM_SYSMESSAGE"),
        defenceState(5 << 16, (5 << 16) | 2));
    OpObservation right = observation("tick 5", List.of(), List.of("SM_SYSMESSAGE"),
        defenceState(0, (5 << 16) | 2));
    ShadowDiff.Result result = ShadowDiff.compare("L", "R",
        List.of(left), List.of(right), false);
    assertFalse(result.passed());
    assertEquals(ShadowDiff.Severity.STATE, result.entries().getFirst().severity());
    assertTrue(result.entries().getFirst().details().stream()
        .anyMatch(detail -> detail.startsWith("ac:")),
        result.entries().getFirst().details().toString());
  }

  @Test
  void defenceWordsRenderAsRangesAndStayInvisibleUntilTheFirstAbilityFrame() {
    assertEquals("2-10", StateSnapshot.abilityRange((10 << 16) | 2));
    assertEquals("0-5", StateSnapshot.abilityRange(5 << 16));
    assertEquals("n/a", StateSnapshot.abilityRange(-1));
    // Pre-W54 snapshots — and the pre-ability-frame snapshots of any era — must not render
    // the words, or every existing report line would change shape.
    StateSnapshot compat = new StateSnapshot("0", 20, 20, 4, 150, 150, 146, 146, 22, 0, 0,
        List.of(), List.of(), List.of(), List.of(), 1_000_000,
        List.of(), -1, List.of(), List.of());
    assertEquals(-1, compat.ac());
    assertEquals(-1, compat.mac());
    assertFalse(compat.describe().contains("ac="));
    assertFalse(compat.describe().contains("mac="));
    StateSnapshot painted = defenceState(0, (10 << 16) | 2);
    assertTrue(painted.describe().endsWith("ac=0-0 mac=2-10"), painted.describe());
  }
}
