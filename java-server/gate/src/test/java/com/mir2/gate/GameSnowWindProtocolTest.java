package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Ability;
import com.mir2.world.Direction;
import com.mir2.world.Equipment;
import com.mir2.world.GameMap;
import com.mir2.world.ItemDatabase;
import com.mir2.world.LevelAbilities;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.WorldObjectSnapshot;
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
 * W44: CM_SPELL → 冰咆哮's snapped RM_MAGICFIRE, RM_STRUCK_MAG marker and skill training.
 *
 * <p>冰咆哮 shares 爆裂火焰's whole {@code MagBigExplosion} wire shape; the frames below differ
 * only in the magic id, the {@code Magic.DB} row-33 effect pair ({@code effectType=2/effect=31})
 * and the level-zero mana cost.
 */
class GameSnowWindProtocolTest {
  private static final int SKILL_SNOWWIND = 33;
  /** {@code GetSpellPoint} at level zero: ROUND(12 / 4 * 1) + defSpell(30). */
  private static final int MANA_COST = 33;
  /** {@code Magic.DB} row 33: {@code NeedL1 = 35}. */
  private static final int WIZARD_LEVEL = 35;

  private final AtomicLong now = new AtomicLong();

  @Test
  void snowWindUsesTheSnappedCenterAndEmitsMagicalStruckAndTrainingFrames() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SNOWWIND))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      var first = spawn(world, "中心木桩", 7, 5);
      var second = spawn(world, "边界木桩", 8, 6);
      int mana = ability(world, caster).mp();
      output.clear();

      // A target id within one cell snaps the click from (6,5) onto the object at (7,5).
      int targetId = first.id();
      DefaultMessage spell = new DefaultMessage(6 | (5 << 16), ProtocolConstants.CM_SPELL,
          targetId & 0xffff, SKILL_SNOWWIND, targetId >>> 16);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size(), "one ground spell emits one RM_MAGICFIRE");
      assertEquals(new Position(7, 5),
          new Position(fired.getFirst().message().param(), fired.getFirst().message().tag()));
      assertEquals(2 | (31 << 8), fired.getFirst().message().series(),
          "Magic.DB row 33 uses effectType=2/effect=31");
      assertEquals(targetId, decodeInteger(fired.getFirst().encodedBody()));
      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "CM_SPELL answers +GOOD");
      assertEquals(mana - MANA_COST, ability(world, caster).mp());

      List<WirePacket> struck = packets(output, ProtocolConstants.SM_STRUCK);
      assertEquals(2, struck.size(), "both legal objects inside the 3x3 square are hit");
      assertTrue(struck.stream().allMatch(packet -> struckMagicFlag(packet) == 1),
          "RM_STRUCK_MAG is encoded as TMessageBodyWL.lTag2 = 1");
      assertEquals(1, packets(output, ProtocolConstants.SM_MAGIC_LVEXP).size(),
          "boTrain produces one skill-training frame for the whole area cast");
      WirePacket training = packets(output, ProtocolConstants.SM_MAGIC_LVEXP).getFirst();
      assertEquals(SKILL_SNOWWIND, training.message().recog());
      assertEquals(0, training.message().param(), "the skill remains at level zero");
      assertTrue(training.message().tag() > 0, "training points are visible on the wire");
      assertTrue(ability(world, first.id()).hp() < first.ability().hp());
      assertTrue(ability(world, second.id()).hp() < second.ability().hp());
    }
  }

  @Test
  void anEmptySquareStillBroadcastsTheCastButTrainsNothing() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SNOWWIND))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      int mana = ability(world, caster).mp();
      output.clear();

      DefaultMessage spell = new DefaultMessage(9 | (9 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_SNOWWIND, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size(), "an empty square still plays the cast and its projectile");
      assertEquals(new Position(9, 9),
          new Position(fired.getFirst().message().param(), fired.getFirst().message().tag()));
      assertEquals(mana - MANA_COST, ability(world, caster).mp(),
          "Delphi spends the mana before MagBigExplosion ever looks for a target");
      assertEquals(0, packets(output, ProtocolConstants.SM_STRUCK).size());
      assertEquals(0, packets(output, ProtocolConstants.SM_MAGIC_LVEXP).size(),
          "no proper target means MagBigExplosion returns False and boTrain stays unset");
    }
  }

  // ------------------------------------------------------------------ helpers

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y) {
    MonsterTemplate target = new MonsterTemplate(name, 0, Ability.monster(10_000, 0, 0, 0, 0),
        1, 1_000_000, 1_000_000, 0, List.of());
    var pending = world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN);
    world.tickOnce();
    return pending.join();
  }

  private static Ability ability(WorldEngine world, int objectId) {
    var pending = world.snapshot(objectId);
    world.tickOnce();
    return pending.join().ability();
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private static int decodeInteger(String encodedBody) {
    byte[] raw = SixBitCodec.decodeString(encodedBody);
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static int struckMagicFlag(WirePacket packet) {
    byte[] raw = SixBitCodec.decodeString(packet.encodedBody());
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
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
