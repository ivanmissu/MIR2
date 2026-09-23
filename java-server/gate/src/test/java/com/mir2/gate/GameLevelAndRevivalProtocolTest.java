package com.mir2.gate;

import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.ItemDatabase;
import com.mir2.world.LevelExperience;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldObjectSnapshot;
import com.mir2.world.WorldObjectType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W14: SM_LEVELUP, SM_ALIVE and SM_DELITEMS on the legacy wire. */
class GameLevelAndRevivalProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void levelUpSendsSmLevelUpWithExperienceAndLevel() {
    List<GameOutbound> output = new ArrayList<>();
    try (WorldEngine world = engine()) {
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 77);
      Ability reached = new Ability(24, 24, 18, 18, 1, 1, 0, 0, 2, 20, 200);
      adapter.send(new WorldEvent.LevelUp(1, 2, 20, reached));

      WirePacket packet = firstPacket(output, ProtocolConstants.SM_LEVELUP);
      // MakeDefaultMsg(SM_LEVELUP, m_Abil.Exp, m_Abil.Level, 0, 0) — ObjBase.pas:5584.
      assertEquals(20, packet.message().recog());
      assertEquals(2, packet.message().param());
      assertEquals(0, packet.message().tag());
      assertEquals(0, packet.message().series());
      assertEquals("", packet.encodedBody());
    }
  }

  @Test
  void levelUpIsNotForwardedForAnotherPlayer() {
    List<GameOutbound> output = new ArrayList<>();
    try (WorldEngine world = engine()) {
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 77);
      adapter.send(new WorldEvent.LevelUp(2, 5, 0, Ability.defaultPlayer()));
      assertTrue(output.isEmpty(), "SM_LEVELUP is a private packet for the levelling player");
    }
  }

  @Test
  void revivalSendsSmAliveWithTheCellAndCharDescBody() {
    List<GameOutbound> output = new ArrayList<>();
    try (WorldEngine world = engine()) {
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 77);
      WorldObjectSnapshot revived = new WorldObjectSnapshot(9, "战士", WorldObjectType.PLAYER,
          "0", new Position(11, 12), Direction.LEFT, 0x01020304, 7, Ability.defaultPlayer());
      adapter.send(new WorldEvent.ObjectRevived(revived));

      WirePacket packet = firstPacket(output, ProtocolConstants.SM_ALIVE);
      // SendRefMsg(RM_ALIVE, m_btDirection, m_nCurrX, m_nCurrY, 0, '') — ObjBase.pas:21199,
      // with the TCharDesc body the RM_ALIVE handler attaches (ObjBase.pas:6066).
      assertEquals(9, packet.message().recog());
      assertEquals(11, packet.message().param());
      assertEquals(12, packet.message().tag());
      assertEquals(Direction.LEFT.code(), packet.message().series());
      byte[] body = SixBitCodec.decode(packet.encodedBody().getBytes(StandardCharsets.ISO_8859_1));
      ByteBuffer desc = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(0x01020304, desc.getInt(), "TCharDesc.Feature");
      assertEquals(7, desc.getInt(), "TCharDesc.Status");
    }
  }

  @Test
  void deathScatterSendsSmDelItemsWithTheNameSlashIndexList() {
    List<GameOutbound> output = new ArrayList<>();
    try (WorldEngine world = engine()) {
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 77);
      BackpackItem venison = BackpackItem.of(StdItems.require("肉"), 41);
      BackpackItem potion = BackpackItem.of(StdItems.smallHealingPotion(), 42);
      adapter.send(new WorldEvent.ItemsRemoved(1, List.of(venison, potion)));

      WirePacket packet = firstPacket(output, ProtocolConstants.SM_DELITEMS);
      // SendDelItemList: series = item count, body = "<name>/<MakeIndex>/" repeated.
      assertEquals(2, packet.message().series());
      assertEquals(venison.name() + "/41/" + potion.name() + "/42/",
          WireMessageCodec.decodeBody(packet.encodedBody()));
    }
  }

  @Test
  void abilityBodyCarriesTheLevelTableMaxExp() {
    // TAbility.MaxExp sits at offset 34 of the packed record, right after Exp.
    Ability level20 = new Ability(224, 224, 81, 81, 3, 4, 0, 2, 20, 1_234,
        LevelExperience.forLevel(20));
    byte[] body = AbilityCodec.bytes(level20);
    ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(20, buffer.getShort(0), "TAbility.Level");
    assertEquals(1_234, buffer.getInt(30), "TAbility.Exp");
    assertEquals(140_000, buffer.getInt(34), "TAbility.MaxExp = GetLevelExp(20)");

    // The 4,000,000,000 plateau is an unsigned DWord on the wire and wraps a signed int.
    Ability capped = new Ability(1, 1, 0, 0, 0, 1, 0, 0, 60, 0, LevelExperience.forLevel(60));
    ByteBuffer plateau = ByteBuffer.wrap(AbilityCodec.bytes(capped)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(4_000_000_000L, Integer.toUnsignedLong(plateau.getInt(34)));
  }

  private WorldEngine engine() {
    return new WorldEngine(
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000),
        List.of(GameMap.empty("0", "PoC", 20, 20)), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }

  private static WirePacket firstPacket(List<GameOutbound> output, int ident) {
    return output.stream()
        .filter(GameOutbound.Packet.class::isInstance)
        .map(outbound -> ((GameOutbound.Packet) outbound).packet())
        .filter(packet -> packet.message().ident() == ident)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no packet with ident " + ident));
  }
}
