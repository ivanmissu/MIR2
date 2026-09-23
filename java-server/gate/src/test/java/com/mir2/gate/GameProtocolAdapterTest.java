package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Direction;
import com.mir2.world.DoorInfo;
import com.mir2.world.GameMap;
import com.mir2.world.MovementKind;
import com.mir2.world.Position;
import com.mir2.world.TeleportRoute;
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

      // An ident outside the supported gameplay subset is ignored; 65535 is the largest
      // value TDefaultMessage's Word ident field can carry and is unassigned in Grobal2.pas.
      assertFalse(adapter.handle(new WirePacket(new DefaultMessage(0, 0xffff, 0, 0, 0))));
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

      // RM_LOGON order (ObjBase.pas:5618): SM_CHANGELIGHT follows SM_NEWMAP, then SendLogon's
      // SM_LOGON + SM_FEATURECHANGED, then the proactive SM_USERNAME, then SM_MAPDESCRIPTION.
      WirePacket changeLight = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CHANGELIGHT, changeLight.message().ident());
      assertEquals(player.id(), changeLight.message().recog());
      assertEquals(0, changeLight.message().param(), "naked character carries no light");

      WirePacket logon = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_LOGON, logon.message().ident());
      byte[] logonBody = SixBitCodec.decodeString(logon.encodedBody());
      assertEquals(16, logonBody.length);
      assertEquals(0x01020304, littleEndianInt(logonBody, 0));
      assertEquals(0x05060708, littleEndianInt(logonBody, 4));

      WirePacket featureChanged = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_FEATURECHANGED, featureChanged.message().ident());
      assertEquals(player.id(), featureChanged.message().recog());
      assertEquals(0x0304, featureChanged.message().param(), "LoWord of the feature");
      assertEquals(0x0102, featureChanged.message().tag(), "HiWord of the feature");

      WirePacket userName = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_USERNAME, userName.message().ident());
      assertEquals(player.id(), userName.message().recog());
      assertEquals(255, userName.message().param(), "white name palette byte");
      assertEquals("second", WireMessageCodec.decodeBody(userName.encodedBody()));

      WirePacket description = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_MAPDESCRIPTION, description.message().ident());
      assertEquals(-1, description.message().recog());
      assertEquals("比奇省", WireMessageCodec.decodeBody(description.encodedBody()));

      WirePacket appeared = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_TURN, appeared.message().ident());
      assertEquals(first.join().id(), appeared.message().recog());
      assertEquals(new CharacterDescription(0x11223344, 0x55667788),
          CharacterDescription.decode(appeared.encodedBody()));

      WirePacket ability = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_ABILITY, ability.message().ident());
      assertEquals(0, ability.message().recog(), "new players start with zero gold and ability sync is immediate");
      assertEquals(50, SixBitCodec.decodeString(ability.encodedBody()).length);
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
  void openDoorRequestBroadcastsOpenDoorOkAndStaysSilentOnReplies() {
    GameMap map = GameMap.empty("0", "PoC", 40, 40);
    map.addDoor(DoorInfo.create(new Position(10, 10), 1, List.of()));
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 7);
      var entered = world.enterPlayer("勇士", "0", new Position(10, 11), Direction.UP, adapter);
      world.tickOnce();
      entered.join();
      output.clear();

      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(1, ProtocolConstants.CM_OPENDOOR, 10, 10, 0))));
      world.tickOnce();

      WirePacket opened = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_OPENDOOR_OK, opened.message().ident());
      assertEquals(0, opened.message().recog());
      assertEquals(10, opened.message().param());
      assertEquals(10, opened.message().tag());
      assertEquals("", opened.encodedBody());
      assertTrue(output.isEmpty(), "Delphi sends no acknowledgement for CM_OPENDOOR");

      // Already-open and door-less cell requests stay silent too.
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(1, ProtocolConstants.CM_OPENDOOR, 10, 10, 0))));
      world.tickOnce();
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(0, ProtocolConstants.CM_OPENDOOR, 12, 12, 0))));
      world.tickOnce();
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void walkingOntoAGateSendsClearObjectsThenChangeMapThenDescription() {
    GameMap origin = GameMap.empty("0", "比奇省", 40, 40);
    GameMap destination = GameMap.empty("1", "盟重省", 40, 40);
    try (WorldEngine world = new WorldEngine(List.of(origin, destination))) {
      var route = world.addRoute(new TeleportRoute("0", new Position(5, 5), "1",
          new Position(3, 3)));
      world.tickOnce();
      assertTrue(route.join());
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 66);
      var entered = world.enterPlayer("过门", "0", new Position(5, 6), Direction.UP, adapter);
      world.tickOnce();
      int playerId = entered.join().id();
      output.clear();

      assertTrue(adapter.handle(action(ProtocolConstants.CM_WALK, 5, 5, Direction.UP)));
      world.tickOnce();

      assertEquals(new GameOutbound.Status(true, 66), output.removeFirst());
      WirePacket clear = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CLEAROBJECTS, clear.message().ident());
      assertEquals(playerId, clear.message().recog());
      WirePacket change = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CHANGEMAP, change.message().ident());
      assertEquals(playerId, change.message().recog());
      assertEquals(3, change.message().param());
      assertEquals(3, change.message().tag());
      assertEquals("1", WireMessageCodec.decodeBody(change.encodedBody()));
      WirePacket description = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_MAPDESCRIPTION, description.message().ident());
      assertEquals("盟重省", WireMessageCodec.decodeBody(description.encodedBody()));
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void sayAndDayChangingTranslateToProtocolPackets() {
    GameMap map = GameMap.empty("0", "比奇省", 40, 40);
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 88);
      var entered = world.enterPlayer("诗人", "0", new Position(10, 10), Direction.DOWN, adapter);
      world.tickOnce();
      int playerId = entered.join().id();
      output.clear();

      // CM_SAY handling
      WirePacket sayPacket = new WirePacket(
          new DefaultMessage(0, ProtocolConstants.CM_SAY, 0, 0, 0),
          WireMessageCodec.encodeBody("你好世界"));
      assertTrue(adapter.handle(sayPacket));
      world.tickOnce();

      // Adapter receives ChatHeard as SM_HEAR
      assertFalse(output.isEmpty());
      WirePacket hear = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_HEAR, hear.message().ident());
      assertEquals(playerId, hear.message().recog());
      assertEquals("诗人:你好世界", WireMessageCodec.decodeBody(hear.encodedBody()));

      // Direct WorldEvent -> Packet translation
      adapter.send(new WorldEvent.Whisper(playerId, "诗人", 2, "Bob", "密语"));
      WirePacket whisper = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_WHISPER, whisper.message().ident());
      assertEquals(playerId, whisper.message().recog());
      assertEquals("诗人=> 密语", WireMessageCodec.decodeBody(whisper.encodedBody()));

      adapter.send(new WorldEvent.Shout(playerId, "诗人", "千里传音"));
      WirePacket shout = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_HEAR, shout.message().ident());
      assertEquals(playerId, shout.message().recog());
      assertEquals("(!)诗人: 千里传音", WireMessageCodec.decodeBody(shout.encodedBody()));

      adapter.send(new WorldEvent.SystemMessage(playerId, "系统通知"));
      WirePacket sysMsg = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_SYSMESSAGE, sysMsg.message().ident());
      assertEquals("系统通知", WireMessageCodec.decodeBody(sysMsg.encodedBody()));

      adapter.send(new WorldEvent.DayChanging(playerId, 3, 1));
      WirePacket dayChanging = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_DAYCHANGING, dayChanging.message().ident());
      assertEquals(3, dayChanging.message().param());
      assertEquals(1, dayChanging.message().tag());

      adapter.send(new WorldEvent.DoorClosed("0", new Position(12, 12)));
      WirePacket doorClosed = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_CLOSEDOOR, doorClosed.message().ident());
      assertEquals(12, doorClosed.message().param());
      assertEquals(12, doorClosed.message().tag());

      assertTrue(output.isEmpty());
    }
  }

  @Test
  void queryUserNameAnswersSmUsernameNearbyAndSmGhostForStaleCells() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      List<GameOutbound> output = new ArrayList<>();
      var first = world.enterPlayer("first", "0", new Position(5, 5), Direction.DOWN,
          ignored -> {});
      var second = world.enterPlayerNear("second", "0", new Position(5, 5), Direction.UP,
          0, 0, ignored -> {});
      world.tickOnce();
      int askerId = first.join().id();
      WorldObjectSnapshot asked = second.join();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, askerId, output::add, () -> 7);

      // ClMain.pas:3601 SendQueryUserName: recog=target, param/tag=the quoted cell.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(asked.id(),
          ProtocolConstants.CM_QUERYUSERNAME, asked.position().x(), asked.position().y(), 0))));
      world.tickOnce();
      WirePacket named = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_USERNAME, named.message().ident());
      assertEquals(asked.id(), named.message().recog());
      assertEquals(255, named.message().param(), "white palette byte for a clean player");
      assertEquals("second", WireMessageCodec.decodeBody(named.encodedBody()));

      // A cell two tiles away is outside CretInNearXY's 3x3 window: SM_GHOST with the
      // client's quoted cell echoed back (ObjBase.pas:2651).
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(asked.id(),
          ProtocolConstants.CM_QUERYUSERNAME, asked.position().x() + 2, asked.position().y(), 0))));
      world.tickOnce();
      WirePacket ghost = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_GHOST, ghost.message().ident());
      assertEquals(asked.id(), ghost.message().recog());
      assertEquals(asked.position().x() + 2, ghost.message().param());
      assertEquals(asked.position().y(), ghost.message().tag());
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
