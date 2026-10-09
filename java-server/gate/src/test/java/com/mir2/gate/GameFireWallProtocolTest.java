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
import com.mir2.world.LevelExperience;
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
 * W49 wire half of 火墙 ({@code SKILL_EARTHFIRE = 22}): a real {@code CM_SPELL} must produce the
 * {@code SM_MAGICFIRE(638)} cast frame with Magic.DB row 22's {@code MakeWord(effectType = 4,
 * effect = 20)} series, followed by five {@code SM_SHOWEVENT(804)} frames — recog = event id,
 * param = {@code ET_FIRE = 5}, tag = x, series = y, body = {@code TShortMessage{0, 0}}. A
 * monster standing in the fire takes {@code SM_STRUCK(31)} whose {@code TMessageBodyWL} carries
 * {@code lTag2 = 1} (the magic-hit marker of {@code RM_STRUCK_MAG}, ObjBase.pas:5516); a kill
 * surfaces as {@code SM_DEATH(32)} plus {@code SM_WINEXP(44)} for the caster; expiry surfaces
 * as five {@code SM_HIDEEVENT(805)} frames (recog = event id, param = 0, tag = x, series = y).
 */
class GameFireWallProtocolTest {
  private static final int SKILL_EARTHFIRE = 22;
  /** Row 22: {@code MakeWord(btEffectType = 4, btEffect = 20)}. */
  private static final int FIRE_WALL_SERIES = 4 | (20 << 8);
  /** {@code ET_FIRE = 5} (Grobal2.pas:102). */
  private static final int ET_FIRE = 5;
  /** Row 22 needs level 24. */
  private static final int WIZARD_LEVEL = 24;
  /** lv0 damage with FixedRandom at a level-24 wizard's MC 2..3: 6 per tick. */
  private static final int DAMAGE = 6;
  /** {@code TFireBurnEvent.Run}'s 3000 ms gate (Event.pas:241). */
  private static final long TICK_MILLIS = 3_000;
  /** lv0 duration: {@code GetPower(10) = 5 + (GetRPow(MC) = 2 shr 1) = 1} → 6 s. */
  private static final long DURATION_MILLIS = 6_000;

  private final AtomicLong now = new AtomicLong();

  @Test
  void castSendsMagicFireThenShowEventForEveryArm() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_EARTHFIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int wizard = entering.join().id();
      output.clear();

      // CM_SPELL on empty ground: Recog = MakeLong(x, y), Param/Series = the target id words
      // (both zero), Tag = MagicId.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          8 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_EARTHFIRE, 0))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 101)), "CM_SPELL answers +GOOD");
      WirePacket fired = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(wizard, fired.message().recog());
      assertEquals(8, fired.message().param());
      assertEquals(5, fired.message().tag());
      assertEquals(FIRE_WALL_SERIES, fired.message().series(),
          "Magic.DB row 22: MakeWord(btEffectType = 4, btEffect = 20)");
      assertEquals(0, decodeInteger(fired.encodedBody()), "no clicked object: target id 0");

