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
 * W48 wire half of 心灵启示 ({@code SKILL_SHOWHP = 28}) and 圣言术 ({@code SKILL_KILLUNDEAD = 32}):
 * a real {@code CM_SPELL} must produce the {@code SM_MAGICFIRE(638)} cast frame with the Magic.DB
 * row's {@code MakeWord(effectType, effect)} series and the target id in the body; 心灵启示's
 * delayed reveal must surface as {@code SM_OPENHEALTH(1100)} (recog = object, param = HP,
 * tag = MaxHP) and its expiry as {@code SM_CLOSEHEALTH(1101)} (recog = object, rest zero);
 * 圣言术's instant kill must surface as {@code SM_MAGICFIRE} followed by {@code SM_DEATH(32)}
 * and {@code SM_WINEXP(44)}, with no {@code SM_STRUCK(31)} anywhere.
 */
class GameShowHpAndTurnUndeadProtocolTest {
  private static final int SKILL_SHOWHP = 28;
  private static final int SKILL_KILLUNDEAD = 32;
  /** Row 28: {@code MakeWord(btEffectType = 2, btEffect = 26)}. */
  private static final int SHOWHP_SERIES = 2 | (26 << 8);
  /** Row 32: {@code MakeWord(btEffectType = 2, btEffect = 30)}. */
  private static final int KILLUNDEAD_SERIES = 2 | (30 << 8);
  /** {@code SendDelayMsg(..., RM_DOOPENHEALTH, ..., 1500)} (Magic.pas:527). */
  private static final long SHOW_HP_DELAY_MILLIS = 1_500;
  /** Row 28 needs level 23; row 32 needs level 32. */
  private static final int TAOIST_LEVEL = 23;
  private static final int WIZARD_LEVEL = 40;

  private final AtomicLong now = new AtomicLong();

