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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W10 doors (Envir.pas GetDoor / UsrEngn.OpenDoor / ProcessMapDoor) and MapInfo connection
 * points (AddMapRoute + TBaseObject.Walk → EnterAnotherMap).
 */
class WorldDoorAndTeleportTest {
  private final AtomicLong now = new AtomicLong(1_000_000);

  @Test
  void openDoorBroadcastsOnlyToPlayersInsideTheTwelveCellSquare() {
    List<WorldEvent> nearEvents = new ArrayList<>();
    List<WorldEvent> farEvents = new ArrayList<>();
    GameMap map = GameMap.empty("0", "PoC", 40, 40);
    map.addDoor(DoorInfo.create(new Position(10, 10), 3, List.of()));
    try (WorldEngine world = engine(map)) {
      WorldObjectSnapshot near = run(world,
          world.enterPlayer("门旁", "0", new Position(11, 11), Direction.UP, nearEvents::add));
      run(world, world.enterPlayer("远处", "0", new Position(33, 10), Direction.UP, farEvents::add));
      nearEvents.clear();
      farEvents.clear();

      assertTrue(run(world, world.openDoor(near.id(), new Position(10, 10))));

      assertEquals(1, eventsOf(nearEvents, WorldEvent.DoorOpened.class).size());
      assertTrue(farEvents.stream().noneMatch(event -> event instanceof WorldEvent.DoorOpened));
      assertTrue(map.doorAt(new Position(10, 10)).status().opened());
    }
  }

  @Test
  void reopenedAndUnknownDoorRequestsStaySilent() {
    List<WorldEvent> events = new ArrayList<>();
    GameMap map = GameMap.empty("0", "PoC", 40, 40);
    map.addDoor(DoorInfo.create(new Position(10, 10), 1, List.of()));
    try (WorldEngine world = engine(map)) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("玩家", "0", new Position(10, 12), Direction.UP, events::add));
      events.clear();

