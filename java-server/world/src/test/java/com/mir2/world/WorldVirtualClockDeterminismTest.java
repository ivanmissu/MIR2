package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * W23: the engine-level contract that makes <em>moving</em> monsters comparable across two
 * processes.
 *
 * <p>{@link WorldSeedDeterminismTest} pinned the randomness half (same seed ⇒ same damage)
 * and could therefore only compare a stationary dummy. The other half is time: a monster's
 * walk/attack cadence is an interval check against the clock, so two processes only agree
 * while they read the same clock. These tests pin that a virtual world's AI is a pure
 * function of the tick count, and that a manual world's is a pure function of the pump.
 */
class WorldVirtualClockDeterminismTest {

  @Test
  void aVirtualWorldsChaseIsAPureFunctionOfTheTickCount() {
    // Two independent engines, same seed, same tick budget — but ticked at wildly different
    // real-world speeds (one in a tight loop, one with the loop body doing extra work).
    // With tick-derived time the chase must come out identical anyway.
    List<String> left = chasePath(40, 0);
    List<String> right = chasePath(40, 3);

    assertEquals(left, right,
        "a virtual world's monster AI must depend on ticks, not on how fast they ran");
    assertTrue(left.size() > 1,
        "the fixture must actually produce movement, otherwise the comparison is vacuous");
  }

  @Test
  void theMonsterActuallyClosesTheDistanceSoTheFixtureIsNotVacuous() {
    List<String> path = chasePath(40, 0);
    assertNotEquals(path.getFirst(), path.getLast(),
        "the chaser must move; a frozen monster would make the determinism claim empty");
  }

  @Test
  void virtualTimeAdvancesExactlyOneTickIntervalPerTick() {
    WorldClock clock = WorldClock.virtual(1_000_000, 50);
    try (WorldEngine world = engine(clock, WorldRandom.seeded(11))) {
      assertEquals(1_000_000, world.now());
      world.tickOnce();
      assertEquals(1_000_050, world.now());
      for (int index = 0; index < 9; index++) world.tickOnce();
      assertEquals(1_000_000 + 10 * 50, world.now());
      assertEquals(clock, world.worldClock().orElseThrow());
    }
  }

  @Test
  void aManualWorldFreezesUntilItIsPumped() {
    WorldClock clock = WorldClock.manual(1_000_000, 50);
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(clock, WorldRandom.seeded(11))) {
      run(world, world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT, events::add));
      WorldObjectSnapshot chaser = run(world, world.spawnMonster(
          MonsterTemplate.orc(), "0", new Position(26, 20), Direction.LEFT));
      Position spawnCell = chaser.position();
      events.clear();

      // The engine's own tick must not move a manual clock, and the frozen world must not
      // let the AI act: this is what keeps an op's observation bucket free of stray events.
      for (int index = 0; index < 200; index++) world.tickOnce();
      assertEquals(1_000_000, world.now(), "tickOnce must not advance a manual clock");
      assertEquals(spawnCell, run(world, world.snapshot(chaser.id())).position(),
          "a frozen world's monster must not step");
      assertFalse(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectMoved));

      // Pumping releases exactly the requested amount of world time. The pump is itself a
      // queued command, so it needs a tick to be picked up — runTicked does that.
      long now = runTicked(world, world.advanceTicks(60));
      assertEquals(1_000_000 + 60 * 50, now);
      assertNotEquals(spawnCell, run(world, world.snapshot(chaser.id())).position(),
          "the pump must actually let the AI run");
    }
  }

  @Test
  void twoManualWorldsPumpedIdenticallyEndUpInTheSameState() {
    // The shadow-harness invariant reduced to one process pair: same seed, same pump
    // schedule ⇒ same monster cells, regardless of interleaving with the host scheduler.
    assertEquals(pumpedPath(List.of(20, 20, 20)), pumpedPath(List.of(20, 20, 20)));
    // ...and the pump is the thing that matters: a different schedule reaches a different
    // point in the chase, so the comparison above is not satisfied by a frozen world.
    assertNotEquals(pumpedPath(List.of(20, 20, 20)), pumpedPath(List.of(1)));
  }

  @Test
  void pumpingIsANoOpOnVirtualAndSystemWorlds() {
    // advanceTicks is the manual pump only; on any other world time is not the caller's.
    try (WorldEngine virtual = engine(WorldClock.virtual(1_000_000, 50), WorldRandom.seeded(3))) {
      long before = virtual.now();
      // The queued command still needs a tick to run, which advances a virtual clock once.
      long after = runTicked(virtual, virtual.advanceTicks(500));
      assertEquals(before + 50, after, "a virtual world ignores the pump");
    }
    try (WorldEngine system = engine(WorldClock.system(), WorldRandom.seeded(3))) {
      long after = runTicked(system, system.advanceTicks(500));
      assertTrue(Math.abs(after - System.currentTimeMillis()) < 5_000,
          "a system world keeps reading host time");
    }
  }

  // ------------------------------------------------------------ fixture

  /**
   * Runs an orc chase for {@code ticks} ticks on a virtual world and returns the monster's
   * cell after each tick. {@code busyWork} makes the caller's loop take a different amount
   * of real time per tick — the stand-in for two hosts under different load.
   */
  private static List<String> chasePath(int ticks, int busyWork) {
    List<String> path = new ArrayList<>();
    try (WorldEngine world = engine(WorldClock.virtual(1_000_000, 50), WorldRandom.seeded(20260923))) {
      run(world, world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT, event -> {}));
      WorldObjectSnapshot chaser = run(world, world.spawnMonster(
          MonsterTemplate.orc(), "0", new Position(26, 20), Direction.LEFT));
      for (int tick = 0; tick < ticks; tick++) {
        for (int spin = 0; spin < busyWork; spin++) {
          // Burn real time without touching the world: on a wall-clock world this would
          // shift every subsequent interval check; on a virtual one it must change nothing.
          try {
            Thread.sleep(1);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        world.tickOnce();
        Position cell = run(world, world.snapshot(chaser.id())).position();
        String line = cell.x() + "," + cell.y();
        if (path.isEmpty() || !path.getLast().equals(line)) path.add(line);
      }
    }
    return path;
  }

  /** The chaser's cell after each pump in {@code schedule}, on a manual world. */
  private static List<String> pumpedPath(List<Integer> schedule) {
    List<String> path = new ArrayList<>();
    try (WorldEngine world = engine(WorldClock.manual(1_000_000, 50), WorldRandom.seeded(20260923))) {
      run(world, world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT, event -> {}));
      WorldObjectSnapshot chaser = run(world, world.spawnMonster(
          MonsterTemplate.orc(), "0", new Position(26, 20), Direction.LEFT));
      for (int ticks : schedule) {
        runTicked(world, world.advanceTicks(ticks));
        Position cell = run(world, world.snapshot(chaser.id())).position();
        path.add(cell.x() + "," + cell.y());
      }
    }
    return path;
  }

  private static WorldEngine engine(WorldClock clock, WorldRandom random) {
    WorldEngine.Config config = new WorldEngine.Config(
        Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000, 200, 600_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 40, 40)),
        clock, random, PlayerStateStore.none(), ItemDatabase.empty(), () -> 12);
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  /** Drains a queued command without letting the surrounding assertion depend on extra ticks. */
  private static <T> T runTicked(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
