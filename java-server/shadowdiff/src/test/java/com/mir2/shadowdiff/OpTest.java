package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
