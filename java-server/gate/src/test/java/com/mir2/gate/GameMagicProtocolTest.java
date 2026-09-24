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

class GameMagicProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void loginSpellKeyChangeAndFireballUseTheClassicWireLayouts() {
    Store store = new Store();
    UUID characterId = UUID.randomUUID();
    Ability ability = LevelAbilities.forLevel(
        LevelAbilities.JOB_WIZARD, 7, Ability.defaultPlayer()).restored();
    store.save(new PlayerState(characterId, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(1))));

    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 77);
      var entered = world.enterPlayer(characterId, "法师", "0", new Position(5, 5),
          Direction.RIGHT, 0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      int playerId = entered.join().id();

      WirePacket skills = packet(output, ProtocolConstants.SM_SENDMYMAGIC);
      assertEquals(1, skills.message().series());
      byte[] learned = SixBitCodec.decode(skills.encodedBody().substring(
          0, skills.encodedBody().length() - 1).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
      assertEquals(MagicCodec.CLIENT_MAGIC_SIZE, learned.length);
      assertEquals(1, Short.toUnsignedInt(ByteBuffer.wrap(learned).order(ByteOrder.LITTLE_ENDIAN)
          .getShort(8)));
      output.clear();

      MonsterTemplate dummy = new MonsterTemplate("木桩", 0,
          Ability.monster(100, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      var spawned = world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT);
      world.tickOnce();
      int targetId = spawned.join().id();
      output.clear();

      WirePacket spell = new WirePacket(new DefaultMessage(
          7 | (5 << 16), ProtocolConstants.CM_SPELL,
          targetId & 0xffff, 1, (targetId >>> 16) & 0xffff));
      assertTrue(adapter.handle(spell));
      world.tickOnce();
      assertTrue(output.contains(new GameOutbound.Status(true, 77)));
      WirePacket fired = packet(output, ProtocolConstants.SM_MAGICFIRE);
      assertEquals(playerId, fired.message().recog());
      assertEquals(7, fired.message().param());
      assertEquals(5, fired.message().tag());
      assertEquals((1 << 8) | 1, fired.message().series());
      assertEquals(targetId, ByteBuffer.wrap(SixBitCodec.decodeString(fired.encodedBody()))
          .order(ByteOrder.LITTLE_ENDIAN).getInt());

      output.clear();
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          1, ProtocolConstants.CM_MAGICKEYCHANGE, 'F', 0, 0))));
      world.tickOnce();
      var currentSkills = world.skills(playerId);
      world.tickOnce();
      assertEquals('F', currentSkills.join().getFirst().key());
      assertTrue(output.isEmpty(), "CM_MAGICKEYCHANGE has no direct response");
    }
  }

  @Test
  void rejectedSpellSendsMagicFireFailSystemMessageAndCommandFail() {
    Store store = new Store();
    UUID id = UUID.randomUUID();
    Ability ability = LevelAbilities.forLevel(
        LevelAbilities.JOB_WIZARD, 7, Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(1))));
    try (WorldEngine world = engine(store)) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 88);
      world.enterPlayer(id, "法师", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WIZARD, adapter);
      world.tickOnce();
      output.clear();

      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          6 | (5 << 16), ProtocolConstants.CM_SPELL, 9999, 1, 0))));
      world.tickOnce();
      assertEquals(ProtocolConstants.SM_MAGICFIRE_FAIL,
          packet(output, ProtocolConstants.SM_MAGICFIRE_FAIL).message().ident());
      assertEquals("施法目标无效", WireMessageCodec.decodeBody(
          packet(output, ProtocolConstants.SM_SYSMESSAGE).encodedBody()));
      assertTrue(output.contains(new GameOutbound.Status(false, 88)));
    }
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(7), store, ItemDatabase.of(StdItemsDb.all()));
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