      // First open succeeds; a second request must not re-broadcast (Delphi guards on
      // not Door.Status.boOpened).
      assertTrue(run(world, world.openDoor(player.id(), new Position(10, 10))));
      events.clear();
      assertFalse(run(world, world.openDoor(player.id(), new Position(10, 10))));
      // A cell without a door anchor is silently refused too (GetDoor returns nil).
      assertFalse(run(world, world.openDoor(player.id(), new Position(12, 10))));
      assertEquals(0, eventsOf(events, WorldEvent.DoorOpened.class).size());
    }
  }

  @Test
  void linkedDoorAnchorsShareOneStatusAndCloseTogetherOnTheFiveSecondSweep() {
    // (19,15) is within Delphi's ±10 Chebyshev sharing radius of (10,10), same index → one
    // TDoorStatus drives every linked anchor.
    DoorInfo firstAnchor = DoorInfo.create(new Position(10, 10), 2, List.of());
    DoorInfo secondAnchor = DoorInfo.create(new Position(19, 15), 2, List.of(firstAnchor));
    assertSame(firstAnchor.status(), secondAnchor.status());
    GameMap map = GameMap.empty("0", "PoC", 40, 40);
    map.addDoor(firstAnchor);
    map.addDoor(secondAnchor);
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(map)) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("盟约", "0", new Position(10, 11), Direction.UP, events::add));
      events.clear();
      run(world, world.openDoor(player.id(), new Position(10, 10)));
      assertTrue(firstAnchor.status().opened());
      assertTrue(secondAnchor.status().opened(), "the shared status opens every linked anchor");

      // At +4800ms the 500ms sweep runs but 5 seconds have not passed → still open.
      advance(4_800);
      world.tickOnce();
      assertTrue(firstAnchor.status().opened());
      // +600ms more: another sweep, now past the 5000ms close timeout → closed and broadcast.
      advance(600);
      world.tickOnce();
      assertFalse(firstAnchor.status().opened());
      assertTrue(eventsOf(events, WorldEvent.DoorClosed.class).size() >= 1);
      assertTrue(map.doors().stream().allMatch(door -> !door.status().opened()));
    }
  }

  @Test
  void walkingOntoAGateTeleportsBetweenMapsWithFullEventFanout() {
    List<WorldEvent> moverEvents = new ArrayList<>();
    List<WorldEvent> originObserverEvents = new ArrayList<>();
    List<WorldEvent> destinationObserverEvents = new ArrayList<>();
    GameMap origin = GameMap.empty("0", "比奇省", 40, 40);
    GameMap destination = GameMap.empty("1", "盟重省", 40, 40);
    try (WorldEngine world = engine(origin, destination)) {
      assertTrue(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "1", new Position(3, 3)))));
      WorldObjectSnapshot mover = run(world,
          world.enterPlayer("过门人", "0", new Position(5, 6), Direction.UP, moverEvents::add));
      run(world, world.enterPlayer("原地目击者", "0", new Position(8, 8), Direction.UP,
          originObserverEvents::add));
      WorldObjectSnapshot destinationObserver = run(world,
          world.enterPlayer("过门目击者", "1", new Position(3, 5), Direction.UP,
              destinationObserverEvents::add));
      moverEvents.clear();
      originObserverEvents.clear();
      destinationObserverEvents.clear();

      MoveResult result = run(world, world.move(mover.id(), new Position(5, 5), Direction.UP,
          MovementKind.WALK));

      assertTrue(result.moved());
      WorldObjectSnapshot teleported = run(world, world.snapshot(mover.id()));
      assertEquals("1", teleported.mapId());
      assertEquals(new Position(3, 3), teleported.position());
      assertTrue(moverEvents.stream().anyMatch(event -> event instanceof WorldEvent.MoveAccepted),
          "Delphi acknowledges the walk before the change-map sequence");
      assertTrue(moverEvents.stream().anyMatch(event -> event instanceof WorldEvent.PlayerMapChanged));
      assertTrue(moverEvents.stream().noneMatch(event -> event instanceof WorldEvent.MoveRejected),
          "a successful gate crossing never rejects the move");
      // The mover rebuilds its whole scene on the new map: the destination observer appears.
      assertTrue(moverEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectAppeared appeared
              && appeared.object().id() == destinationObserver.id()));
      // The origin observer watches the player vanish; the destination observer sees it arrive.
      assertTrue(originObserverEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectDisappeared disappeared
              && disappeared.objectId() == mover.id()));
      assertTrue(destinationObserverEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectAppeared appeared
              && appeared.object().id() == mover.id()));
      assertTrue(originObserverEvents.stream().noneMatch(event -> event instanceof WorldEvent.ObjectMoved),
          "the gate jump is a disappear, never a walk broadcast");
      assertEquals(mover.id(), destination.objectAt(new Position(3, 3)));
      assertEquals(0, origin.objectAt(new Position(5, 6)));
    }
  }

  @Test
  void runningOntoAGateTeleportsToo() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 40, 40),
        GameMap.empty("1", "PoC2", 40, 40))) {
      assertTrue(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "1", new Position(3, 3)))));
      WorldObjectSnapshot runner = run(world,
          world.enterPlayer("跑者", "0", new Position(5, 7), Direction.UP, event -> {}));

      MoveResult result = run(world, world.move(runner.id(), new Position(5, 5), Direction.UP,
          MovementKind.RUN));

      assertTrue(result.moved());
      WorldObjectSnapshot teleported = run(world, world.snapshot(runner.id()));
      assertEquals("1", teleported.mapId());
      assertEquals(new Position(3, 3), teleported.position());
    }
  }

  @Test
  void closedDoorNextToAGateKeepsTheWalkLocalUntilOpened() {
    List<WorldEvent> events = new ArrayList<>();
    GameMap origin = GameMap.empty("0", "PoC", 40, 40);
    // A door anchor sits adjacent to the gate cell, like a house doorway covering a route.
    origin.addDoor(DoorInfo.create(new Position(6, 5), 1, List.of()));
    try (WorldEngine world = engine(origin, GameMap.empty("1", "PoC2", 40, 40))) {
      assertTrue(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "1", new Position(3, 3)))));
      WorldObjectSnapshot walker = run(world,
          world.enterPlayer("挡路人", "0", new Position(5, 6), Direction.UP, events::add));
      events.clear();

      MoveResult blockedWalk = run(world, world.move(walker.id(), new Position(5, 5),
          Direction.UP, MovementKind.WALK));
      assertTrue(blockedWalk.moved(), "ArroundDoorOpened=false swallows the gate, the walk stands");
      assertEquals("0", blockedWalk.player().mapId());
      assertEquals(new Position(5, 5), blockedWalk.player().position());
      assertTrue(events.stream().noneMatch(event -> event instanceof WorldEvent.PlayerMapChanged));

      // Open the door, step off the gate and back on: this time the crossing fires.
      assertTrue(run(world, world.openDoor(walker.id(), new Position(6, 5))));
      MoveResult south = run(world, world.move(walker.id(), new Position(5, 6),
          Direction.DOWN, MovementKind.WALK));
      assertTrue(south.moved());
      MoveResult freedWalk = run(world, world.move(walker.id(), new Position(5, 5),
          Direction.UP, MovementKind.WALK));
      assertTrue(freedWalk.moved());
      assertEquals("1", freedWalk.player().mapId());
    }
  }

  @Test
  void unwalkableGateDestinationRollsTheWholeMoveBack() {
    List<WorldEvent> events = new ArrayList<>();
    GameMap origin = GameMap.empty("0", "PoC", 40, 40);
    // Target (3,3) is blocked terrain on the destination map.
    GameMap destination = GameMap.withBlockedCells("1", "PoC2", 40, 40, List.of(new Position(3, 3)));
    try (WorldEngine world = engine(origin, destination)) {
      assertTrue(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "1", new Position(3, 3)))));
      WorldObjectSnapshot walker = run(world,
          world.enterPlayer("撞墙人", "0", new Position(5, 6), Direction.UP, events::add));
      events.clear();

      MoveResult result = run(world, world.move(walker.id(), new Position(5, 5), Direction.UP,
          MovementKind.WALK));

      assertFalse(result.moved());
      assertEquals(WorldEvent.MoveRejection.GATE_TARGET_UNPASSABLE,
          result.rejectionReason().orElseThrow());
      WorldObjectSnapshot stayed = run(world, world.snapshot(walker.id()));
      assertEquals("0", stayed.mapId());
      assertEquals(new Position(5, 6), stayed.position(),
          "EnterAnotherMap failure must roll the walk back to its source");
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.MoveRejected));
      assertTrue(events.stream().noneMatch(event -> event instanceof WorldEvent.MoveAccepted));
      assertTrue(events.stream().noneMatch(event -> event instanceof WorldEvent.PlayerMapChanged));
      assertTrue(events.stream().noneMatch(event -> event instanceof WorldEvent.ObjectMoved));
      // Occupancy never moved: the source cell still holds the player, the dest cell is empty.
      assertEquals(walker.id(), origin.objectAt(new Position(5, 6)));
      assertEquals(0, destination.objectAt(new Position(3, 3)));
    }
  }

  @Test
  void routesToUnknownMapsAreDroppedLikeAddMapRouteFailures() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 40, 40))) {
      assertFalse(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "missing", new Position(3, 3)))));
      assertTrue(run(world, world.addRoute(new TeleportRoute("0", new Position(5, 5),
          "0", new Position(3, 3)))));
    }
  }

  private static <T extends WorldEvent> List<T> eventsOf(List<WorldEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private WorldEngine engine(GameMap... maps) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(maps), now::get, new Random(20260920L),
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