  @Test
  void showHpSendsOpenHealthOnTheWireAfterTheDelay() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SHOWHP))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      int taoist = entering.join().id();
      WorldObjectSnapshot target = spawn(world, "显血怪", false, 7, 5);
      output.clear();

      // CM_SPELL: Recog = MakeLong(x, y), Param/Series = the target id words, Tag = MagicId.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          target.id() & 0xffff, SKILL_SHOWHP, target.id() >>> 16))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 101)), "CM_SPELL answers +GOOD");
      WirePacket fired = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(taoist, fired.message().recog());
      assertEquals(7, fired.message().param());
      assertEquals(5, fired.message().tag());
      assertEquals(SHOWHP_SERIES, fired.message().series(),
          "Magic.DB row 28: MakeWord(btEffectType = 2, btEffect = 26)");
      assertEquals(target.id(), decodeInteger(fired.encodedBody()),
          "the body carries the target object id, as RM_MAGICFIRE does (Magic.pas:714-718)");
      assertTrue(packets(output, ProtocolConstants.SM_OPENHEALTH).isEmpty(),
          "the reveal is a delayed RM_DOOPENHEALTH, not a cast-time frame");
      // The successful reveal trains the skill: SM_MAGIC_LVEXP on the caster's own client.
      WirePacket training = packet(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(SKILL_SHOWHP, training.message().recog());
      assertEquals(0, training.message().param());

      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      WirePacket opened = packet(output, ProtocolConstants.SM_OPENHEALTH);
      assertEquals(target.id(), opened.message().recog());
      assertEquals(100, opened.message().param(), "RM_OPENHEALTH param = m_WAbil.HP");
      assertEquals(100, opened.message().tag(), "RM_OPENHEALTH tag = m_WAbil.MaxHP");
      assertEquals(0, opened.message().series());
      assertEquals("", opened.encodedBody());
    }
  }

  @Test
  void showHpCloseHealthFollowsWhenTheWindowLapses() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SHOWHP))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 102);
      var entering = world.enterPlayer(id, "道士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_TAOIST, adapter);
      world.tickOnce();
      WorldObjectSnapshot target = spawn(world, "显血怪", false, 7, 5);
      // GetPower13(GetRPow(SC) * 2 + 30) at skill level 0 with FixedRandom's minSc draw.
      int n = ability(world, entering.join().id()).minSc() * 2 + 30;
      long intervalMillis = (long) (Math.rint((n - n / 3.0) / 4.0 + n / 3.0)) * 1_000L;
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          target.id() & 0xffff, SKILL_SHOWHP, target.id() >>> 16))));
      world.tickOnce();
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();
      assertEquals(1, packets(output, ProtocolConstants.SM_OPENHEALTH).size());
      output.clear();

      // Strictly greater than m_dwShowHPTick + m_dwShowHPInterval (ObjBase.pas:4029).
      now.addAndGet(intervalMillis + 1 - SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      WirePacket closed = packet(output, ProtocolConstants.SM_CLOSEHEALTH);
      assertEquals(target.id(), closed.message().recog());
      assertEquals(0, closed.message().param());
      assertEquals(0, closed.message().tag());
      assertEquals(0, closed.message().series());
      assertEquals("", closed.encodedBody());
    }
  }

  @Test
  void showHpOpenHealthReachesOtherPlayersClients() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SHOWHP))));
    store.save(new PlayerState(targetId, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(), Equipment.empty(), 0, 0, 0, List.of()));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> casterOutput = new ArrayList<>();
      List<GameOutbound> targetOutput = new ArrayList<>();
      GameProtocolAdapter casterAdapter = new GameProtocolAdapter(world, casterOutput::add, () -> 103);
      GameProtocolAdapter targetAdapter = new GameProtocolAdapter(world, targetOutput::add, () -> 104);
      var entering = world.enterPlayer(casterId, "道士", "0", new Position(5, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_TAOIST, casterAdapter);
      world.tickOnce();
      int caster = entering.join().id();
      var joining = world.enterPlayer(targetId, "战士", "0", new Position(6, 5), Direction.DOWN,
          0, 0, LevelAbilities.JOB_WARRIOR, targetAdapter);
      world.tickOnce();
      int target = joining.join().id();
      casterOutput.clear();
      targetOutput.clear();

      assertTrue(casterAdapter.handle(new WirePacket(new DefaultMessage(
          6 | (5 << 16), ProtocolConstants.CM_SPELL,
          target & 0xffff, SKILL_SHOWHP, target >>> 16))));
      world.tickOnce();
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      // SendRefMsg(RM_OPENHEALTH) is a ±12-cell broadcast: the caster's client and the revealed
      // player's own client both receive the frame.
      assertEquals(1, packets(casterOutput, ProtocolConstants.SM_OPENHEALTH).size());
      WirePacket opened = packet(targetOutput, ProtocolConstants.SM_OPENHEALTH);
      assertEquals(target, opened.message().recog());
      assertEquals(ability(world, target).maxHp(), opened.message().tag());
    }
  }

  @Test
  void turnUndeadSendsMagicFireThenDeathOnTheWire() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_KILLUNDEAD))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 105);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int wizard = entering.join().id();
      WorldObjectSnapshot target = spawn(world, "骷髅兵", true, 7, 5);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          target.id() & 0xffff, SKILL_KILLUNDEAD, target.id() >>> 16))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 105)), "CM_SPELL answers +GOOD");
      WirePacket fired = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(wizard, fired.message().recog());
      assertEquals(KILLUNDEAD_SERIES, fired.message().series(),
          "Magic.DB row 32: MakeWord(btEffectType = 2, btEffect = 30)");
      assertEquals(target.id(), decodeInteger(fired.encodedBody()));
      WirePacket died = packet(output, ProtocolConstants.SM_DEATH);
      assertEquals(target.id(), died.message().recog());
      assertTrue(packets(output, ProtocolConstants.SM_STRUCK).isEmpty(),
          "TAnimalObject.Struck emits no wire frame — 圣言术 never sends SM_STRUCK");
      assertTrue(indexOf(output, ProtocolConstants.SM_MAGICFIRE)
              < indexOf(output, ProtocolConstants.SM_DEATH),
          "RM_MAGICFIRE closes DoSpell before the monster's Die is processed");
      WirePacket exp = packet(output, ProtocolConstants.SM_WINEXP);
      assertEquals(50, exp.message().param(), "the kill's experience rides SM_WINEXP");
      // FixedRandom trains with the minimum gain, reported as SM_MAGIC_LVEXP.
      WirePacket training = packet(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(SKILL_KILLUNDEAD, training.message().recog());
    }
  }

  @Test
  void turnUndeadOnALivingMonsterSendsOnlyTheCastFrame() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_KILLUNDEAD))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 106);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      WorldObjectSnapshot target = spawn(world, "活物", false, 7, 5);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          target.id() & 0xffff, SKILL_KILLUNDEAD, target.id() >>> 16))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 106)));
      assertEquals(1, packets(output, ProtocolConstants.SM_MAGICFIRE).size());
      assertTrue(packets(output, ProtocolConstants.SM_DEATH).isEmpty(),
          "the LA_UNDEAD gate (Magic.pas:903) exits before Struck and before any roll");
      assertTrue(packets(output, ProtocolConstants.SM_MAGIC_LVEXP).isEmpty(),
          "boTrain stays unset, so no training frame is sent");
    }
  }

  // ------------------------------------------------------------------ helpers

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static WorldObjectSnapshot spawn(WorldEngine world, String name, boolean undead, int x, int y) {
    // AGGRESSIVE with huge intervals: the monster ticks the full AI path (so 心灵启示's expiry
    // walk reaches it) but never actually walks or attacks during a test.
    MonsterTemplate target = new MonsterTemplate(name, 0, Ability.monster(100, 1, 1, 0, 0),
        12, 600_000, 600_000, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), undead, 0, 0);
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
