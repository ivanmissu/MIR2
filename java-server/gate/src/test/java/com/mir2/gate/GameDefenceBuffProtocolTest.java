package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
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
import com.mir2.world.StdItem;
import com.mir2.world.StdItemsDb;
import com.mir2.world.WorldEngine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * W46 wire half of 神圣战甲术 ({@code SKILL_DEJIWONHO} = 15) and 幽灵盾
 * ({@code SKILL_HANGMAJINBUB} = 14): the {@code RM_MAGICFIRE} cast frame carrying the row's
 * {@code effectType = 9}, the {@code RM_ABILITY} refresh {@code DefenceUp} sends to every buffed
 * player, the green {@code g_sDefenceUpTime} hint, and the amulet-gated fizzle that shares
 * {@code RM_MAGICFIREFAIL} with 灵魂火符.
 */
class GameDefenceBuffProtocolTest {
  private static final int SKILL_HANGMAJINBUB = 14;
  private static final int SKILL_DEJIWONHO = 15;
  /** Magic.DB row 15: {@code effectType = 9}, {@code effect = 12} → Series 3081. */
  private static final int HOLY_ARMOUR_SERIES = 9 | (12 << 8);
  /** {@code GetSpellPoint} at level zero for both rows: {@code Round(15 / 4 * 1)} + 0. */
  private static final int MANA_COST = 4;
  /** Level 25 clears row 15's {@code NeedL1 = 25}; {@code 2 + 25 div 7} is the AC bonus. */
  private static final int TAOIST_LEVEL = 25;
  private static final int DEFENCE_BONUS = 5;

  private final AtomicLong now = new AtomicLong();

  @Test
  void holyArmourSendsMagicFireAbilityHintAndTrainingOnOneCharmCharge() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    StdItem amulet = StdItemsDb.byName("护身符").orElseThrow();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT, BackpackItem.of(amulet, 901))),
        0, 0, 0, List.of(PlayerSkill.learned(SKILL_DEJIWONHO))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      int mana = ability(world, caster).mp();
      output.clear();

      // A ground click on the caster's own cell: DoSpell reads only nTargetX/nTargetY.
      DefaultMessage spell = new DefaultMessage(5 | (5 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_DEJIWONHO, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "CM_SPELL answers +GOOD");
      assertEquals(mana - MANA_COST, ability(world, caster).mp());

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size());
      assertEquals(HOLY_ARMOUR_SERIES, fired.getFirst().message().series(),
          "Magic.DB row 15 uses effectType=9/effect=12");
      assertEquals(5, fired.getFirst().message().param());
      assertEquals(5, fired.getFirst().message().tag(), "the click cell rides along unchanged");
      assertEquals(0, decodeInteger(fired.getFirst().encodedBody()), "no click target was named");

      // DefenceUp ends with RecalcAbilitys + SendMsg(Self, RM_ABILITY): the packed TAbility the
      // client repaints its AC/MAC from, and nothing else (no RM_SUBABILITY, no RM_WEIGHTCHANGED).
      List<WirePacket> abilities = packets(output, ProtocolConstants.SM_ABILITY);
      assertEquals(1, abilities.size());
      int[] ranges = decodeAbility(abilities.getFirst());
      assertEquals(0, ranges[0], "the lower AC bound never moves");
      assertEquals(DEFENCE_BONUS, ranges[1], "the upper AC bound gains 2 + Level div 7");
      assertEquals(2, ranges[2], "神圣战甲术 leaves the magic defence range alone");
      assertEquals(5, ranges[3]);

      List<WirePacket> hints = packets(output, ProtocolConstants.SM_SYSMESSAGE);
      assertEquals(1, hints.size());
      assertEquals("防御力增加50秒", WireMessageCodec.decodeBody(hints.getFirst().encodedBody()),
          "g_sDefenceUpTime with the rolled duration in seconds");

      List<WirePacket> training = packets(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(1, training.size(),
          "MagMakeDefenceArea returned > 0, so DoSpell trains the skill once");
      assertEquals(SKILL_DEJIWONHO, training.getFirst().message().recog());
      assertEquals(0, training.getFirst().message().param(), "the skill remains at level zero");
      assertTrue(training.getFirst().message().tag() > 0, "training points are visible");

      List<WirePacket> wear = packets(output, ProtocolConstants.SM_DURACHANGE);
      assertEquals(1, wear.size(), "CheckAmulet/UseAmulet spends one 100-unit 护身符 charge");
      assertEquals(amulet.duraMax() - 100, wear.getFirst().message().recog(),
          "RM_DURACHANGE carries the worn charm's remaining durability in Recog");
      assertEquals(EquipmentSlot.ARM_RING_LEFT.index(), wear.getFirst().message().param());
      assertEquals(0, packets(output, ProtocolConstants.SM_STRUCK).size(),
          "a defence buff damages nobody");
    }
  }

  @Test
  void ghostShieldWithoutACharmSendsOnlyMagicFireFail() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_HANGMAJINBUB))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      int mana = ability(world, caster).mp();
      output.clear();

      DefaultMessage spell = new DefaultMessage(5 | (5 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_HANGMAJINBUB, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      assertEquals(mana - MANA_COST, ability(world, caster).mp(),
          "the mana was already gone before CheckAmulet ran");
      assertEquals(ProtocolConstants.SM_MAGICFIRE_FAIL,
          packets(output, ProtocolConstants.SM_MAGICFIRE_FAIL).getFirst().message().ident());
      assertEquals(0, packets(output, ProtocolConstants.SM_MAGICFIRE).size(),
          "boSpellFail stays True, so DoSpell exits before its RM_MAGICFIRE broadcast");
      assertEquals(0, packets(output, ProtocolConstants.SM_ABILITY).size(),
          "no MagDefenceUp ran, so no RM_ABILITY refresh goes out");
      assertEquals(0, packets(output, ProtocolConstants.SM_SYSMESSAGE).size(),
          "the missing-charm fizzle is silent in Delphi too");
      assertEquals(0, packets(output, ProtocolConstants.SM_MAGIC_LVEXP).size());
      assertTrue(output.contains(new GameOutbound.Status(true, 42)));
      assertFalse(ability(world, caster).maxMac() > 5, "the MAC bonus never landed");
    }
  }

  // ------------------------------------------------------------------ helpers

  /** {@code minAc, maxAc, minMac, maxMac} from the packed 50-byte TAbility body. */
  private static int[] decodeAbility(WirePacket packet) {
    ByteBuffer buffer = ByteBuffer.wrap(SixBitCodec.decodeString(packet.encodedBody()))
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.getShort();
    int ac = buffer.getInt();
    int mac = buffer.getInt();
    return new int[] {ac & 0xffff, ac >>> 16, mac & 0xffff, mac >>> 16};
  }

  private static int decodeInteger(String encodedBody) {
    byte[] raw = SixBitCodec.decodeString(encodedBody);
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static Ability ability(WorldEngine world, int objectId) {
    var pending = world.snapshot(objectId);
    world.tickOnce();
    return pending.join().ability();
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static List<WirePacket> packets(List<GameOutbound> output, int ident) {
    return output.stream().filter(GameOutbound.Packet.class::isInstance)
        .map(GameOutbound.Packet.class::cast).map(GameOutbound.Packet::packet)
        .filter(packet -> packet.message().ident() == ident).toList();
  }

  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
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
