package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Ability;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.Equipment;
import com.mir2.world.EquipmentSlot;
import com.mir2.world.GameMap;
import com.mir2.world.ItemDatabase;
import com.mir2.world.LevelAbilities;
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItemsDb;
import com.mir2.world.WorldEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * W47: the 隐身术 family on the real wire. A {@code CM_SPELL} for {@code SKILL_CLOAK}(18) or
 * {@code SKILL_BIGCLOAK}(19) must produce {@code SM_CHARSTATUSCHANGED(657)} with the frame
 * Delphi's {@code RM_CHARSTATUSCHANGED -> SM_CHARSTATUSCHANGED} conversion writes
 * (ObjBase.pas:5966): recog = the object the status word belongs to, param/tag = the low/high
 * halves of {@code m_nCharStatus}, series = {@code m_nHitSpeed}. For the private spell the
 * status frame precedes {@code SM_MAGICFIRE} (the case body runs before DoSpell's trailing
 * broadcast, Magic.pas:714-716); for the group spell it arrives 800 ms later, addressed to each
 * friend (Magic.pas:1300 → ObjBase.pas:4619).
 */
class GameCloakProtocolTest {
  private static final int SKILL_CLOAK = 18;
  private static final int SKILL_BIGCLOAK = 19;
  /** {@code $80000000 shr 8} = {@code 0x00800000}: bit 23 lands in the <em>high</em> word. */
  private static final int TRANSPARENT_LO_WORD = 0x0000;
  private static final int TRANSPARENT_HI_WORD = 0x0080;

  private final AtomicLong now = new AtomicLong();

  @Test
  void privateCloakSendsTheStatusWordBeforeTheCastFrame() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(charmed(id, SKILL_CLOAK));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      int taoist = entering.join().id();
      output.clear();

