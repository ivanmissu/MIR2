package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.world.Direction;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpTest {

  @Test
  void parsesEveryKeywordWithArguments() {
    List<Op> ops = Op.parseScript("""
        turn 3
        walk 0
        run 7
        hit 2
        heavyhit 4
        bighit 6
        pickup
        bag
        say hello world
        drop 木剑
        eat 金创药(小量)
        takeon 木剑
        takeoff 木剑
        sleep 250
        relog
        """);
    assertEquals(15, ops.size());
    assertEquals(Op.Kind.TURN, ops.get(0).kind());
    assertEquals(Direction.DOWN_RIGHT, ops.get(0).direction());
    assertEquals(Direction.UP, ops.get(1).direction());
    assertEquals(Direction.UP_LEFT, ops.get(2).direction());
    assertEquals("hello world", ops.get(8).text());
    assertEquals("木剑", ops.get(9).text());
    assertEquals("金创药(小量)", ops.get(10).text());
    assertEquals(250, ops.get(13).millis());
    assertEquals(Op.Kind.RELOG, ops.get(14).kind());
  }

  @Test
  void ignoresCommentsAndBlankLines() {
    List<Op> ops = Op.parseScript("""
        # full-line comment

        walk 4   # trailing comment
        """);
    assertEquals(1, ops.size());
    assertEquals(Op.Kind.WALK, ops.get(0).kind());
    assertEquals(Direction.DOWN, ops.get(0).direction());
  }

  @Test
  void rejectsUnknownKeywordsWithTheLineNumber() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> Op.parseScript("walk 4\nfly 2\n"));
    assertTrue(error.getMessage().contains("line 2"), error.getMessage());
  }

  @Test
  void rejectsMissingArguments() {
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("walk"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("say"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("sleep"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("walk 9"));
  }

  @Test
  void describeRoundTripsThroughTheParser() {
    List<Op> original = Op.defaultScript();
    String rendered = String.join("\n", original.stream().map(Op::describe).toList());
    assertEquals(original, Op.parseScript(rendered));
  }

  @Test
  void tickIsParsedAsACountAndSurvivesTheRoundTrip() {
    // `tick N` is the W23 addition: the harness-level control that makes world time a
    // scripted quantity instead of a race between two hosts' wall clocks.
    List<Op> ops = Op.parseScript("tick 1\ntick 20\ntick 0\n");
    assertEquals(3, ops.size());
    assertEquals(Op.Kind.TICK, ops.get(0).kind());
    assertEquals(1, ops.get(0).millis());
    assertEquals(20, ops.get(1).millis());
    assertEquals(0, ops.get(2).millis(), "a zero pump is legal: it is just an observation");

    // describe() must render the count back, otherwise a --script round trip would drop it.
    assertEquals("tick 20", ops.get(1).describe());
    assertEquals(ops, Op.parseScript(String.join("\n", ops.stream().map(Op::describe).toList())));

    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("tick"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("tick -1"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("tick soon"));
  }

  @Test
  void aiScriptPumpsTheClockAroundEveryPlayerActionAndRoundTrips() {
    List<Op> script = Op.aiScript();

    // The comparison is only meaningful if world time actually moves: a pumpless AI script
    // would compare two frozen worlds and pass vacuously.
    long pumped = script.stream().filter(op -> op.kind() == Op.Kind.TICK)
        .mapToLong(Op::millis).sum();
    assertTrue(pumped >= 100,
        "the AI script must pump enough world time for a chase to happen, saw " + pumped);

    // No wall-clock sleeps: the whole point is that pacing comes from ticks, not from the
    // host scheduler. One stray `sleep` would reintroduce the race W23 removes.
    assertTrue(script.stream().noneMatch(op -> op.kind() == Op.Kind.SLEEP),
        "the AI script must not fall back to wall-clock sleeps");

    // Player actions must sit between pumps, so acks and AI reactions share a bucket.
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.HIT));
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.WALK));
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.RELOG),
        "the pumped world time must survive the persistence round trip");

    assertEquals(script,
        Op.parseScript(String.join("\n", script.stream().map(Op::describe).toList())));
  }

  @Test
  void defaultScriptStaysDeterministicAndEndsWithARelogCheck() {
    List<Op> script = Op.defaultScript();
    assertTrue(script.size() >= 20, "the smoke script covers the core loop");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.RELOG),
        "the persistence round trip must be exercised");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.HIT));
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.BAG));
  }


  // ------------------------------------------------------------ W30: duo prefixes + group ops

  @Test
  void parsesDuoActorPrefixesAndKeepsUnprefixedLinesPrimary() {
    List<Op> ops = Op.parseScript("""
        walk 4
        p1 turn 2
        p2 walk 6
        p2 hit 0
        relog
        """);
    assertEquals(5, ops.size());
    assertNull(ops.get(0).actor(), "an unprefixed line is the primary session");
    assertEquals(Op.PRIMARY_ACTOR, ops.get(1).actor(), "an explicit p1 stays primary");
    assertEquals(Op.PARTNER_ACTOR, ops.get(2).actor());
    assertTrue(ops.get(2).isPartnerOp());
    assertTrue(ops.get(3).isPartnerOp());
    assertFalse(ops.get(0).isPartnerOp());
    assertFalse(ops.get(1).isPartnerOp());
    assertNull(ops.get(4).actor(), "relog stays unprefixed in solo scripts");
    assertEquals(Op.Kind.RELOG, ops.get(4).kind());

    // describe() renders the prefix so a --script round trip keeps the acting session.
    assertEquals(ops, Op.parseScript(String.join("\n", ops.stream().map(Op::describe).toList())));
    assertEquals("p2 walk 6", ops.get(2).describe());
  }

  @Test
  void parsesTheFourGroupOps() {
    List<Op> ops = Op.parseScript("""
        groupmode 1
        groupmode 0
        p2 groupmode 1
        groupcreate shadow02
        groupadd shadow02
        groupdel shadow02
        """);
    assertEquals(Op.Kind.GROUPMODE, ops.get(0).kind());
    assertEquals("1", ops.get(0).text());
    assertEquals("0", ops.get(1).text());
    assertEquals(Op.PARTNER_ACTOR, ops.get(2).actor());
    assertEquals(Op.Kind.GROUPCREATE, ops.get(3).kind());
    assertEquals("shadow02", ops.get(3).text());
    assertEquals(Op.Kind.GROUPADD, ops.get(4).kind());
    assertEquals(Op.Kind.GROUPDEL, ops.get(5).kind());

    assertEquals(ops,
        Op.parseScript(String.join("\n", ops.stream().map(Op::describe).toList())));

    // CM_GROUPMODE is a boolean switch: anything but 0/1 must not parse.
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("groupmode 2"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("groupmode"));
    assertThrows(IllegalArgumentException.class, () -> Op.parseScript("groupcreate"));
  }

  @Test
  void duoPartyScriptCoversTheWholeInteractionLoop() {
    List<Op> script = Op.duoPartyScript();
    // The refused-first invitation is what makes the -4 branch observable.
    long invitations = script.stream()
        .filter(op -> op.kind() == Op.Kind.GROUPCREATE).count();
    assertEquals(2, invitations, "invite refused (-4), then accepted");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.GROUPDEL));
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.PICKUP),
        "the 鸡肉 drop must be picked up");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.GROUPMODE
        && "1".equals(op.text())));
    // Both players relog, and both act in the script.
    assertEquals(2, script.stream().filter(op -> op.kind() == Op.Kind.RELOG).count());
    assertTrue(script.stream().anyMatch(Op::isPartnerOp));
    // Kills are driven by pumped world time only — no wall-clock sleeps in duo scripts.
    assertTrue(script.stream().noneMatch(op -> op.kind() == Op.Kind.SLEEP));
    long pumped = script.stream().filter(op -> op.kind() == Op.Kind.TICK)
        .mapToLong(Op::millis).sum();
    assertTrue(pumped >= 100, "the party script must pump enough world time for two kills");
    assertTrue(script.stream().anyMatch(op -> op.text() != null && op.text().contains("{p2}")),
        "the built-in duo scripts address the partner by the {p2} placeholder");
  }

  @Test
  void duoDeathPkScriptCoversMurderDeathAndBothRelogins() {
    List<Op> script = Op.duoDeathPkScript();
    assertTrue(script.stream().filter(op -> op.kind() == Op.Kind.HIT).count() >= 8,
        "the murder needs enough blows for 15 HP at warrior damage");
    assertTrue(script.stream().allMatch(op -> op.kind() != Op.Kind.HIT
        || op.isPartnerOp()), "only p2 swings: the victim never fights back");
    assertEquals(2, script.stream().filter(op -> op.kind() == Op.Kind.RELOG).count());
    long pumped = script.stream().filter(op -> op.kind() == Op.Kind.TICK)
        .mapToLong(Op::millis).sum();
    assertTrue(pumped >= 180, "ten spaced blows need at least 10 × 18 ticks");
  }

  @Test
  void persistenceAndLockScriptsExerciseTheItemLifecycle() {
    List<Op> persistence = Op.persistenceScript();
    assertTrue(persistence.stream().anyMatch(op -> op.kind() == Op.Kind.TAKEON
        && "木剑".equals(op.text())));
    assertTrue(persistence.stream().anyMatch(op -> op.kind() == Op.Kind.TAKEON
        && "布衣(男)".equals(op.text())));
    assertEquals(1, persistence.stream()
        .filter(op -> op.kind() == Op.Kind.DROP && "金创药(小量)".equals(op.text())).count());
    assertEquals(1, persistence.stream()
        .filter(op -> op.kind() == Op.Kind.PICKUP).count());
    assertEquals(1, persistence.stream()
        .filter(op -> op.kind() == Op.Kind.EAT).count());
    assertEquals(1, persistence.stream()
        .filter(op -> op.kind() == Op.Kind.TAKEOFF).count());
    assertEquals(1, persistence.stream().filter(op -> op.kind() == Op.Kind.RELOG).count());

    List<Op> lock = Op.lockScript();
    assertEquals(2, lock.stream()
        .filter(op -> op.kind() == Op.Kind.TAKEOFF && "木剑".equals(op.text())).count(),
        "the lock refuses the take-off before and after the relog");
    assertTrue(lock.stream().noneMatch(op -> op.kind() == Op.Kind.EAT));
  }

}
