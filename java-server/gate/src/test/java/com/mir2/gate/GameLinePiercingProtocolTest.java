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
 * W38: the RM_MAGICFIRE of the line-piercing bolts ({@code SKILL_FIRE} = 9, 地狱火;
 * {@code SKILL_SHOOTLIGHTEN} = 10, 疾光电影) carries Delphi's mutated {@code nTargetX/nTargetY}
 * — the end of the beam — with the clicked object's id only when {@code CretInNearXY} snapped
 * the click onto it (ObjBase.pas:16854 / Magic.pas:273).
 */
class GameLinePiercingProtocolTest {
  private static final int SKILL_FIRE = 9;

  private final AtomicLong now = new AtomicLong();

  @Test
  void groundCastsMagicFireCarriesTheBeamEndAndZeroTarget() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, 16), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 42);
      world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      output.clear();

      // CM_SPELL on empty ground four cells right: beam reaches the +5 cell (10, 5).
      WirePacket spell = new WirePacket(new DefaultMessage(
          11 | (5 << 16), ProtocolConstants.CM_SPELL, 0, SKILL_FIRE, 0));
      assertTrue(adapter.handle(spell));
      world.tickOnce();

      WirePacket fire = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(new Position(fire.message().param(), fire.message().tag()), new Position(10, 5),
          "SM_MAGICFIRE rides the beam end Delphi wrote back into nTargetX/nTargetY");
      // Magic.DB id 9: MakeWord(btEffectType=5, btEffect=7).
      assertEquals(5 | (7 << 8), fire.message().series());
      assertEquals(0, decodeBodyInteger(fire.encodedBody()), "no click target was named");
      assertTrue(output.contains(new GameOutbound.Status(true, 42)), "+GOOD still answers CM_SPELL");
    }
  }

  @Test
  void snappedClickNamesTheObjectInsideTheMagicFireBody() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, 16), List.of(),
        Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_FIRE))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 43);
      world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();

      MonsterTemplate dummy = new MonsterTemplate("木桩", 0,
          Ability.monster(1_000, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      var spawned = world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT);
      world.tickOnce();
      int targetId = spawned.join().id();
      output.clear();

      // The client clicked within one cell of the object it named: CretInNearXY snaps the coords
      // onto the object, DoSpell keeps it alive, so the id survives into the packet body.
      WirePacket spell = new WirePacket(new DefaultMessage(
          6 | (5 << 16), ProtocolConstants.CM_SPELL,
          targetId & 0xffff, SKILL_FIRE, (targetId >>> 16) & 0xffff));
      assertTrue(adapter.handle(spell));
      world.tickOnce();

      WirePacket fire = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(new Position(fire.message().param(), fire.message().tag()), new Position(10, 5));
      assertEquals(targetId, decodeBodyInteger(fire.encodedBody()));
    }
  }

  private static int decodeBodyInteger(String encodedBody) {
    byte[] raw = SixBitCodec.decodeString(encodedBody);
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20260930L), store, ItemDatabase.of(StdItemsDb.all()));
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
