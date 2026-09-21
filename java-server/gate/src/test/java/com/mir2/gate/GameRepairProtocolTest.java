package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.Equipment;
import com.mir2.world.GameMap;
import com.mir2.world.ItemDatabase;
import com.mir2.world.LevelAbilities;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItem;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W15 wire mapping: the merchant repair family — {@code CM_MERCHANTDLGSELECT},
 * {@code CM_MERCHANTQUERYREPAIRCOST}, {@code CM_USERREPAIRITEM} and their replies, plus the
 * gold the replies carry ({@code SM_GOLDCHANGED}, the {@code SM_ABILITY} header).
 */
class GameRepairProtocolTest {
  private final AtomicLong now = new AtomicLong(1_000);

  /** 测试剑: StdMode 5 weapon, DuraMax 100, price 101 (not a multiple of three). */
  private static StdItem testSword() {
    return new StdItem("测试剑", 5, 0, 2, 0, 0, 0, 1, 100, 0, 0,
        StdItem.packedRange(2, 5), 0, 0, 0, 0, 101);
  }

  private static final class PreparedStore implements PlayerStateStore {
    private final UUID characterId;
    private final PlayerState state;

    PreparedStore(UUID characterId, PlayerState state) {
      this.characterId = characterId;
      this.state = state;
    }

    @Override
    public Optional<PlayerState> load(UUID id) {
      return characterId.equals(id) ? Optional.of(state) : Optional.empty();
    }

    @Override
    public void save(PlayerState newState) {
      // Intentionally transient beyond the preload.
    }
  }

  @Test
  void theRepairDialogCostQuoteAndRepairPacketsMatchTheDelphiLayouts() {
    List<GameOutbound> output = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store)) {
      var entered = world.enterPlayer(characterId, "战士", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      int playerId = entered.join().id();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 7);
      output.clear();

      // ClMain.pas:3094 SendMerchantDlgSelect: MakeDefaultMsg(CM_MERCHANTDLGSELECT, merchant,
      // 0, 0, 0) + EncodeString(label).
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_MERCHANTDLGSELECT, 0, 0, 0),
          WireMessageCodec.encodeBody("@repair"))));
      world.tickOnce();
      WirePacket dialog = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_SENDUSERREPAIR, dialog.message().ident());
      assertEquals(9001, dialog.message().recog(), "the echoed merchant id opens the dialog");

      // ClMain.pas:3120 SendQueryRepairCost: MakeDefaultMsg(CM_MERCHANTQUERYREPAIRCOST,
      // merchant, Loword(itemindex), Hiword(itemindex)) + EncodeString(itemname).
      // MakeIndex 3 packs as param=3, tag=0.
      output.clear();
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_MERCHANTQUERYREPAIRCOST, 3, 0, 0),
          WireMessageCodec.encodeBody("测试剑"))));
      world.tickOnce();
      WirePacket quote = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_SENDREPAIRCOST, quote.message().ident());
      assertEquals(20, quote.message().recog(), "Round(101 div 3 / 100 * 60) = 20");

      // ClMain.pas:3136 SendRepairItem: same layout through CM_USERREPAIRITEM.
      output.clear();
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_USERREPAIRITEM, 3, 0, 0),
          WireMessageCodec.encodeBody("测试剑"))));
      world.tickOnce();
      WirePacket repaired = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_USERREPAIRITEM_OK, repaired.message().ident());
      // ClMain.pas:4594: Recog=gold, Param=Dura, Tag=DuraMax — the client refreshes all
      // three from this one packet.
      assertEquals(480, repaired.message().recog());
      assertEquals(98, repaired.message().param());
      assertEquals(98, repaired.message().tag());
    }
  }

  @Test
  void aMakeIndexAboveSixteenBitsPacksAcrossParamAndTag() {
    List<GameOutbound> output = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    // MakeIndex 0x10003 = 65539: Loword 3 rides in Param, Hiword 1 in Tag.
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(new BackpackItem(testSword(), 0x10003, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store)) {
      var entered = world.enterPlayer(characterId, "战士", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      GameProtocolAdapter adapter =
          new GameProtocolAdapter(world, entered.join().id(), output::add, () -> 7);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_MERCHANTQUERYREPAIRCOST, 3, 1, 0),
          WireMessageCodec.encodeBody("测试剑"))));
      world.tickOnce();
      WirePacket quote = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_SENDREPAIRCOST, quote.message().ident(),
          "the split MakeIndex must resolve back to the bag item");
      assertEquals(20, quote.message().recog());
    }
  }

  @Test
  void anEmptyWalletAnswersWithTheFailurePacket() {
    List<GameOutbound> output = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 1));
    try (WorldEngine world = engine(store)) {
      var entered = world.enterPlayer(characterId, "战士", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      GameProtocolAdapter adapter =
          new GameProtocolAdapter(world, entered.join().id(), output::add, () -> 7);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_USERREPAIRITEM, 3, 0, 0),
          WireMessageCodec.encodeBody("测试剑"))));
      world.tickOnce();
      WirePacket failed = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_USERREPAIRITEM_FAIL, failed.message().ident());
      assertEquals(0, failed.message().recog());
    }
  }

  @Test
  void abilityAndGoldPacketsCarryTheWalletInTheHeader() {
    List<GameOutbound> output = new ArrayList<>();
    GameProtocolAdapter adapter = adapterFor(output);

    // ObjBase.pas:5685 RM_ABILITY: MakeDefaultMsg(SM_ABILITY, m_nGold, MakeWord(btJob, 99),
    // LoWord(GameGold), HiWord(GameGold)) + the 50-byte TAbility body.
    adapter.send(new WorldEvent.AbilityChanged(1, Ability.defaultPlayer(), 4_500, 1));
    WirePacket ability = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_ABILITY, ability.message().ident());
    assertEquals(4_500, ability.message().recog(), "the client reads its wallet from Recog");
    assertEquals((1 & 0xff) | (99 << 8), ability.message().param(),
        "MakeWord(btJob, 99) packs the job with the fixed 99 high byte");

    // RM_GOLDCHANGED: Recog = the new wallet total.
    adapter.send(new WorldEvent.GoldChanged(1, 6_000));
    WirePacket gold = ((GameOutbound.Packet) output.removeFirst()).packet();
    assertEquals(ProtocolConstants.SM_GOLDCHANGED, gold.message().ident());
    assertEquals(6_000, gold.message().recog());
  }

  private WorldEngine engine(PlayerStateStore store) {
    return new WorldEngine(WorldEngine.Config.defaults(), List.of(GameMap.empty("0", "PoC", 20, 20)),
        now::get, new Random(20020522L), store, ItemDatabase.of(StdItems.defaults()));
  }

  /** Adapter bound to player id 1 without a live world, for pure event-to-packet checks. */
  private static GameProtocolAdapter adapterFor(List<GameOutbound> output) {
    WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)));
    return new GameProtocolAdapter(world, 1, output::add, () -> 7);
  }
}
