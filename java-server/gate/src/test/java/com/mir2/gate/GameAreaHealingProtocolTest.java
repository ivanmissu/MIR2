package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
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
 * W40: the wire shape of 群体治愈术 ({@code SKILL_BIGHEALLING} = 29, Magic.pas:532). One
 * CM_SPELL produces one SM_MAGICFIRE on the (snapped) click, one immediate
 * SM_HEALTHSPELLCHANGED for the caster's spent mana, and — 800 ms later — one
 * SM_HEALTHSPELLCHANGED per friend that actually recovered HP.
 */
class GameAreaHealingProtocolTest {
  private static final int SKILL_BIGHEALLING = 29;
  /** Magic.DB row 29: {@code Round(12 / 4 * 1) + 30}. */
  private static final int MANA_COST = 33;

  private final AtomicLong now = new AtomicLong();

  @Test
  void oneCastFiresOnceAndHealsEveryPartyMemberWithItsOwnHealthPacket() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    store.save(hurt(casterId, LevelAbilities.JOB_TAOIST, 31, SKILL_BIGHEALLING, 40));
    store.save(hurt(memberId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      var entering = world.enterPlayer(casterId, "道士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      int caster = entering.join().id();
      var joining = world.enterPlayer(memberId, "队友", "0", new Position(6, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WARRIOR, ignored -> {});
      world.tickOnce();
      int member = joining.join().id();

      var allowing = world.setAllowGroup(member, true);
      world.tickOnce();
      assertTrue(allowing.join());
      var creating = world.createGroup(caster, "队友");
      world.tickOnce();
      assertTrue(creating.join());

      Ability casterBefore = ability(world, caster);
      Ability memberBefore = ability(world, member);
      int heal = expectedHeal(casterBefore);
      output.clear();

      // CM_SPELL on the caster's own cell, no object named: Recog = MakeLong(x, y).
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          5 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_BIGHEALLING, 0))));
      world.tickOnce();

      WirePacket fire = packets(output, ProtocolConstants.SM_MAGICFIRE).getFirst();
      assertEquals(1, packets(output, ProtocolConstants.SM_MAGICFIRE).size(),
          "an area spell still fires exactly one RM_MAGICFIRE");
      assertEquals(new Position(5, 5),
          new Position(fire.message().param(), fire.message().tag()));
      // Magic.DB row 29: MakeWord(btEffectType = 2, btEffect = 27).
      assertEquals(2 | (27 << 8), fire.message().series());
      assertEquals(0, decodeBodyInteger(fire.encodedBody()), "no click target was named");
      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "+GOOD answers CM_SPELL");

      // The mana is spent immediately (DamageSpell/HealthSpellChanged before the case body).
      WirePacket manaPacket = health(output, caster).getLast();
      assertEquals(casterBefore.mp() - MANA_COST, manaPacket.message().tag());
      assertEquals(casterBefore.hp(), manaPacket.message().param(),
          "HP only moves when the delayed RM_MAGHEALING lands");
      assertTrue(health(output, member).isEmpty(), "nothing has healed yet");

      output.clear();
      now.addAndGet(800);
      world.tickOnce();

      assertEquals(casterBefore.hp() + heal, health(output, caster).getLast().message().param());
      assertEquals(memberBefore.hp() + heal, health(output, member).getLast().message().param(),
          "每个实际恢复的目标都有独立的 SM_HEALTHSPELLCHANGED");
      assertEquals(casterBefore.hp() + heal, ability(world, caster).hp());
      assertEquals(memberBefore.hp() + heal, ability(world, member).hp());
    }
  }

  @Test
  void aRejectedCastAnswersMagicFireFailPlusSysMessageAndSpendsNothing() {
    Store store = new Store();
    UUID warriorId = UUID.randomUUID();
    store.save(new PlayerState(warriorId, levelAbility(LevelAbilities.JOB_WARRIOR, 40), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_BIGHEALLING))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 43);
      var entering = world.enterPlayer(warriorId, "战士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WARRIOR, adapter);
      world.tickOnce();
      int warrior = entering.join().id();
      int mana = ability(world, warrior).mp();
      output.clear();

      // Magic.DB row 29 is job = 2 (道士): a warrior never gets past the job gate.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          5 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_BIGHEALLING, 0))));
      world.tickOnce();

      assertFalse(packets(output, ProtocolConstants.SM_MAGICFIRE_FAIL).isEmpty());
      assertTrue(packets(output, ProtocolConstants.SM_MAGICFIRE).isEmpty(),
          "a rejected cast never reaches boSpellFire");
      assertEquals("当前职业无法使用该技能", WireMessageCodec.decodeBody(
          packets(output, ProtocolConstants.SM_SYSMESSAGE).getFirst().encodedBody()));
      assertTrue(output.contains(new GameOutbound.Status(false, 43)));
      assertEquals(mana, ability(world, warrior).mp());
    }
  }

  // ------------------------------------------------------------------ helpers

  /** Pumps one tick so the queued snapshot command runs, then reads the answer. */
  private static Ability ability(WorldEngine world, int objectId) {
    var pending = world.snapshot(objectId);
    world.tickOnce();
    return pending.join().ability();
  }

  /** {@code GetPower(MPow) + LoWord(SC) * 2} with every FixedRandom draw collapsed to zero. */
  private static int expectedHeal(Ability caster) {
    return (int) Math.rint(10 / 4.0) + 4 + caster.minSc() * 2;
  }

  private static PlayerState hurt(UUID id, int job, int level, int magicId, int missing) {
    Ability ability = levelAbility(job, level);
    List<PlayerSkill> skills = magicId == 0 ? List.of() : List.of(PlayerSkill.learned(magicId));
    return new PlayerState(id, ability.withHp(ability.maxHp() - missing), List.of(),
        Equipment.empty(), 0, 0, 0, skills);
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private static int decodeBodyInteger(String encodedBody) {
    byte[] raw = SixBitCodec.decodeString(encodedBody);
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  /** Every random draw collapses to its range minimum, so the shared power roll is exact. */
  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  private static List<WirePacket> packets(List<GameOutbound> output, int ident) {
    return output.stream().filter(GameOutbound.Packet.class::isInstance)
        .map(GameOutbound.Packet.class::cast).map(GameOutbound.Packet::packet)
        .filter(packet -> packet.message().ident() == ident).toList();
  }

  /** SM_HEALTHSPELLCHANGED frames addressed to one object (recog = object id). */
  private static List<WirePacket> health(List<GameOutbound> output, int objectId) {
    return packets(output, ProtocolConstants.SM_HEALTHSPELLCHANGED).stream()
        .filter(packet -> packet.message().recog() == objectId).toList();
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
