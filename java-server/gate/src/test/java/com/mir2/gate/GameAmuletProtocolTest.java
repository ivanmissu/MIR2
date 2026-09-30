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
import com.mir2.world.MonsterTemplate;
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItem;
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
 * W37: the amulet-gated {@code SKILL_FIRECHARM}(13)/{@code SKILL_AMYOUNSUL}(6) cast path answers
 * {@code SM_MAGICFIRE_FAIL} with no {@code SM_SYSMESSAGE} and no {@code +FAIL} status flip when
 * the caster has no charm to spend — distinct from the ordinary {@code SpellRejected} wire shape
 * covered by {@code GameMagicProtocolTest}.
 */
class GameAmuletProtocolTest {
  private static final int SKILL_FIRECHARM = 13;

  private final AtomicLong now = new AtomicLong();

  @Test
  void fireCharmWithoutAnAmuletSendsOnlyMagicFireFailAndKeepsStatusGood() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, 20), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIRECHARM))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 99);
      world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();

      MonsterTemplate dummy = new MonsterTemplate("木桩", 0,
          Ability.monster(1_000, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      var spawned = world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT);
      world.tickOnce();
      int targetId = spawned.join().id();
      output.clear();

      WirePacket spell = new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          targetId & 0xffff, SKILL_FIRECHARM, (targetId >>> 16) & 0xffff));
      assertTrue(adapter.handle(spell));
      world.tickOnce();

      assertEquals(ProtocolConstants.SM_MAGICFIRE_FAIL,
          packet(output, ProtocolConstants.SM_MAGICFIRE_FAIL).message().ident());
      assertFalse(output.stream().anyMatch(item -> item instanceof GameOutbound.Packet outbound
          && outbound.packet().message().ident() == ProtocolConstants.SM_MAGICFIRE),
          "no charm means no RM_MAGICFIRE projectile ever goes out");
      assertFalse(output.stream().anyMatch(item -> item instanceof GameOutbound.Packet outbound
          && outbound.packet().message().ident() == ProtocolConstants.SM_SYSMESSAGE),
          "the missing-charm fizzle is silent on the wire in Delphi too");
      assertTrue(output.contains(new GameOutbound.Status(true, 99)),
          "mana was already spent and +GOOD already answered before the amulet check ran");
    }
  }

  @Test
  void fireCharmWithAnAmuletSendsMagicFireAndWearsTheCharm() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    StdItem amulet = StdItemsDb.byName("护身符").orElseThrow();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, 20), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT, BackpackItem.of(amulet, 401))),
        0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIRECHARM))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 100);
      world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();

      MonsterTemplate dummy = new MonsterTemplate("木桩", 0,
          Ability.monster(1_000, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      var spawned = world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT);
      world.tickOnce();
      int targetId = spawned.join().id();
      output.clear();

      WirePacket spell = new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          targetId & 0xffff, SKILL_FIRECHARM, (targetId >>> 16) & 0xffff));
      assertTrue(adapter.handle(spell));
      world.tickOnce();

      assertEquals(ProtocolConstants.SM_MAGICFIRE,
          packet(output, ProtocolConstants.SM_MAGICFIRE).message().ident());
      assertEquals(ProtocolConstants.SM_DURACHANGE,
          packet(output, ProtocolConstants.SM_DURACHANGE).message().ident());
      assertFalse(output.stream().anyMatch(item -> item instanceof GameOutbound.Packet outbound
          && outbound.packet().message().ident() == ProtocolConstants.SM_MAGICFIRE_FAIL));
      assertTrue(output.contains(new GameOutbound.Status(true, 100)));
    }
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20260928L), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static WirePacket packet(List<GameOutbound> output, int ident) {
    return output.stream().filter(GameOutbound.Packet.class::isInstance)
        .map(GameOutbound.Packet.class::cast).map(GameOutbound.Packet::packet)
        .filter(packet -> packet.message().ident() == ident).findFirst().orElseThrow();
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