      List<WirePacket> shown = packets(output, ProtocolConstants.SM_SHOWEVENT);
      assertEquals(5, shown.size(), "the whole cross is reported");
      List<Position> cells = new ArrayList<>();
      for (WirePacket show : shown) {
        assertTrue(show.message().recog() > 0, "recog is the event id");
        assertEquals(ET_FIRE, show.message().param(), "param is the event type");
        cells.add(new Position(show.message().tag(), show.message().series()));
        int[] shortMessage = decodeShortMessage(show.encodedBody());
        assertEquals(0, shortMessage[0], "TShortMessage.Ident = m_nEventParam = 0");
        assertEquals(0, shortMessage[1], "TShortMessage.wMsg = 0");
      }
      assertEquals(List.of(new Position(8, 4), new Position(7, 5), new Position(8, 5),
          new Position(9, 5), new Position(8, 6)), cells,
          "MagMakeFireCross's arm order (Magic.pas:1148-1164)");
      assertTrue(indexOf(output, ProtocolConstants.SM_MAGICFIRE)
          < indexOf(output, ProtocolConstants.SM_SHOWEVENT),
          "Delphi's SearchViewRange reports the flames after the cast frame");
      // The unconditional Result := 1 trains the skill.
      WirePacket training = packet(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(SKILL_EARTHFIRE, training.message().recog());
    }
  }

  @Test
  void fireBurnSendsStruckWithTheMagicHitMarker() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_EARTHFIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int wizard = entering.join().id();
      WorldObjectSnapshot statue = spawn(world, "烧烤架", 100, 7, 5);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_EARTHFIRE, 0))));
      world.tickOnce();
      output.clear();

      now.addAndGet(TICK_MILLIS + 1);
      world.tickOnce();

      WirePacket struck = packet(output, ProtocolConstants.SM_STRUCK);
      assertEquals(statue.id(), struck.message().recog(), "recog = the victim");
      assertEquals(100 - DAMAGE, struck.message().param(), "param = the victim's HP");
      assertEquals(100, struck.message().tag(), "tag = MaxHP");
      assertEquals(DAMAGE, struck.message().series(), "series = the damage");
      int[] body = decodeMessageBodyWl(struck.encodedBody());
      assertEquals(statue.feature(), body[0], "lParam1 = the victim's feature");
      assertEquals(statue.status(), body[1], "lParam2 = the victim's char status");
      assertEquals(wizard, body[2], "lTag1 = the attacker (the fire's owner)");
      assertEquals(1, body[3], "lTag2 = 1: RM_STRUCK_MAG's magic-hit marker (ObjBase.pas:5516)");
    }
  }

  @Test
  void fireKillSendsDeathThenExperienceForTheCaster() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_EARTHFIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      WorldObjectSnapshot prey = spawn(world, "残血怪", 5, 8, 5);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          8 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_EARTHFIRE, 0))));
      world.tickOnce();
      output.clear();

      now.addAndGet(TICK_MILLIS + 1);
      world.tickOnce();

      WirePacket death = packet(output, ProtocolConstants.SM_DEATH);
      assertEquals(prey.id(), death.message().recog());
      WirePacket experience = packet(output, ProtocolConstants.SM_WINEXP);
      assertEquals(50, experience.message().param(), "the template's experience to the owner");
      assertTrue(indexOf(output, ProtocolConstants.SM_STRUCK)
          < indexOf(output, ProtocolConstants.SM_DEATH), "struck precedes death");
    }
  }

  @Test
  void fireExpirySendsHideEventForEveryArm() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_EARTHFIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      var entering = world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          8 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_EARTHFIRE, 0))));
      world.tickOnce();
      List<WirePacket> shown = packets(output, ProtocolConstants.SM_SHOWEVENT);
      assertEquals(5, shown.size());
      output.clear();

      // Strictly past the 6000 ms duration the fire closes (Event.pas:278).
      now.addAndGet(DURATION_MILLIS + 1);
      world.tickOnce();

      List<WirePacket> hidden = packets(output, ProtocolConstants.SM_HIDEEVENT);
      assertEquals(5, hidden.size(), "every arm is hidden");
      List<Integer> shownIds = shown.stream().map(packet -> packet.message().recog()).toList();
      for (WirePacket hide : hidden) {
        assertTrue(shownIds.contains(hide.message().recog()),
            "SM_HIDEEVENT names the same event id SM_SHOWEVENT introduced");
        assertEquals(0, hide.message().param());
        assertTrue(hide.message().tag() >= 7 && hide.message().tag() <= 9);
        assertTrue(hide.message().series() >= 4 && hide.message().series() <= 6);
        assertEquals("", hide.encodedBody(), "SM_HIDEEVENT carries no body");
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  /**
   * A monster with a flat MAC 0..0 (so the fire's MAC roll absorbs nothing) whose walk/attack
   * intervals are so large it never acts during a test.
   */
  private static WorldObjectSnapshot spawn(WorldEngine world, String name, int hp, int x, int y) {
    MonsterTemplate target = new MonsterTemplate(name, 0, new Ability(
        hp, hp, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, LevelExperience.forLevel(1)),
        12, 600_000, 600_000, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), false, 0, 0);
    var pending = world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN);
    world.tickOnce();
    return pending.join();
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private static int decodeInteger(String encodedBody) {
    byte[] raw = SixBitCodec.decodeString(encodedBody);
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  /** {@code TShortMessage} (Grobal2.pas:700): {@code [Ident, wMsg]} as two little-endian words. */
  private static int[] decodeShortMessage(String encodedBody) {
    ByteBuffer buffer = ByteBuffer.wrap(SixBitCodec.decodeString(encodedBody))
        .order(ByteOrder.LITTLE_ENDIAN);
    return new int[] {buffer.getShort() & 0xffff, buffer.getShort() & 0xffff};
  }

  /** {@code TMessageBodyWL} (Grobal2.pas:712): four little-endian dwords. */
  private static int[] decodeMessageBodyWl(String encodedBody) {
    ByteBuffer buffer = ByteBuffer.wrap(SixBitCodec.decodeString(encodedBody))
        .order(ByteOrder.LITTLE_ENDIAN);
    return new int[] {buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt()};
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
    public Optional<PlayerState> load(UUID id) {
      return Optional.ofNullable(states.get(id));
    }

    @Override
    public void save(PlayerState state) {
      states.put(state.characterId(), state);
    }
  }
}
