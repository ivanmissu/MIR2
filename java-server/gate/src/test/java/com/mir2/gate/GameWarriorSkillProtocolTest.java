package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Ability;
import com.mir2.world.AttackKind;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.HitSpeed;
import com.mir2.world.ItemDatabase;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import com.mir2.world.WorldObjectType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W33 wire half of the 战士武器技 batch: {@code CM_POWERHIT} in, {@code SM_SPELL2} and the
 * {@code '+PWR'} tag frame out, plus the {@code SM_SUBABILITY} that finally carries 准确/敏捷
 * to the character panel.
 */
class GameWarriorSkillProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void clientPowerHitIsAcceptedAndBroadcastAsSpell2OnlyWhileArmed() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();

      List<GameOutbound> observed = new ArrayList<>();
      GameProtocolAdapter observer = new GameProtocolAdapter(world, observed::add, () -> 77);
      var watching = world.enterPlayer("观察者", "0", new Position(5, 8), Direction.UP, observer);
      world.tickOnce();
      watching.join();
      observed.clear();

      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 77);
      sink.set(adapter);

      // CM_POWERHIT (3018) now reaches the world; with no 攻杀剑术 armed, AttackDir keeps the
      // broadcast at RM_HIT (ObjBase.pas:18847).
      now.addAndGet(5_000);
      assertTrue(adapter.handle(action(ProtocolConstants.CM_POWERHIT, 5, 5, Direction.RIGHT)));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(true, 77), output.remove(0));
      WirePacket swing = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_HIT, swing.message().ident());
      assertEquals(playerId, swing.message().recog());

      // The armed case is exercised through the event the world would emit for it.
      observed.clear();
      WorldObjectSnapshot attacker = new WorldObjectSnapshot(playerId, "战士",
          WorldObjectType.PLAYER, "0", new Position(5, 5), Direction.RIGHT);
      observer.send(new WorldEvent.ObjectAttacked(attacker, AttackKind.POWER_HIT));
      WirePacket spell2 = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_SPELL2, spell2.message().ident());
      assertEquals(playerId, spell2.message().recog());
      assertEquals(5, spell2.message().param());
      assertEquals(5, spell2.message().tag());
      assertEquals(Direction.RIGHT.code(), spell2.message().series());
    }
  }

  @Test
  void powerHitReadyBecomesTheRawPwrTagFrame() throws Exception {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 9, output::add, () -> 77);

      adapter.send(new WorldEvent.PowerHitReady(9));
      assertEquals(GameOutbound.Signal.POWER_HIT, output.remove(0));
      // Another player's cadence must not leak into this session.
      adapter.send(new WorldEvent.PowerHitReady(10));
      assertTrue(output.isEmpty());

      // SendSocket(nil, '+PWR') is a bare tag frame: no '/tick' suffix, unlike +GOOD/+FAIL.
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      WireMessageCodec.writeGameOutbound(bytes, GameOutbound.Signal.POWER_HIT);
      assertEquals("#+PWR!", bytes.toString(StandardCharsets.US_ASCII));
    }
  }

  @Test
  void subAbilityPacksAccuracyAndAgilityIntoTheParamWord() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 9, output::add, () -> 77);

      adapter.send(new WorldEvent.SubAbilityChanged(9, 3, 14, 18, 4, 5, 6, 7));
      WirePacket packet = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_SUBABILITY, packet.message().ident());
      assertEquals(3, packet.message().recog(), "MakeLong(MakeWord(m_nAntiMagic, 0), 0)");
      assertEquals(14, packet.message().param() & 0xff, "准确 in the low byte");
      assertEquals(18, (packet.message().param() >>> 8) & 0xff, "敏捷 in the high byte");
      assertEquals(4, packet.message().tag() & 0xff);
      assertEquals(5, (packet.message().tag() >>> 8) & 0xff);
      assertEquals(6, packet.message().series() & 0xff);
      assertEquals(7, (packet.message().series() >>> 8) & 0xff);
      assertEquals("", packet.encodedBody());

      // Another player's refresh is dropped, like every other player-scoped event.
      adapter.send(new WorldEvent.SubAbilityChanged(10, 0, 5, 15, 0, 0, 0, 0));
      assertTrue(output.isEmpty());
    }
  }

  @Test
  void theDefaultCharacterReportsDefhitAndDefspeed() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, output::add, () -> 77);
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, adapter);
      world.tickOnce();
      entered.join();

      WirePacket subAbility = output.stream()
          .filter(GameOutbound.Packet.class::isInstance)
          .map(outbound -> ((GameOutbound.Packet) outbound).packet())
          .filter(packet -> packet.message().ident() == ProtocolConstants.SM_SUBABILITY)
          .findFirst()
          .orElseThrow(() -> new AssertionError("login must carry SM_SUBABILITY"));
      assertEquals(HitSpeed.DEF_HIT, subAbility.message().param() & 0xff);
      assertEquals(HitSpeed.DEF_SPEED, (subAbility.message().param() >>> 8) & 0xff);
      assertFalse(MonsterTemplate.orc().hitPoint() == 0,
          "the dodge model needs the Monster.DB HIT column wired through");
      assertNotNull(Ability.defaultPlayer());
    }
  }

  private WorldEngine engine(GameMap map) {
    return new WorldEngine(new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000),
        List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }

  private static WirePacket action(int ident, int x, int y, Direction direction) {
    return new WirePacket(new DefaultMessage((y << 16) | x, ident, 0, direction.code(), 0));
  }
}
