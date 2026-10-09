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
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItemsDb;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldObjectSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
 * W50 wire half of 瞬息移动 ({@code SKILL_SPACEMOVE = 21}): a real {@code CM_SPELL} must produce
 * the cast frame {@code SM_MAGICFIRE(638)} carrying Magic.DB row 21's
 * {@code MakeWord(effectType = 4, effect = 19)} series, then the departure frame
 * {@code SM_SPACEMOVE_HIDE2(806)} (recog = the caster, every other word zero), the mover's
 * {@code SM_CLEAROBJECTS} + {@code SM_CHANGEMAP} pair, and finally the arrival frame
 * {@code SM_SPACEMOVE_SHOW2(807)} — recog = the actor, param = x, tag = y, series =
 * {@code MakeWord(direction, light)} and a bare {@code TCharDesc} body (Delphi only appends the
 * name block for a non-empty {@code sMsg}, ObjBase.pas:6247, and {@code SpaceMove} passes
 * {@code ''}). A failed {@code Random(11) < btLevel * 2 + 4} gate stops after the cast frame.
 */
class GameSpaceMoveProtocolTest {
  private static final int SKILL_SPACEMOVE = 21;
  /** Row 21: {@code MakeWord(btEffectType = 4, btEffect = 19)}. */
  private static final int SPACE_MOVE_SERIES = 4 | (19 << 8);
  /** Row 21 needs level 19. */
  private static final int WIZARD_LEVEL = 19;
  /**
   * A 30x30 map puts {@code MapRandomMove}'s margin at 20 and every FixedRandom draw at its
   * minimum, so the caster lands on (20, 20).
   */
  private static final Position LANDING = new Position(20, 20);

  private final AtomicLong now = new AtomicLong();

