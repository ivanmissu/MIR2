package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.AttackKind;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.HitSpeed;
import com.mir2.world.ItemDatabase;
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

/**
 * W34 wire half of the 刺杀剑术/半月弯刀 batch: {@code CM_LONGHIT}/{@code CM_WIDEHIT} in,
 * {@code SM_LONGHIT}/{@code SM_WIDEHIT} out, and the four {@code ThrustingOnOff}/{@code
 * HalfMoonOnOff} tag frames (+LNG/+ULNG/+WID/+UWID) that switch which packet the client sends
 * (ObjBase.pas:9043-9073).
 */
class GameSpecialAttackProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void weaponSkillToggledBecomesTheMatchingTagFrame() throws Exception {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 9, output::add, () -> 77);

      adapter.send(new WorldEvent.WeaponSkillToggled(9, HitSpeed.SKILL_ERGUM, true));
      assertEquals(GameOutbound.Signal.THRUSTING_ON, output.remove(0));
      adapter.send(new WorldEvent.WeaponSkillToggled(9, HitSpeed.SKILL_ERGUM, false));
      assertEquals(GameOutbound.Signal.THRUSTING_OFF, output.remove(0));
      adapter.send(new WorldEvent.WeaponSkillToggled(9, HitSpeed.SKILL_BANWOL, true));
      assertEquals(GameOutbound.Signal.HALF_MOON_ON, output.remove(0));
      adapter.send(new WorldEvent.WeaponSkillToggled(9, HitSpeed.SKILL_BANWOL, false));
      assertEquals(GameOutbound.Signal.HALF_MOON_OFF, output.remove(0));

      // Another player's toggle must never leak into this session.
      adapter.send(new WorldEvent.WeaponSkillToggled(10, HitSpeed.SKILL_ERGUM, true));
      assertTrue(output.isEmpty());

      // Each is a bare tag frame like '+PWR': '#' + tag + '!', no '/tick' suffix.
      assertEquals("#+LNG!", encode(GameOutbound.Signal.THRUSTING_ON));
      assertEquals("#+ULNG!", encode(GameOutbound.Signal.THRUSTING_OFF));
      assertEquals("#+WID!", encode(GameOutbound.Signal.HALF_MOON_ON));
      assertEquals("#+UWID!", encode(GameOutbound.Signal.HALF_MOON_OFF));
    }
  }

  @Test
  void clientLongAndWideHitReachTheWorldAndDegradeToHitForAnUnlearnedActor() {
    for (int ident : new int[] {ProtocolConstants.CM_LONGHIT, ProtocolConstants.CM_WIDEHIT}) {
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

        // The gate accepts the new idents and forwards them; without the book learned, AttackDir
        // keeps the broadcast at RM_HIT (ObjBase.pas:18790/18845).
        now.addAndGet(5_000);
        assertTrue(adapter.handle(action(ident, 5, 5, Direction.RIGHT)));
        world.tickOnce();
        assertEquals(new GameOutbound.Status(true, 77), output.remove(0));
        WirePacket swing = ((GameOutbound.Packet) observed.remove(0)).packet();
        assertEquals(ProtocolConstants.SM_HIT, swing.message().ident());
        assertEquals(playerId, swing.message().recog());
      }
    }
  }

  @Test
  void longAndWideHitBroadcastsMapToTheirOwnIdents() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> observed = new ArrayList<>();
      GameProtocolAdapter observer = new GameProtocolAdapter(world, observed::add, () -> 77);

      WorldObjectSnapshot attacker = new WorldObjectSnapshot(7, "战士",
          WorldObjectType.PLAYER, "0", new Position(6, 4), Direction.RIGHT);

      observer.send(new WorldEvent.ObjectAttacked(attacker, AttackKind.LONG_HIT));
      WirePacket longHit = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_LONGHIT, longHit.message().ident());
      assertEquals(7, longHit.message().recog());
      assertEquals(6, longHit.message().param());
      assertEquals(4, longHit.message().tag());
      assertEquals(Direction.RIGHT.code(), longHit.message().series());

      observer.send(new WorldEvent.ObjectAttacked(attacker, AttackKind.WIDE_HIT));
      WirePacket wideHit = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_WIDEHIT, wideHit.message().ident());
      assertEquals(7, wideHit.message().recog());
    }
  }

  private static String encode(GameOutbound.Signal signal) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    WireMessageCodec.writeGameOutbound(bytes, signal);
    return bytes.toString(StandardCharsets.US_ASCII);
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
