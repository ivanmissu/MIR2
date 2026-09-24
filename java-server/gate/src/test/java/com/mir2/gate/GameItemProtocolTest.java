package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.Equipment;
import com.mir2.world.EquipmentSlot;
import com.mir2.world.GameMap;
import com.mir2.world.Position;
import com.mir2.world.StdItem;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W12 wire mapping: CM_TAKEONITEM/CM_TAKEOFFITEM/CM_EAT/CM_DROPITEM and their replies. */
class GameItemProtocolTest {

  private static StdItem cloth() {
    return new StdItem("布衣(男)", 10, 1, 5, 0, 0, 0, 100, 1000,
        StdItem.packedRange(1, 4), 0, 0, 0, 0, 0, 0, 100);
  }

  private static BackpackItem worn(StdItem template, int makeIndex) {
    return BackpackItem.of(template, makeIndex);
  }

  @Test
  void takeOnAndTakeOffPacketsCarryTheSlotInParamAndMakeIndexInRecog() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)))) {
      List<GameOutbound> output = new ArrayList<>();
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.DOWN,
          new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      int playerId = entered.join().id();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 7);
      output.clear();

      // ClMain.pas:3058 SendTakeOnItem -> MakeDefaultMsg(CM_TAKEONITEM, itmindex, where, 0, 0)
      WirePacket takeOn = new WirePacket(
          new DefaultMessage(42, ProtocolConstants.CM_TAKEONITEM, EquipmentSlot.DRESS.index(), 0, 0),
          WireMessageCodec.encodeBody("布衣(男)"));
      assertTrue(adapter.handle(takeOn), "CM_TAKEONITEM must be a supported ident");
      world.tickOnce();

      // The bag is empty, so the world refuses and the adapter answers SM_TAKEON_FAIL(-1).
      WirePacket fail = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_TAKEON_FAIL, fail.message().ident());
      assertEquals(-1, fail.message().recog());

      output.clear();
      WirePacket takeOff = new WirePacket(
          new DefaultMessage(42, ProtocolConstants.CM_TAKEOFFITEM, EquipmentSlot.DRESS.index(), 0, 0),
          WireMessageCodec.encodeBody("布衣(男)"));
      assertTrue(adapter.handle(takeOff));
      world.tickOnce();
      WirePacket offFail = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_TAKEOFF_FAIL, offFail.message().ident());
      assertEquals(-2, offFail.message().recog(), "an empty slot reports the Delphi n10 = -2");
    }
  }

  @Test
  void successfulTakeOffAlsoEmitsTheDelphiTrailingFailurePacket() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);

    adapter.send(new WorldEvent.ItemUnequipped(
        1, EquipmentSlot.DRESS, worn(cloth(), 42), 0x02000000, 0));

    // SM_TAKEOFF_OK carries the new Feature in recog and FeatureEx in param.
    WirePacket ok = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_TAKEOFF_OK, ok.message().ident());
    assertEquals(0x02000000, ok.message().recog());

    // Quirk: ClientTakeOffItems leaves n10 = 0 on success and its exit test is "n10 <= 0",
    // so a SM_TAKEOFF_FAIL with recog 0 always trails a successful take-off.
    WirePacket trailing = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_TAKEOFF_FAIL, trailing.message().ident());
    assertEquals(0, trailing.message().recog());

    // The item reappears in the bag through SM_ADDITEM.
    WirePacket added = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_ADDITEM, added.message().ident());
    assertEquals("布衣(男)", ClientItemCodec.decode(added.encodedBody()).name());
  }

  @Test
  void eatAndDropRepliesMatchTheDelphiIdentsAndBodies() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);

    adapter.send(new WorldEvent.ItemUsed(1, worn(potion(), 9), 30, 0));
    assertEquals(ProtocolConstants.SM_EAT_OK,
        ((GameOutbound.Packet) output.removeFirst()).packet().message().ident());

    adapter.send(new WorldEvent.UseItemRejected(1, WorldEvent.UseItemRejection.NO_SUCH_ITEM));
    assertEquals(ProtocolConstants.SM_EAT_FAIL,
        ((GameOutbound.Packet) output.removeFirst()).packet().message().ident());

    // SM_DROPITEM_SUCCESS: recog = MakeIndex, body = item name.
    adapter.send(new WorldEvent.ItemDropped(1, worn(cloth(), 42),
        new com.mir2.world.GroundItem(77, "布衣(男)", 100, "0", new Position(5, 5))));
    WirePacket dropped = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_DROPITEM_SUCCESS, dropped.message().ident());
    assertEquals(42, dropped.message().recog());
    assertEquals("布衣(男)", WireMessageCodec.decodeBody(dropped.encodedBody()));

    adapter.send(new WorldEvent.DropItemRejected(
        1, "布衣(男)", 42, WorldEvent.DropRejection.SAFE_ZONE));
    WirePacket refused = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_DROPITEM_FAIL, refused.message().ident());
    assertEquals(42, refused.message().recog());
    assertEquals("布衣(男)", WireMessageCodec.decodeBody(refused.encodedBody()));

    // SM_WEIGHTCHANGED: recog/param/tag = Weight/WearWeight/HandWeight (ObjBase.pas:5766).
    adapter.send(new WorldEvent.WeightChanged(1, 11, 5, 2));
    WirePacket weight = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_WEIGHTCHANGED, weight.message().ident());
    assertEquals(11, weight.message().recog());
    assertEquals(5, weight.message().param());
    assertEquals(2, weight.message().tag());
  }

  @Test
  void wornSetIsSentAsSlotSeparatedClientItemsAndSkippedWhenEmpty() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);

    // ObjBase.pas:16930 sends nothing at all when no slot is filled.
    adapter.send(new WorldEvent.EquipmentSent(1, Equipment.empty()));
    assertTrue(output.isEmpty(), "an empty worn set produces no SM_SENDUSEITEMS");

    Equipment equipment = Equipment.empty()
        .with(EquipmentSlot.DRESS, worn(cloth(), 42))
        .with(EquipmentSlot.WEAPON, worn(com.mir2.world.StdItems.woodenSword(), 43));
    adapter.send(new WorldEvent.EquipmentSent(1, equipment));

    WirePacket sent = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_SENDUSEITEMS, sent.message().ident());
    String body = sent.encodedBody();
    // Body layout: IntToStr(slot) + '/' + EncodeBuffer(TClientItem) + '/' per occupied slot.
    String[] parts = body.split("/");
    assertEquals(4, parts.length, "two slots produce four '/'-separated fields");
    assertEquals(String.valueOf(EquipmentSlot.DRESS.index()), parts[0]);
    assertEquals("布衣(男)", ClientItemCodec.decode(parts[1]).name());
    assertEquals(String.valueOf(EquipmentSlot.WEAPON.index()), parts[2]);
    assertEquals("木剑", ClientItemCodec.decode(parts[3]).name());
  }

  @Test
  void cannotTakeOffRejectionEmitsRedSysMessageAndTakeOffFail() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);

    adapter.send(new WorldEvent.UnequipRejected(1, -4, WorldEvent.UnequipRejection.CANNOT_TAKE_OFF));
    assertEquals(2, output.size());

    WirePacket sysMsg = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_SYSMESSAGE, sysMsg.message().ident());
    assertEquals("无法取下物品", WireMessageCodec.decodeBody(sysMsg.encodedBody()));

    WirePacket failMsg = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_TAKEOFF_FAIL, failMsg.message().ident());
    assertEquals(-4, failMsg.message().recog());
  }

  @Test
  void unsupportedItemIdentsAreStillRejected() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);
    assertFalse(adapter.handle(new WirePacket(new DefaultMessage(0, 0xfffe, 0, 0, 0))));
  }

  private static StdItem potion() {
    return com.mir2.world.StdItems.smallHealingPotion();
  }

  /** Adapter bound to player id 1 without a live world, for pure event-to-packet checks. */
  private static GameProtocolAdapter adapterFor(List<GameOutbound> output) {
    WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)));
    return new GameProtocolAdapter(world, 1, output::add, () -> 7);
  }
}