  @Test
  void castSendsTheCastFrameThenHideThenChangemapThenShow() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SPACEMOVE))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      int wizard = enter(world, adapter, id);
      output.clear();

      // CM_SPELL on empty ground: Recog = MakeLong(x, y), Param/Series = target id words (both
      // zero), Tag = MagicId. The click sits two cells to the right, so the shared cast path
      // turns the caster right before the branch runs.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_SPACEMOVE, 0))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 101)), "CM_SPELL answers +GOOD");

      // 1. Magic.pas:496 — the branch's own cast frame, ahead of everything else it does.
      WirePacket fired = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(wizard, fired.message().recog());
      assertEquals(7, fired.message().param());
      assertEquals(5, fired.message().tag());
      assertEquals(SPACE_MOVE_SERIES, fired.message().series(),
          "Magic.DB row 21: MakeWord(btEffectType = 4, btEffect = 19)");
      assertEquals(0, decodeInteger(fired.encodedBody()), "no clicked object: target id 0");

      // 2. RM_SPACEMOVE_FIRE2 -> SM_SPACEMOVE_HIDE2 (ObjBase.pas:6214): recog = the mover.
      WirePacket hidden = packet(output, ProtocolConstants.SM_SPACEMOVE_HIDE2);
      assertEquals(wizard, hidden.message().recog());
      assertEquals(0, hidden.message().param());
      assertEquals(0, hidden.message().tag());
      assertEquals(0, hidden.message().series());
      assertTrue(hidden.encodedBody().isEmpty(), "no body");

      // 3. The mover drops and reloads its scene (ObjBase.pas:4427-4428), map id in the body.
      WirePacket changed = packet(output, ProtocolConstants.SM_CHANGEMAP);
      assertEquals(wizard, changed.message().recog());
      assertEquals(LANDING.x(), changed.message().param());
      assertEquals(LANDING.y(), changed.message().tag());
      assertEquals("0", new String(SixBitCodec.decodeString(changed.encodedBody()),
          StandardCharsets.ISO_8859_1));

      // 4. RM_SPACEMOVE_SHOW2 -> SM_SPACEMOVE_SHOW2 (ObjBase.pas:6242): param = x, tag = y,
      //    series = MakeWord(direction, light), body = TCharDesc only.
      WirePacket shown = packet(output, ProtocolConstants.SM_SPACEMOVE_SHOW2);
      assertEquals(wizard, shown.message().recog());
      assertEquals(LANDING.x(), shown.message().param());
      assertEquals(LANDING.y(), shown.message().tag());
      assertEquals(Direction.RIGHT.code(), shown.message().series() & 0xff,
          "the shared cast path turned the caster toward the click before it moved");
      assertEquals(0, (shown.message().series() >>> 8) & 0xff, "no torch: m_nLight is 0");
      CharacterDescription description = CharacterDescription.decode(shown.encodedBody());
      assertEquals(0, description.feature());
      assertEquals(0, description.status());

      assertTrue(indexOf(output, ProtocolConstants.SM_MAGICFIRE)
          < indexOf(output, ProtocolConstants.SM_SPACEMOVE_HIDE2));
      assertTrue(indexOf(output, ProtocolConstants.SM_SPACEMOVE_HIDE2)
          < indexOf(output, ProtocolConstants.SM_CLEAROBJECTS));
      assertTrue(indexOf(output, ProtocolConstants.SM_CLEAROBJECTS)
          < indexOf(output, ProtocolConstants.SM_CHANGEMAP));
      assertTrue(indexOf(output, ProtocolConstants.SM_CHANGEMAP)
          < indexOf(output, ProtocolConstants.SM_SPACEMOVE_SHOW2));

      // MagSaceMove's Result := True is unconditional inside the gate.
      WirePacket training = packet(output, ProtocolConstants.SM_MAGIC_LVEXP);
      assertEquals(SKILL_SPACEMOVE, training.message().recog());
    }
  }

  @Test
  void failedGateStopsAfterTheCastFrame() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SPACEMOVE))));

    // Random(11) = 10 fails a level-0 cast (10 < 0 * 2 + 4 = 4 is false).
    try (WorldEngine world = engine(store, new ScriptedRandom(10))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 101);
      enter(world, adapter, id);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_SPACEMOVE, 0))));
      world.tickOnce();

      assertTrue(output.contains(new GameOutbound.Status(true, 101)),
          "DoSpell still answers +GOOD: boSpellFail is never raised on this branch");
      packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(-1, indexOf(output, ProtocolConstants.SM_SPACEMOVE_HIDE2));
      assertEquals(-1, indexOf(output, ProtocolConstants.SM_SPACEMOVE_SHOW2));
      assertEquals(-1, indexOf(output, ProtocolConstants.SM_CHANGEMAP));
      assertEquals(-1, indexOf(output, ProtocolConstants.SM_MAGIC_LVEXP),
          "a failed gate trains nothing");
    }
  }

  @Test
  void observersSeeTheFramesForTheirOwnSide() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_WIZARD, WIZARD_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_SPACEMOVE))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<GameOutbound> casterOutput = new ArrayList<>();
      List<GameOutbound> originOutput = new ArrayList<>();
      List<GameOutbound> landingOutput = new ArrayList<>();
      GameProtocolAdapter casterAdapter =
          new GameProtocolAdapter(world, casterOutput::add, () -> 101);
      int caster = enter(world, casterAdapter, casterId);
      GameProtocolAdapter originAdapter =
          new GameProtocolAdapter(world, originOutput::add, () -> 102);
      enter(world, originAdapter, UUID.randomUUID(), "原地目击者", new Position(6, 6));
      GameProtocolAdapter landingAdapter =
          new GameProtocolAdapter(world, landingOutput::add, () -> 103);
      enter(world, landingAdapter, UUID.randomUUID(), "落点目击者", new Position(20, 22));
      casterOutput.clear();
      originOutput.clear();
      landingOutput.clear();

      assertTrue(casterAdapter.handle(new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_SPACEMOVE, 0))));
      world.tickOnce();

      // The cell being left: the departure frame, plus the event-driven disappearance.
      WirePacket originHidden = packet(originOutput, ProtocolConstants.SM_SPACEMOVE_HIDE2);
      assertEquals(caster, originHidden.message().recog());
      assertEquals(-1, indexOf(originOutput, ProtocolConstants.SM_SPACEMOVE_SHOW2));
      assertEquals(caster, packet(originOutput, ProtocolConstants.SM_DISAPPEAR).message().recog());

      // The landing cell: the arrival frame and a fresh appearance, but never the departure.
      WirePacket landingShown = packet(landingOutput, ProtocolConstants.SM_SPACEMOVE_SHOW2);
      assertEquals(caster, landingShown.message().recog());
      assertEquals(LANDING.x(), landingShown.message().param());
      assertEquals(LANDING.y(), landingShown.message().tag());
      assertEquals(-1, indexOf(landingOutput, ProtocolConstants.SM_SPACEMOVE_HIDE2));
      assertEquals(caster, packet(landingOutput, ProtocolConstants.SM_TURN).message().recog());

      // The caster itself receives both frames.
      packet(casterOutput, ProtocolConstants.SM_SPACEMOVE_HIDE2);
      packet(casterOutput, ProtocolConstants.SM_SPACEMOVE_SHOW2);
    }
  }

  // ---------------------------------------------------------------- harness

  private int enter(WorldEngine world, GameProtocolAdapter adapter, UUID id) {
    return enter(world, adapter, id, "法师", new Position(5, 5));
  }

  private int enter(
      WorldEngine world, GameProtocolAdapter adapter, UUID id, String name, Position position) {
    var entering = world.enterPlayer(id, name, "0", position, Direction.RIGHT, 0, 0,
        LevelAbilities.JOB_WIZARD, adapter);
    world.tickOnce();
    WorldObjectSnapshot snapshot = entering.join();
    return snapshot.id();
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get, random,
        store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private static int decodeInteger(String encodedBody) {
    return ByteBuffer.wrap(SixBitCodec.decodeString(encodedBody))
        .order(ByteOrder.LITTLE_ENDIAN).getInt();
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

  /** Every draw collapses to its range minimum. */
  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  /** Pinned draws (one per {@code nextInt} call, in order), then the range minimum. */
  private static final class ScriptedRandom extends Random {
    private final int[] draws;
    private int index;

    ScriptedRandom(int... draws) {
      this.draws = draws;
    }

    @Override
    public int nextInt(int bound) {
      return index < draws.length ? draws[index++] : 0;
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
