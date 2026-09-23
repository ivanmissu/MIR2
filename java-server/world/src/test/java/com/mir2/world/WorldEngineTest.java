package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEngineTest {
  @Test
  void nearbySpawnAvoidsOccupiedCellAndPreservesAppearance() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 8, 8)))) {
      var first = world.enterPlayer("first", "0", new Position(3, 3), Direction.DOWN, ignored -> {});
      world.tickOnce();
      assertEquals(new Position(3, 3), first.join().position());

      var second = world.enterPlayerNear("second", "0", new Position(3, 3), Direction.UP,
          0x12345678, 0x23456789, ignored -> {});
      world.tickOnce();
      WorldObjectSnapshot snapshot = second.join();
      assertNotEquals(new Position(3, 3), snapshot.position());
      assertEquals(0x12345678, snapshot.feature());
      assertEquals(0x23456789, snapshot.status());
    }
  }

  @Test
  void spawnedNpcAppearsToViewersBlocksItsCellAndCannotBeAttacked() {
    List<WorldEvent> viewerEvents = new CopyOnWriteArrayList<>();
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "比奇省", 20, 20)))) {
      var entered = world.enterPlayer("战士", "0", new Position(6, 5), Direction.DOWN,
          event -> viewerEvents.add(event));
      world.tickOnce();
      int playerId = entered.join().id();
      viewerEvents.clear();

      var spawned = world.spawnNpc("老兵", "0", new Position(7, 5), 3, Direction.DOWN);
      world.tickOnce();
      WorldObjectSnapshot npc = spawned.join();
      // MakeMonsterFeature(RC_NPC=50, 0, wAppr): low byte 50, high word the Npc.wil index.
      assertEquals((3 << 16) | 50, npc.feature());
      assertEquals("老兵", npc.name());
      assertEquals(new Position(7, 5), npc.position());
      assertEquals(WorldEvent.ObjectAppeared.class, viewerEvents.getLast().getClass(),
          "a viewer in range must see the NPC appear");

      // The NPC's cell is solid: a walk onto it is rejected, exactly like a monster's.
      var blocked = world.move(playerId, new Position(7, 5), Direction.RIGHT, MovementKind.WALK);
      world.tickOnce();
      assertFalse(blocked.join().moved(), "the NPC must block its cell");

      // A swing at the NPC connects with nothing (IsAttackTarget is False for TNormNpc).
      var swing = world.attack(playerId, new Position(6, 5), Direction.RIGHT, AttackKind.HIT);
      world.tickOnce();
      AttackResult swingResult = swing.join();
      assertTrue(swingResult.accepted(), "the swing itself must be accepted");
      assertTrue(swingResult.hitNothing(), "an NPC can never be a melee victim");
      WorldObjectSnapshot npcAfter = runTick(world, world.snapshot(npc.id()));
      assertTrue(npcAfter.alive(), "an NPC can never be damaged");
      assertEquals("老兵", npcAfter.name());

      // Names resolve through the CretInNearXY 3x3 window: on the quoted cell yes, two
      // cells off (the client's stale belief) the answer is a ghost.
      var named = world.queryUserName(playerId, npc.id(), 7, 5);
      world.tickOnce();
      WorldEngine.UserNameQuery answer = named.join();
      assertTrue(answer.present());
      assertEquals("老兵", answer.name());
      assertEquals(255, answer.nameColor());
      var ghosted = world.queryUserName(playerId, npc.id(), 9, 5);
      world.tickOnce();
      assertFalse(ghosted.join().present());
    }
  }

  @Test
  void tickSerializesLifecycleCollisionMovementAndVisibilityEvents() {
    GameMap map = GameMap.withBlockedCells(
        "0", "比奇", 30, 30, List.of(new Position(10, 11)));
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 2, 100);
    List<WorldEvent> aliceEvents = new ArrayList<>();
    List<WorldEvent> bobEvents = new ArrayList<>();

    try (WorldEngine world = new WorldEngine(config, List.of(map))) {
      var aliceEntry = world.enterPlayer(
          "Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      assertFalse(aliceEntry.isDone(), "commands must wait for the world tick");
      world.tickOnce();
      WorldObjectSnapshot alice = aliceEntry.join();
      WorldEvent.MapEntered aliceMap = assertInstanceOf(WorldEvent.MapEntered.class, aliceEvents.getFirst());
      assertTrue(aliceMap.visibleObjects().isEmpty());

      var bobEntry = world.enterPlayer(
          "Bob", "0", new Position(11, 10), Direction.LEFT, bobEvents::add);
      world.tickOnce();
      WorldObjectSnapshot bob = bobEntry.join();
      WorldEvent.MapEntered bobMap = assertInstanceOf(WorldEvent.MapEntered.class, bobEvents.getFirst());
      assertEquals(List.of(alice), bobMap.visibleObjects());
      assertEquals(bob, assertInstanceOf(
          WorldEvent.ObjectAppeared.class, aliceEvents.getLast()).object());

      aliceEvents.clear();
      MoveResult occupied = runTick(world,
          world.move(alice.id(), new Position(11, 10), Direction.RIGHT, MovementKind.WALK));
      assertFalse(occupied.moved());
      assertEquals(WorldEvent.MoveRejection.OCCUPIED, occupied.rejection());
      assertEquals(WorldEvent.MoveRejection.OCCUPIED,
          assertInstanceOf(WorldEvent.MoveRejected.class, aliceEvents.getFirst()).reason());

      aliceEvents.clear();
      bobEvents.clear();
      MoveResult bobMoved = runTick(world,
          world.move(bob.id(), new Position(12, 10), Direction.RIGHT, MovementKind.WALK));
      assertTrue(bobMoved.moved());
      assertInstanceOf(WorldEvent.MoveAccepted.class, bobEvents.getFirst());
      WorldEvent.ObjectMoved observedMove = assertInstanceOf(
          WorldEvent.ObjectMoved.class, aliceEvents.getFirst());
      assertEquals(new Position(11, 10), observedMove.from());
      assertEquals(new Position(12, 10), observedMove.object().position());

      aliceEvents.clear();
      MoveResult terrain = runTick(world,
          world.move(alice.id(), new Position(10, 11), Direction.DOWN, MovementKind.WALK));
      assertEquals(WorldEvent.MoveRejection.BLOCKED_TERRAIN, terrain.rejection());

      aliceEvents.clear();
      bobEvents.clear();
      MoveResult ranAway = runTick(world,
          world.move(alice.id(), new Position(8, 10), Direction.LEFT, MovementKind.RUN));
      assertTrue(ranAway.moved());
      assertInstanceOf(WorldEvent.MoveAccepted.class, aliceEvents.get(0));
      assertEquals(bob.id(), assertInstanceOf(
          WorldEvent.ObjectDisappeared.class, aliceEvents.get(1)).objectId());
      assertEquals(alice.id(), assertInstanceOf(
          WorldEvent.ObjectDisappeared.class, bobEvents.getFirst()).objectId());

      aliceEvents.clear();
      boolean staleTurn = runTick(world,
          world.turn(alice.id(), new Position(10, 10), Direction.UP));
      assertFalse(staleTurn);
      assertInstanceOf(WorldEvent.TurnRejected.class, aliceEvents.getFirst());

      bobEvents.clear();
      runTick(world, world.leavePlayer(alice.id()));
      assertEquals(0, map.objectAt(new Position(8, 10)));
      assertEquals(1, runTick(world, world.onlinePlayers()));
    }
  }

  @Test
  void startedEngineOwnsAllMutationAndCallbacksOnOneThread() throws Exception {
    AtomicReference<String> callbackThread = new AtomicReference<>();
    List<WorldEvent> events = new CopyOnWriteArrayList<>();
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 16, 16)))) {
      world.start();
      WorldObjectSnapshot player = world.enterPlayer(
              "ThreadCheck", "0", new Position(5, 5), Direction.DOWN,
              event -> {
                callbackThread.set(Thread.currentThread().getName());
                events.add(event);
              })
          .get(2, TimeUnit.SECONDS);
      assertEquals("ThreadCheck", player.name());
      assertTrue(callbackThread.get().startsWith("mir2-world"));
      assertFalse(events.isEmpty());
      // The command future completes inside the tick, so the counter is bumped just afterwards.
      assertTrue(awaitTicks(world), "the scheduled world thread must keep ticking");
      assertTrue(world.isRunning());
    }
  }

  private static boolean awaitTicks(WorldEngine world) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (world.tickCount() > 0) return true;
      Thread.sleep(5);
    }
    return false;
  }

  private static <T> T runTick(WorldEngine world, java.util.concurrent.CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
