package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TBaseObject.InSafeZone} (ObjBase.pas:21527) and the target filter in
 * {@code IsAttackTarget} (ObjBase.pas:21370): monsters may not attack a player standing on a
 * town square.
 */
class WorldSafeZoneTest {
  private final AtomicLong now = new AtomicLong(1_000_000);

  @Test
  void monstersNeverAttackAPlayerStandingInsideAStartPointSafeZone() {
    GameMap town = GameMap.empty("0", "比奇省", 60, 60);
    town.addStartPoint(new StartPoint(new Position(30, 30), 10));
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(town)) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("新手", "0", new Position(30, 30), Direction.DOWN, events::add));
      // Ring the spawn exactly the way the old bootstrap did: eight monsters at touching range.
      for (Position seat : List.of(
          new Position(29, 29), new Position(29, 30), new Position(29, 31),
          new Position(30, 29), new Position(30, 31),
          new Position(31, 29), new Position(31, 30), new Position(31, 31))) {
        run(world, world.spawnMonster(MonsterTemplate.chicken(), "0", seat, Direction.DOWN));
      }
      events.clear();

      for (int tick = 0; tick < 40; tick++) {
        advance(1_000);
        world.tickOnce();
      }

      assertFalse(events.stream().anyMatch(event ->
              event instanceof WorldEvent.ObjectStruck struck
                  && struck.victim().id() == player.id()),
          "a monster must not strike a player inside the start point's safe zone");
      Ability after = run(world, world.snapshot(player.id())).ability();
      assertEquals(after.maxHp(), after.hp(), "the player must not have lost any health");
      assertTrue(after.alive());
    }
  }

  @Test
  void theSameMonsterStillAttacksAPlayerStandingOutsideTheZone() {
    // The control case for the test above: same map, same monster, but the player stands
    // well outside the town square, so the IsAttackTarget filter must not shield it.
    GameMap map = GameMap.empty("0", "比奇省", 60, 60);
    map.addStartPoint(new StartPoint(new Position(10, 10), 10));
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(map)) {
      assertFalse(map.isSafeZone(new Position(40, 40)), "the ambush site is unprotected");
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("野游", "0", new Position(40, 40), Direction.RIGHT, events::add));
      run(world, world.spawnMonster(
          MonsterTemplate.chicken(), "0", new Position(41, 40), Direction.LEFT));
      events.clear();

      for (int tick = 0; tick < 10; tick++) {
        advance(1_000);
        world.tickOnce();
      }

      assertTrue(events.stream().anyMatch(event ->
              event instanceof WorldEvent.ObjectStruck struck
                  && struck.victim().id() == player.id()),
          "outside the safe zone the monster attacks as before");
    }
  }

  @Test
  void aMapWideSafeFlagProtectsEveryCell() {
    MapFlags safe = new MapFlags(true, false, false, false, false, false, false, "", false,
        true, false, -1, -1);
    GameMap sanctuary = GameMap.empty("0", "比奇省", 40, 40, safe);
    assertTrue(sanctuary.isSafeZone(new Position(0, 0)));
    assertTrue(sanctuary.isSafeZone(new Position(39, 39)));

    GameMap wild = GameMap.empty("1", "野外", 40, 40);
    assertFalse(wild.isSafeZone(new Position(20, 20)));
  }

  @Test
  void startPointZonesAreSquaresMeasuredPerAxis() {
    GameMap town = GameMap.empty("0", "比奇省", 60, 60);
    town.addStartPoint(new StartPoint(new Position(30, 30), 10));

    // InSafeZone compares |dx| and |dy| separately, so the corner of the square counts.
    assertTrue(town.isSafeZone(new Position(40, 40)));
    assertTrue(town.isSafeZone(new Position(20, 20)));
    assertFalse(town.isSafeZone(new Position(41, 30)));
    assertFalse(town.isSafeZone(new Position(30, 41)));
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
