package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.MovementKind;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import com.mir2.world.WorldObjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameProtocolAdapterTest {
  @Test
  void clientWalkRunAndTurnAreSubmittedAndAcknowledged() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.DOWN,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();

      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 1234);
      sink.set(adapter);

      assertTrue(adapter.handle(action(ProtocolConstants.CM_WALK, 6, 5, Direction.RIGHT)));
      world.tickOnce();
      assertEquals(new Position(6, 5), worldSnapshot(world, playerId).position());
      assertEquals(new GameOutbound.Status(true, 1234), output.removeFirst());

      assertTrue(adapter.handle(action(ProtocolConstants.CM_RUN, 8, 5, Direction.RIGHT)));
      world.tickOnce();
      assertEquals(new Position(8, 5), worldSnapshot(world, playerId).position());
      assertEquals(new GameOutbound.Status(true, 1234), output.removeFirst());

      assertTrue(adapter.handle(action(ProtocolConstants.CM_TURN, 8, 5, Direction.UP)));
      world.tickOnce();
      assertEquals(Direction.UP, worldSnapshot(world, playerId).direction());
      assertEquals(new GameOutbound.Status(true, 1234), output.removeFirst());
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void staleCoordinatesAndInvalidDirectionsReleaseClientActionLockWithFail() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("warrior", "0", new Position(5, 5), Direction.DOWN,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 99);
      sink.set(adapter);

      adapter.handle(action(ProtocolConstants.CM_WALK, 9, 5, Direction.RIGHT));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(false, 99), output.removeFirst());
      assertEquals(new Position(5, 5), worldSnapshot(world, playerId).position());

      WirePacket invalidDirection = new WirePacket(new DefaultMessage(pack(5, 4),
          ProtocolConstants.CM_WALK, 0, 8, 0));
      assertTrue(adapter.handle(invalidDirection));
      assertEquals(new GameOutbound.Status(false, 99), output.removeFirst());

      assertFalse(adapter.handle(new WirePacket(new DefaultMessage(0, ProtocolConstants.CM_SAY, 0, 0, 0))));
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void mapEntryEmitsNewMapLogonDescriptionAndVisibleObjects() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "比奇省", 20, 20)))) {
      List<GameOutbound> output = new ArrayList<>();
      var first = world.enterPlayer("first", "0", new Position(5, 5), Direction.DOWN,
          0x11223344, 0x55667788, ignored -> {});
      world.tickOnce();
      assertEquals(1, first.join().id());

      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add);
      var second = world.enterPlayerNear("second", "0", new Position(5, 5), Direction.UP,
          0x01020304, 0x05060708, adapter);
      world.tickOnce();
      WorldObjectSnapshot player = second.join();

      WirePacket newMap = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_NEWMAP, newMap.message().ident());
      assertEquals(player.position().x(), newMap.message().param());
      assertEquals(player.position().y(), newMap.message().tag());
      assertEquals("0", WireMessageCodec.decodeBody(newMap.encodedBody()));

      WirePacket logon = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_LOGON, logon.message().ident());
      byte[] logonBody = SixBitCodec.decodeString(logon.encodedBody());
      assertEquals(16, logonBody.length);
      assertEquals(0x01020304, littleEndianInt(logonBody, 0));
      assertEquals(0x05060708, littleEndianInt(logonBody, 4));

      WirePacket description = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_MAPDESCRIPTION, description.message().ident());
      assertEquals(-1, description.message().recog());
      assertEquals("比奇省", WireMessageCodec.decodeBody(description.encodedBody()));

      WirePacket appeared = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_TURN, appeared.message().ident());
      assertEquals(first.join().id(), appeared.message().recog());
      assertEquals(new CharacterDescription(0x11223344, 0x55667788),
          CharacterDescription.decode(appeared.encodedBody()));
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void observerEventsBecomeLegacyMovementPackets() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 1);
      WorldObjectSnapshot other = new WorldObjectSnapshot(7, "other", WorldObjectType.PLAYER,
          "0", new Position(11, 12), Direction.DOWN_LEFT);

      adapter.send(new WorldEvent.ObjectMoved(other, new Position(10, 11), MovementKind.RUN));
      WirePacket run = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_RUN, run.message().ident());
      assertEquals(7, run.message().recog());
      assertEquals(11, run.message().param());
      assertEquals(12, run.message().tag());
      assertEquals(Direction.DOWN_LEFT.code(), run.message().series());
      assertArrayEquals(new byte[8], SixBitCodec.decodeString(run.encodedBody()));

      adapter.send(new WorldEvent.ObjectTurned(other));
      assertEquals(ProtocolConstants.SM_TURN,
          ((GameOutbound.Packet) output.removeFirst()).packet().message().ident());

      adapter.send(new WorldEvent.ObjectDisappeared(7));
      WirePacket disappeared = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_DISAPPEAR, disappeared.message().ident());
      assertEquals(7, disappeared.message().recog());
    }
  }

  @Test
  void clientOpenDoorUsesParamTagAndDoesNotProduceAnActionAck() {
    GameMap map = GameMap.withDoors("0", "PoC", 20, 20,
        List.of(new GameMap.DoorDefinition(new Position(7, 8), 1, 0)));
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("door-user", "0", new Position(5, 5), Direction.RIGHT,
          event -> sink.get().send(event));
      world.tickOnce();
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, entered.join().id(), output::add, () -> 1);
      sink.set(adapter);

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(12345, ProtocolConstants.CM_OPENDOOR,
          7, 8, 0))));
      world.tickOnce();
      WirePacket opened = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_OPENDOOR_OK, opened.message().ident());
      assertEquals(7, opened.message().param());
      assertEquals(8, opened.message().tag());
      assertTrue(output.isEmpty(), "CM_OPENDOOR has no +GOOD/+FAIL packet");
    }
  }

  @Test
  void doorAndSameServerMapChangeEventsUseTheLegacyDoorAndChangeMapPackets() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 7, output::add, () -> 1);

      adapter.send(new WorldEvent.DoorOpened("0", new Position(10, 11)));
      WirePacket opened = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_OPENDOOR_OK, opened.message().ident());
      assertEquals(0, opened.message().recog());
      assertEquals(10, opened.message().param());
      assertEquals(11, opened.message().tag());

      adapter.send(new WorldEvent.DoorClosed("0", new Position(10, 11)));
      WirePacket closed = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CLOSEDOOR, closed.message().ident());
      assertEquals(10, closed.message().param());
      assertEquals(11, closed.message().tag());

      WorldObjectSnapshot self = new WorldObjectSnapshot(7, "traveler", WorldObjectType.PLAYER,
          "1", new Position(3, 4), Direction.DOWN);
      adapter.send(new WorldEvent.MapChanged(self, new GameMap.MapInfo("1", "矿洞", 20, 20),
          List.of(), List.of()));
      assertEquals(ProtocolConstants.SM_CLEAROBJECTS,
          ((GameOutbound.Packet) output.removeFirst()).packet().message().ident());
      WirePacket changed = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CHANGEMAP, changed.message().ident());
      assertEquals(7, changed.message().recog());
      assertEquals(3, changed.message().param());
      assertEquals(4, changed.message().tag());
      assertEquals("1", WireMessageCodec.decodeBody(changed.encodedBody()));
      assertEquals(ProtocolConstants.SM_MAPDESCRIPTION,
          ((GameOutbound.Packet) output.removeFirst()).packet().message().ident());
      assertTrue(output.isEmpty());
    }
  }

  private static WirePacket action(int ident, int x, int y, Direction direction) {
    return new WirePacket(new DefaultMessage(pack(x, y), ident, 0, direction.code(), 0));
  }

  private static int pack(int x, int y) {
    return (y << 16) | x;
  }

  private static int littleEndianInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff)
        | ((bytes[offset + 1] & 0xff) << 8)
        | ((bytes[offset + 2] & 0xff) << 16)
        | (bytes[offset + 3] << 24);
  }

  private static WorldObjectSnapshot worldSnapshot(WorldEngine world, int playerId) {
    var snapshot = world.snapshot(playerId);
    world.tickOnce();
    return snapshot.join();
  }
}
