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
import com.mir2.world.MonsterBehavior;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItemsDb;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldObjectSnapshot;
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
 * W45 wire half of 抗拒火环 ({@code SKILL_FIREWIND = 8}): the RM_MAGICFIRE cast frame and the
 * RM_PUSH / {@code SM_BACKSTEP} frames {@code CharPushed} emits per traversed cell, plus the
 * two absences that separate this skill from every other wizard branch — no {@code SM_STRUCK}
 * at all, and training only when {@code MagPushArround} returned a positive count.
 */
class GamePushArroundProtocolTest {
  private static final int SKILL_FIREWIND = 8;
  /** {@code GetSpellPoint} at level zero: ROUND(8 / 4 * 1) + defSpell(0). */
  private static final int MANA_COST = 2;
  /** {@code Magic.DB} row 8: {@code NeedL1 = 12}. */
  private static final int WIZARD_LEVEL = 12;

  private final AtomicLong now = new AtomicLong();

  @Test
  void adjacentTargetProducesBackstepFramesInsteadOfStruckFrames() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, 20),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIREWIND))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      WorldObjectSnapshot victim = spawn(world, "木桩", 6, 5);
      int mana = ability(world, caster).mp();
      output.clear();

      DefaultMessage spell = new DefaultMessage(6 | (5 << 16), ProtocolConstants.CM_SPELL,
          victim.id() & 0xffff, SKILL_FIREWIND, victim.id() >>> 16);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size(), "the cast still plays even though nothing is damaged");
      assertEquals(new Position(6, 5),
          new Position(fired.getFirst().message().param(), fired.getFirst().message().tag()));
      assertEquals(4 | (6 << 8), fired.getFirst().message().series(),
          "Magic.DB row 8 uses effectType=4/effect=6, i.e. Series 1540");
      assertEquals(victim.id(), decodeInteger(fired.getFirst().encodedBody()));
      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "CM_SPELL answers +GOOD");
      assertEquals(mana - MANA_COST, ability(world, caster).mp());
      assertEquals(0, packets(output, ProtocolConstants.SM_STRUCK).size(),
          "MagPushArround never calls SendMsg(RM_MAGSTRUCK)");

      List<WirePacket> backstep = packets(output, ProtocolConstants.SM_BACKSTEP);
      assertEquals(1, backstep.size(), "one RM_PUSH per successfully traversed cell");
      WirePacket push = backstep.getFirst();
      assertEquals(victim.id(), push.message().recog());
      // CharPushed assigns m_nCurrX/m_nCurrY before SendRefMsg, so RM_PUSH names the cell the
      // victim now stands on; the pre-push cell never reaches the wire.
      assertEquals(7, push.message().param());
      assertEquals(5, push.message().tag());
      assertEquals(Direction.LEFT.code(), push.message().series() & 0xff,
          "nBackDir faces the caster that pushed westwards");
      assertEquals(new CharacterDescription(0, 0).encode(), push.encodedBody(),
          "sendObjectAction encodes the object's own feature/status body");
      assertEquals(new Position(7, 5), position(world, victim.id()),
          "the pushed monster really moved one cell west");

      List<WirePacket> training = packets(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(1, training.size(),
          "MagPushArround returned > 0, so DoSpell trains the skill once");
      assertEquals(SKILL_FIREWIND, training.getFirst().message().recog());
      assertEquals(0, training.getFirst().message().param(), "the skill remains at level zero");
      assertTrue(training.getFirst().message().tag() > 0, "training points are visible");
    }
  }

  @Test
  void emptyNeighbourhoodBroadcastsTheCastButNoBackstepOrTraining() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, 20),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIREWIND))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      int mana = ability(world, caster).mp();
      output.clear();

      DefaultMessage spell = new DefaultMessage(8 | (8 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_FIREWIND, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size());
      assertEquals(4 | (6 << 8), fired.getFirst().message().series());
      assertEquals(mana - MANA_COST, ability(world, caster).mp(),
          "an empty 3x3 still pays the mana ClientSpellXY spent before DoSpell");
      assertEquals(0, packets(output, ProtocolConstants.SM_BACKSTEP).size());
      assertEquals(0, packets(output, ProtocolConstants.SM_MAGIC_LVEXP).size(),
          "MagPushArround returned 0, so boTrain stays unset and no RM_MAGIC_LVEXP is sent");
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
        12, 1_000_000, 1_000_000, 0, MonsterBehavior.STATIONARY, List.of());
    var pending = world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN);
    world.tickOnce();
    return pending.join();
  }

  private static Position position(WorldEngine world, int objectId) {
    var pending = world.snapshot(objectId);
    world.tickOnce();
    return pending.join().position();
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
