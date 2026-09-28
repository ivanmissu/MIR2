package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.protocol.CharacterDescription;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.HitSpeed;
import com.mir2.world.ItemDatabase;
import com.mir2.world.PlayerSkill;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldObjectSnapshot;
import com.mir2.world.WorldObjectType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * W36 wire half of the 野蛮冲撞 batch ({@code SKILL_MOOTEBO = 27}):
 * {@code SM_RUSH} (6), {@code SM_RUSHKUNG} (7), and {@code SM_BACKSTEP} (9).
 */
class GameMotaeboProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void objectRushedEmitsSmRushWithCorrectWireLayout() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> observed = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, observed::add, () -> 77);

      WorldObjectSnapshot rusher = new WorldObjectSnapshot(12, "冲撞勇士",
          WorldObjectType.PLAYER, "0", new Position(8, 5), Direction.RIGHT, 101, 0, 3);

      adapter.send(new WorldEvent.ObjectRushed(rusher, new Position(7, 5), Direction.RIGHT));
      WirePacket packet = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_RUSH, packet.message().ident());
      assertEquals(12, packet.message().recog());
      assertEquals(8, packet.message().param());
      assertEquals(5, packet.message().tag());
      // Series = MakeWord(direction, light)
      int expectedSeries = Direction.RIGHT.code() | (3 << 8);
      assertEquals(expectedSeries, packet.message().series());
      assertEquals(new CharacterDescription(101, 0).encode(), packet.encodedBody());
    }
  }

  @Test
  void objectPushedEmitsSmBackstepWithCorrectWireLayout() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> observed = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, observed::add, () -> 77);

      WorldObjectSnapshot pushed = new WorldObjectSnapshot(25, "受击怪物",
          WorldObjectType.MONSTER, "0", new Position(9, 5), Direction.LEFT, 50, 0, 0);

      adapter.send(new WorldEvent.ObjectPushed(pushed, new Position(8, 5), Direction.LEFT));
      WirePacket packet = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_BACKSTEP, packet.message().ident());
      assertEquals(25, packet.message().recog());
      assertEquals(9, packet.message().param());
      assertEquals(5, packet.message().tag());
      int expectedSeries = Direction.LEFT.code() | (0 << 8);
      assertEquals(expectedSeries, packet.message().series());
      assertEquals(new CharacterDescription(50, 0).encode(), packet.encodedBody());
    }
  }

  @Test
  void objectRushFailedEmitsSmRushkungWithTargetCell() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> observed = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, observed::add, () -> 77);

      WorldObjectSnapshot rusher = new WorldObjectSnapshot(12, "冲撞勇士",
          WorldObjectType.PLAYER, "0", new Position(5, 5), Direction.RIGHT, 101, 0, 3);

      adapter.send(new WorldEvent.ObjectRushFailed(rusher, new Position(6, 5), Direction.RIGHT));
      WirePacket packet = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_RUSHKUNG, packet.message().ident());
      assertEquals(12, packet.message().recog());
      // param/tag carry the front obstacle cell where the rush failed (ObjBase.pas:21803)
      assertEquals(6, packet.message().param());
      assertEquals(5, packet.message().tag());
      int expectedSeries = Direction.RIGHT.code() | (3 << 8);
      assertEquals(expectedSeries, packet.message().series());
      assertEquals(new CharacterDescription(101, 0).encode(), packet.encodedBody());
    }
  }

  private WorldEngine engine(GameMap map) {
    return new WorldEngine(new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000),
        List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }
}
