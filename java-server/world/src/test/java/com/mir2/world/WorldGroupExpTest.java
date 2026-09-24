package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGroupExpTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void twoPlayerGroupSharesExperienceWithBonus() {
    List<WorldEvent> eventsA = new CopyOnWriteArrayList<>();
    List<WorldEvent> eventsB = new CopyOnWriteArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 30, 30))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("WarriorA", "0", new Position(5, 5), Direction.RIGHT, eventsA::add));
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("WarriorB", "0", new Position(5, 6), Direction.RIGHT, eventsB::add));
      run(world, world.createGroup(a.id(), "WarriorB"));

      WorldObjectSnapshot chicken = run(world,
          world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      eventsA.clear();
      eventsB.clear();

      for (int i = 0; i < 20; i++) {
        advance(1_000);
        AttackResult res = run(world,
            world.attack(a.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        if (res.victimSnapshot().isPresent() && !res.victimSnapshot().orElseThrow().alive()) break;
      }

      // Chicken has 9 base exp. 2 players in party -> 1.3x bonus = round(9 * 1.3) = 12 total exp.
      // Both are Lv 1, so each gets 6 exp.
      WorldEvent.ExperienceGained expA = eventsA.stream()
          .filter(WorldEvent.ExperienceGained.class::isInstance)
          .map(WorldEvent.ExperienceGained.class::cast)
          .findFirst().orElseThrow();
      assertEquals(6, expA.gained());

      WorldEvent.ExperienceGained expB = eventsB.stream()
          .filter(WorldEvent.ExperienceGained.class::isInstance)
          .map(WorldEvent.ExperienceGained.class::cast)
          .findFirst().orElseThrow();
      assertEquals(6, expB.gained());
    }
  }

  @Test
  void farAwayTeammateDoesNotReceiveExperienceAndKillerGetsBaseExp() {
    List<WorldEvent> eventsA = new CopyOnWriteArrayList<>();
    List<WorldEvent> eventsB = new CopyOnWriteArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 50, 50))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("WarriorA", "0", new Position(5, 5), Direction.RIGHT, eventsA::add));
      // WarriorB is at (25, 25), distance > 12 tiles away
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("WarriorB", "0", new Position(25, 25), Direction.RIGHT, eventsB::add));
      run(world, world.createGroup(a.id(), "WarriorB"));

      run(world,
          world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      eventsA.clear();
      eventsB.clear();

      for (int i = 0; i < 20; i++) {
        advance(1_000);
        AttackResult res = run(world,
            world.attack(a.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        if (res.victimSnapshot().isPresent() && !res.victimSnapshot().orElseThrow().alive()) break;
      }

      // Teammate is too far: only 1 player eligible -> Killer receives full 100% base exp (9) with no bonus
      WorldEvent.ExperienceGained expA = eventsA.stream()
          .filter(WorldEvent.ExperienceGained.class::isInstance)
          .map(WorldEvent.ExperienceGained.class::cast)
          .findFirst().orElseThrow();
      assertEquals(MonsterTemplate.chicken().experience(), expA.gained());

      assertFalse(eventsB.stream().anyMatch(WorldEvent.ExperienceGained.class::isInstance));
    }
  }

  private WorldEngine engine(GameMap map) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
