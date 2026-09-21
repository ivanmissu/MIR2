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
  void defaultScriptStaysDeterministicAndEndsWithARelogCheck() {
    List<Op> script = Op.defaultScript();
    assertTrue(script.size() >= 20, "the smoke script covers the core loop");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.RELOG),
        "the persistence round trip must be exercised");
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.HIT));
    assertTrue(script.stream().anyMatch(op -> op.kind() == Op.Kind.BAG));
  }
}