      // CM_SPELL on the caster's own cell, no object named: Recog = MakeLong(x, y).
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          5 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_CLOAK, 0))));
      world.tickOnce();

      WirePacket status = packet(output, ProtocolConstants.SM_CHARSTATUSCHANGED);
      assertEquals(taoist, status.message().recog());
      assertEquals(TRANSPARENT_LO_WORD, status.message().param(),
          "bit 23 of the word sits above LoWord, so the low half stays zero");
      assertEquals(TRANSPARENT_HI_WORD, status.message().tag());
      assertEquals(0, status.message().series(), "m_nHitSpeed has no Java source yet");
      assertTrue(packet(output, ProtocolConstants.SM_MAGICFIRE).message().series()
              == (4 | (16 << 8)),
          "Magic.DB row 18: MakeWord(btEffectType = 4, btEffect = 16)");
      assertTrue(indexOf(output, ProtocolConstants.SM_CHARSTATUSCHANGED)
              < indexOf(output, ProtocolConstants.SM_MAGICFIRE),
          "StatusChanged runs inside the case body; RM_MAGICFIRE closes DoSpell");
      assertTrue(indexOf(output, ProtocolConstants.SM_DURACHANGE) >= 0,
          "UseAmulet already spent one 100-unit 护身符 charge");
      assertTrue(output.contains(new GameOutbound.Status(true, 101)));
    }
  }

  @Test
  void groupCloakDeliversTheStatusFrameToEachFriendAfterEightHundredMillis() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    store.save(charmed(casterId, SKILL_BIGCLOAK));
    store.save(new PlayerState(memberId, levelAbility(LevelAbilities.JOB_WARRIOR, 20), List.of(),
        Equipment.empty(), 0, 0, 0, List.of()));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> casterOutput = new ArrayList<>();
      List<GameOutbound> memberOutput = new ArrayList<>();
      GameProtocolAdapter casterAdapter = new GameProtocolAdapter(world, casterOutput::add, () -> 102);
      GameProtocolAdapter memberAdapter = new GameProtocolAdapter(world, memberOutput::add, () -> 103);
      var entering = world.enterPlayer(casterId, "道士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_TAOIST, casterAdapter);
      world.tickOnce();
      int caster = entering.join().id();
      var joining = world.enterPlayer(memberId, "队友", "0", new Position(6, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WARRIOR, memberAdapter);
      world.tickOnce();
      int member = joining.join().id();

      var allowing = world.setAllowGroup(member, true);
      world.tickOnce();
      assertTrue(allowing.join());
      var creating = world.createGroup(caster, "队友");
      world.tickOnce();
      assertTrue(creating.join());
      casterOutput.clear();
      memberOutput.clear();

      assertTrue(casterAdapter.handle(new WirePacket(new DefaultMessage(
          5 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_BIGCLOAK, 0))));
      world.tickOnce();

      assertTrue(packets(memberOutput, ProtocolConstants.SM_CHARSTATUSCHANGED).isEmpty(),
          "nothing lands on the friend before the self-addressed RM_TRANSPARENT fires");

      now.addAndGet(800);
      world.tickOnce();

      WirePacket status = packet(memberOutput, ProtocolConstants.SM_CHARSTATUSCHANGED);
      assertEquals(member, status.message().recog());
      assertEquals(TRANSPARENT_LO_WORD, status.message().param());
      assertEquals(TRANSPARENT_HI_WORD, status.message().tag());
      // SendRefMsg broadcasts to every player in range, so the caster's client witnesses both
      // repaints: its own (it is a friend of itself) and the member's.
      assertEquals(1, packets(casterOutput, ProtocolConstants.SM_CHARSTATUSCHANGED).stream()
              .filter(frame -> frame.message().recog() == caster).count(),
          "the caster is a friend of themself and gets exactly one repaint");
      assertEquals(1, packets(casterOutput, ProtocolConstants.SM_CHARSTATUSCHANGED).stream()
              .filter(frame -> frame.message().recog() == member).count());
    }
  }

  @Test
  void aMissingCharmAnswersMagicFireFailWithoutAnyStatusFrame() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, 26), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_CLOAK))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 104);
      world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          5 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_CLOAK, 0))));
      world.tickOnce();

      assertEquals(ProtocolConstants.SM_MAGICFIRE_FAIL,
          packet(output, ProtocolConstants.SM_MAGICFIRE_FAIL).message().ident());
      assertTrue(packets(output, ProtocolConstants.SM_CHARSTATUSCHANGED).isEmpty(),
          "boSpellFail exits DoSpell before the case body, so no StatusChanged is broadcast");
      assertTrue(packets(output, ProtocolConstants.SM_MAGICFIRE).isEmpty());
      assertTrue(output.contains(new GameOutbound.Status(true, 104)),
          "mana was already spent and +GOOD already answered before the amulet check ran");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static PlayerState charmed(UUID id, int magicId) {
    return new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, 26), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT,
            BackpackItem.of(StdItemsDb.byName("护身符").orElseThrow(), 501))),
        0, 0, 0, List.of(PlayerSkill.learned(magicId)));
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20261008L), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static List<WirePacket> packets(List<GameOutbound> output, int ident) {
    return output.stream().filter(GameOutbound.Packet.class::isInstance)
        .map(GameOutbound.Packet.class::cast).map(GameOutbound.Packet::packet)
        .filter(packet -> packet.message().ident() == ident).toList();
  }

  private static WirePacket packet(List<GameOutbound> output, int ident) {
    return packets(output, ident).getFirst();
  }

  /** Position of the first frame with {@code ident} in the outbound stream, or -1. */
  private static int indexOf(List<GameOutbound> output, int ident) {
    for (int index = 0; index < output.size(); index++) {
      if (output.get(index) instanceof GameOutbound.Packet outbound
          && outbound.packet().message().ident() == ident) {
        return index;
      }
    }
    return -1;
  }

  private static final class Store implements PlayerStateStore {
    private final Map<UUID, PlayerState> states = new HashMap<>();

    @Override
    public Optional<PlayerState> load(UUID characterId) {
      return Optional.ofNullable(states.get(characterId));
    }

    @Override
    public void save(PlayerState state) {
      states.put(state.characterId(), state);
    }
  }
}
