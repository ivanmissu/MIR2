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

/** W43: CM_SPELL → 地狱雷光's caster-centred RM_MAGICFIRE, RM_STRUCK_MAG frames and training. */
class GameElecBlizzardProtocolTest {
  private static final int SKILL_LIGHTFLOWER = 24;
  /** {@code GetSpellPoint} of Magic.DB row 24 at skill level zero. */
  private static final int MANA_COST = 29;

  private final AtomicLong now = new AtomicLong();

  @Test
  void lightFlowerBlastsTheCasterSquareAndIgnoresTheClickCell() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, 30), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_LIGHTFLOWER))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      var undead = spawn(world, "稻草人", 6, 6, true);
      var living = spawn(world, "木桩", 7, 5, false);
      var clicked = spawn(world, "点击处木桩", 10, 5, false);
      int mana = ability(world, caster).mp();
      output.clear();

      // The client clicks empty ground far from its own cell: Recog carries the click, the
      // target id words stay zero, exactly like a normal ground-target spell.
      DefaultMessage spell = new DefaultMessage(10 | (5 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_LIGHTFLOWER, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      List<WirePacket> fired = packets(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(1, fired.size(), "one blizzard emits one RM_MAGICFIRE");
      assertEquals(new Position(10, 5),
          new Position(fired.getFirst().message().param(), fired.getFirst().message().tag()),
          "the broadcast still carries the clicked cell, not the caster's");
      assertEquals(4 | (22 << 8), fired.getFirst().message().series(),
          "Magic.DB row 24 uses effectType=4/effect=22");
      assertEquals(0, decodeInteger(fired.getFirst().encodedBody()),
          "a ground click names no target object");
      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "CM_SPELL answers +GOOD");
      assertEquals(mana - MANA_COST, ability(world, caster).mp());

      List<WirePacket> struck = packets(output, ProtocolConstants.SM_STRUCK);
      assertEquals(2, struck.size(),
          "only the two objects inside the caster's 5x5 square are hit");
      assertTrue(struck.stream().allMatch(packet -> struckMagicFlag(packet) == 1),
          "RM_STRUCK_MAG is encoded as TMessageBodyWL.lTag2 = 1");
      assertTrue(ability(world, undead.id()).hp() < undead.ability().hp());
      assertTrue(ability(world, living.id()).hp() < living.ability().hp());
      assertEquals(clicked.ability().hp(), ability(world, clicked.id()).hp(),
          "the click cell itself is three cells from the caster and stays unharmed");

      List<WirePacket> training = packets(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(1, training.size(), "boTrain produces one skill-training frame per cast");
      assertEquals(SKILL_LIGHTFLOWER, training.getFirst().message().recog());
      assertEquals(0, training.getFirst().message().param(), "the skill remains at level zero");
      assertTrue(training.getFirst().message().tag() > 0,
          "training points are visible on the wire");
    }
  }

  @Test
  void aClickOnEmptyGroundWithNobodyInRangeStillSpendsManaAndShowsTheCast() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, 30), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_LIGHTFLOWER))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "法师", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      int mana = ability(world, caster).mp();
      output.clear();

      DefaultMessage spell = new DefaultMessage(5 | (9 << 16), ProtocolConstants.CM_SPELL,
          0, SKILL_LIGHTFLOWER, 0);
      assertTrue(adapter.handle(new WirePacket(spell)));
      world.tickOnce();

      assertEquals(1, packets(output, ProtocolConstants.SM_MAGICFIRE).size(),
          "RM_MAGICFIRE goes out even when the square only holds the caster");
      assertEquals(0, packets(output, ProtocolConstants.SM_STRUCK).size());
      assertEquals(0, packets(output, ProtocolConstants.SM_MAGIC_LVEXP).size(),
          "an empty square never trains the skill");
      assertEquals(mana - MANA_COST, ability(world, caster).mp());
    }
  }

  // ------------------------------------------------------------------ helpers

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y, boolean undead) {
    MonsterTemplate target = new MonsterTemplate(name, 0, Ability.monster(10_000, 0, 0, 0, 0),
        1, 1_000_000, 1_000_000, 0, MonsterBehavior.STATIONARY, List.of(), List.of(), undead);
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
