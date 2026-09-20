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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-derived coverage for Envir doors and AddMapRoute / EnterAnotherMap. */
class WorldDoorAndRouteTest {
  private final AtomicLong now = new AtomicLong(1_000);

  @Test
  void closedDoorKeepsRouteAsNormalCellThenOpenDoorTransfersAndBroadcastsMapChange() {
    GameMap source = GameMap.withDoors("0", "比奇", 12, 12,
        List.of(new GameMap.DoorDefinition(new Position(2, 2), 7, 0)));
    GameMap destination = GameMap.empty("1", "矿洞", 12, 12);
    MapRoute route = new MapRoute("0", new Position(2, 1), "1", new Position(5, 5));
    List<WorldEvent> travelerEvents = new ArrayList<>();
    List<WorldEvent> sourceObserverEvents = new ArrayList<>();
    List<WorldEvent> destinationObserverEvents = new ArrayList<>();

    try (WorldEngine world = world(List.of(source, destination), List.of(route))) {
      WorldObjectSnapshot traveler = run(world,
          world.enterPlayer("旅行者", "0", new Position(1, 1), Direction.RIGHT, travelerEvents::add));
      run(world, world.enterPlayer("源观察者", "0", new Position(4, 1), Direction.LEFT,
          sourceObserverEvents::add));
      run(world, world.enterPlayer("目标观察者", "1", new Position(6, 5), Direction.LEFT,
          destinationObserverEvents::add));
      travelerEvents.clear();
      sourceObserverEvents.clear();
      destinationObserverEvents.clear();

      // A closed neighboring door prevents gate activation, exactly ArroundDoorOpened's result.
      WorldObjectSnapshot normalMove = run(world,
          world.move(traveler.id(), new Position(2, 1), Direction.RIGHT, MovementKind.WALK)).player();
      assertEquals("0", normalMove.mapId());
      assertTrue(travelerEvents.stream().anyMatch(WorldEvent.MoveAccepted.class::isInstance));

      // Walk back, open the nearby door (CM_OPENDOOR has no distance restriction), then step onto
      // the same gate cell. The transfer sends disappearance/map-change/appearance, not SM_WALK.
      travelerEvents.clear();
      sourceObserverEvents.clear();
      destinationObserverEvents.clear();
      run(world, world.move(traveler.id(), new Position(1, 1), Direction.LEFT, MovementKind.WALK));
      travelerEvents.clear();
      sourceObserverEvents.clear();
      assertTrue(run(world, world.openDoor(traveler.id(), new Position(2, 2))));
      assertTrue(source.doorAt(new Position(2, 2)).orElseThrow().open());
      assertTrue(travelerEvents.stream().anyMatch(WorldEvent.DoorOpened.class::isInstance));

      travelerEvents.clear();
      sourceObserverEvents.clear();
      destinationObserverEvents.clear();
      WorldObjectSnapshot transferred = run(world,
          world.move(traveler.id(), new Position(2, 1), Direction.RIGHT, MovementKind.WALK)).player();
      assertEquals("1", transferred.mapId());
      assertEquals(new Position(5, 5), transferred.position());
      assertInstanceOf(WorldEvent.MapChanged.class, travelerEvents.stream()
          .filter(WorldEvent.MapChanged.class::isInstance).findFirst().orElseThrow());
      assertTrue(sourceObserverEvents.stream().anyMatch(event -> event instanceof WorldEvent.ObjectDisappeared gone
          && gone.objectId() == traveler.id()));
      assertTrue(destinationObserverEvents.stream().anyMatch(event -> event instanceof WorldEvent.ObjectAppeared appeared
          && appeared.object().id() == traveler.id()));
      assertFalse(sourceObserverEvents.stream().anyMatch(WorldEvent.ObjectMoved.class::isInstance),
          "cross-map transfer must not be rendered as a local walk");
    }
  }

  @Test
  void sharedDoorStateBroadcastsOpenAndAutoCloseOnlyAfterFiveSeconds() {
    GameMap map = GameMap.withDoors("0", "比奇", 12, 12, List.of(
        new GameMap.DoorDefinition(new Position(4, 4), 3, 0x10),
        new GameMap.DoorDefinition(new Position(5, 4), 3, 0x80)));
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = world(List.of(map), List.of())) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("开门者", "0", new Position(3, 4), Direction.RIGHT, events::add));
      events.clear();

      assertTrue(run(world, world.openDoor(player.id(), new Position(4, 4))));
      assertTrue(map.doorAt(new Position(5, 4)).orElseThrow().open());
      assertInstanceOf(WorldEvent.DoorOpened.class, events.getFirst());
      assertFalse(run(world, world.openDoor(player.id(), new Position(5, 4))),
          "reopening an already open shared status is silent");

      events.clear();
      now.addAndGet(5_000);
      world.tickOnce();
      assertTrue(map.doorAt(new Position(4, 4)).orElseThrow().open(),
          "Delphi uses a strict greater-than five-second close check");
      assertTrue(events.isEmpty());

      now.incrementAndGet();
      world.tickOnce();
      assertFalse(map.doorAt(new Position(4, 4)).orElseThrow().open());
      assertInstanceOf(WorldEvent.DoorClosed.class, events.getFirst());
    }
  }

  private WorldEngine world(List<GameMap> maps, List<MapRoute> routes) {
    return new WorldEngine(new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000),
        maps, now::get, new Random(7L), PlayerStateStore.none(), ItemDatabase.empty(), routes);
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
