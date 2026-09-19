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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W03 combat slice: melee attack, monster AI, death, drop and pickup on a virtual clock. */
class WorldCombatTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void playerKillsAdjacentMonsterAndReceivesExperienceStruckAndDeathEvents() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      WorldObjectSnapshot monster = run(world,
          world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();

      int swings = 0;
      AttackResult last = null;
      while (swings < 20) {
        advance(1_000);
        last = run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        swings++;
        assertTrue(last.accepted(), "the attack interval must allow one swing per second");
        if (last.victimSnapshot().isPresent() && !last.victimSnapshot().orElseThrow().alive()) break;
      }
      assertNotNull(last);
      assertFalse(last.victimSnapshot().orElseThrow().alive(), "the chicken must die within 20 swings");

      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.AttackAccepted));
      assertTrue(events.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectStruck struck && struck.victim().id() == monster.id()));
      WorldEvent.ObjectDied died = events.stream()
          .filter(WorldEvent.ObjectDied.class::isInstance)
          .map(WorldEvent.ObjectDied.class::cast)
          .findFirst().orElseThrow();
      assertEquals(monster.id(), died.victim().id());
      assertEquals(player.id(), died.killerId());

      WorldEvent.ExperienceGained experience = events.stream()
          .filter(WorldEvent.ExperienceGained.class::isInstance)
          .map(WorldEvent.ExperienceGained.class::cast)
          .findFirst().orElseThrow();
      assertEquals(MonsterTemplate.chicken().experience(), experience.gained());
      assertEquals(experience.gained(), experience.total());
    }
  }

  @Test
  void deadMonsterDropsLootThatCanBePickedUpAndThenIsHidden() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      run(world, world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      for (int swing = 0; swing < 20; swing++) {
        advance(1_000);
        AttackResult result = run(world,
            world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        if (result.victimSnapshot().isPresent() && !result.victimSnapshot().orElseThrow().alive()) break;
      }

      WorldEvent.ItemAppeared dropped = events.stream()
          .filter(WorldEvent.ItemAppeared.class::isInstance)
          .map(WorldEvent.ItemAppeared.class::cast)
          .findFirst().orElseThrow();
      // 鸡肉 is a guaranteed (1-in-1) drop, so it must be on the monster's own cell.
      assertEquals("鸡肉", dropped.item().name());
      assertEquals(new Position(6, 5), dropped.item().position());

      // Standing on another cell must not allow the pickup.
      events.clear();
      assertFalse(run(world, world.pickUp(player.id(), new Position(5, 5))));
      assertInstanceOf(WorldEvent.PickupRejected.class, events.getFirst());

      // Walk onto the item cell, which is free once the corpse has been cleaned up.
      advance(10_000);
      world.tickOnce();
      events.clear();
      assertTrue(run(world, world.move(player.id(), new Position(6, 5), Direction.RIGHT, MovementKind.WALK)).moved());
      assertTrue(run(world, world.pickUp(player.id(), new Position(6, 5))));

      WorldEvent.ItemPickedUp pickedUp = events.stream()
          .filter(WorldEvent.ItemPickedUp.class::isInstance)
          .map(WorldEvent.ItemPickedUp.class::cast)
          .findFirst().orElseThrow();
      assertEquals(dropped.item(), pickedUp.item());
      // The bag entry carries the full template, a fresh make index and full durability,
      // mirroring UsrEngn.pas CopyToUserItemFromName.
      assertEquals(StdItems.chickenMeat(), pickedUp.backpackItem().item());
      assertTrue(pickedUp.backpackItem().makeIndex() > 0);
      assertEquals(pickedUp.backpackItem().dura(), pickedUp.backpackItem().duraMax());
      assertTrue(run(world, world.itemsAt("0", new Position(6, 5))).isEmpty());
    }
  }

  @Test
  void monsterChasesAndAttacksTheNearestPlayerOnItsOwnIntervals() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      run(world, world.spawnMonster(MonsterTemplate.orc(), "0", new Position(9, 5), Direction.LEFT));
      events.clear();

      boolean struck = false;
      for (int tick = 0; tick < 40 && !struck; tick++) {
        advance(1_000);
        world.tickOnce();
        struck = events.stream().anyMatch(event ->
            event instanceof WorldEvent.ObjectStruck hit && hit.victim().id() == player.id());
      }
      assertTrue(struck, "an orc four cells away must close in and hit the player");
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectMoved),
          "the chase must be broadcast as movement events");
      assertTrue(run(world, world.snapshot(player.id())).ability().hp() < Ability.defaultPlayer().hp());
    }
  }

  @Test
  void attacksFasterThanTheHitIntervalAreRejectedAndDeadPlayersCannotAct() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      advance(5_000);
      assertTrue(run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT)).accepted());

      advance(100);
      AttackResult tooFast = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertFalse(tooFast.accepted());
      assertEquals(WorldEvent.AttackRejection.TOO_FAST, tooFast.rejection());

      advance(1_000);
      AttackResult stale = run(world,
          world.attack(player.id(), new Position(9, 9), Direction.RIGHT, AttackKind.HIT));
      assertEquals(WorldEvent.AttackRejection.POSITION_MISMATCH, stale.rejection());
    }
  }

  @Test
  void monstersAreVisibleToEnteringPlayersAndDisappearAfterTheCorpseTimeout() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot monster = run(world,
          world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));

      WorldEvent.MapEntered entered = assertInstanceOf(WorldEvent.MapEntered.class, events.getFirst());
      assertEquals(List.of(monster.id()), entered.visibleObjects().stream().map(WorldObjectSnapshot::id).toList());
      assertSame(WorldObjectType.MONSTER, entered.visibleObjects().getFirst().type());

      for (int swing = 0; swing < 20; swing++) {
        advance(1_000);
        AttackResult result = run(world,
            world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        if (result.victimSnapshot().isPresent() && !result.victimSnapshot().orElseThrow().alive()) break;
      }
      assertEquals(0, run(world, world.liveMonsters()));

      events.clear();
      advance(10_000);
      world.tickOnce();
      assertTrue(events.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectDisappeared gone && gone.objectId() == monster.id()));
    }
  }

  private WorldEngine engine(GameMap map) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    // The pickup loop resolves full templates from the minimal standard-item catalog.
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
