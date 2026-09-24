package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameGroupProtocolTest {

  @Test
  void groupModeAndGroupLifecyclePacketsRoundTrip() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      List<GameOutbound> outputA = new ArrayList<>();
      List<GameOutbound> outputB = new ArrayList<>();
      var a = world.enterPlayer("LeaderA", "0", new Position(5, 5), Direction.DOWN,
          new GameProtocolAdapter(world, outputA::add, () -> 100));
      var b = world.enterPlayer("MemberB", "0", new Position(6, 5), Direction.DOWN,
          new GameProtocolAdapter(world, outputB::add, () -> 100));
      world.tickOnce();
      int idA = a.join().id();
      int idB = b.join().id();
      GameProtocolAdapter adapterA = new GameProtocolAdapter(world, idA, outputA::add, () -> 100);
      GameProtocolAdapter adapterB = new GameProtocolAdapter(world, idB, outputB::add, () -> 100);
      outputA.clear();
      outputB.clear();

      // 1. CM_GROUPMODE (1019) param=1 -> SM_GROUPMODECHANGED (659) param=1
      WirePacket modePacket = new WirePacket(
          new DefaultMessage(0, ProtocolConstants.CM_GROUPMODE, 1, 0, 0), "");
      assertTrue(adapterA.handle(modePacket));
      world.tickOnce();
      WirePacket modeReply = ((GameOutbound.Packet) outputA.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_GROUPMODECHANGED, modeReply.message().ident());
      assertEquals(1, modeReply.message().param());

      // 2. CM_CREATEGROUP (1020) body="MemberB" -> SM_CREATEGROUP_OK (660) + SM_GROUPMEMBERS (667)
      WirePacket createPacket = new WirePacket(
          new DefaultMessage(0, ProtocolConstants.CM_CREATEGROUP, 0, 0, 0),
          WireMessageCodec.encodeBody("MemberB"));
      assertTrue(adapterA.handle(createPacket));
      world.tickOnce();

      // Output A should have SM_CREATEGROUP_OK and SM_GROUPMEMBERS
      List<WirePacket> packetsA = outputA.stream()
          .filter(GameOutbound.Packet.class::isInstance)
          .map(GameOutbound.Packet.class::cast)
          .map(GameOutbound.Packet::packet)
          .toList();
      assertTrue(packetsA.stream().anyMatch(p -> p.message().ident() == ProtocolConstants.SM_CREATEGROUP_OK));
      WirePacket membersA = packetsA.stream()
          .filter(p -> p.message().ident() == ProtocolConstants.SM_GROUPMEMBERS)
          .findFirst().orElseThrow();
      assertEquals("LeaderA/MemberB/", WireMessageCodec.decodeBody(membersA.encodedBody()));

      // Output B should also receive SM_GROUPMEMBERS
      List<WirePacket> packetsB = outputB.stream()
          .filter(GameOutbound.Packet.class::isInstance)
          .map(GameOutbound.Packet.class::cast)
          .map(GameOutbound.Packet::packet)
          .toList();
      WirePacket membersB = packetsB.stream()
          .filter(p -> p.message().ident() == ProtocolConstants.SM_GROUPMEMBERS)
          .findFirst().orElseThrow();
      assertEquals("LeaderA/MemberB/", WireMessageCodec.decodeBody(membersB.encodedBody()));

      // 3. CM_DELGROUPMEMBER (1022) body="MemberB" -> SM_GROUPDELMEM_OK (663) + SM_GROUPCANCEL (666)
      outputA.clear();
      outputB.clear();
      WirePacket delPacket = new WirePacket(
          new DefaultMessage(0, ProtocolConstants.CM_DELGROUPMEMBER, 0, 0, 0),
          WireMessageCodec.encodeBody("MemberB"));
      assertTrue(adapterA.handle(delPacket));
      world.tickOnce();

      List<WirePacket> delPacketsA = outputA.stream()
          .filter(GameOutbound.Packet.class::isInstance)
          .map(GameOutbound.Packet.class::cast)
          .map(GameOutbound.Packet::packet)
          .toList();
      assertTrue(delPacketsA.stream().anyMatch(p -> p.message().ident() == ProtocolConstants.SM_GROUPDELMEM_OK));
      assertTrue(delPacketsA.stream().anyMatch(p -> p.message().ident() == ProtocolConstants.SM_GROUPCANCEL));

      List<WirePacket> delPacketsB = outputB.stream()
          .filter(GameOutbound.Packet.class::isInstance)
          .map(GameOutbound.Packet.class::cast)
          .map(GameOutbound.Packet::packet)
          .toList();
      assertTrue(delPacketsB.stream().anyMatch(p -> p.message().ident() == ProtocolConstants.SM_GROUPCANCEL));
    }
  }
}
