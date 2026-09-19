package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Ability;
import com.mir2.world.AttackKind;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.GroundItem;
import com.mir2.world.ItemDatabase;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.StdItems;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import com.mir2.world.WorldObjectType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Maps the combat/pickup half of the W03 slice onto the legacy CM_/SM_ wire messages. */
class GameCombatProtocolTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void clientHitVariantsReachTheWorldAndAreBroadcastToObserversOnly() {
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

      now.addAndGet(5_000);
      assertTrue(adapter.handle(action(ProtocolConstants.CM_HIT, 5, 5, Direction.RIGHT)));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(true, 77), output.remove(0));
      // ObjBase.pas suppresses RM_HIT towards the attacker itself, so only observers see the swing.
      assertTrue(output.isEmpty());
      WirePacket hit = ((GameOutbound.Packet) observed.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_HIT, hit.message().ident());
      assertEquals(playerId, hit.message().recog());
      assertEquals(5, hit.message().param());
      assertEquals(5, hit.message().tag());
      assertEquals(Direction.RIGHT.code(), hit.message().series());

      now.addAndGet(5_000);
      assertTrue(adapter.handle(action(ProtocolConstants.CM_HEAVYHIT, 5, 5, Direction.RIGHT)));
      world.tickOnce();
      output.remove(0);
      assertEquals(ProtocolConstants.SM_HEAVYHIT,
          ((GameOutbound.Packet) observed.remove(0)).packet().message().ident());

      // A swing inside the hit interval must release the client action lock with +FAIL.
      assertTrue(adapter.handle(action(ProtocolConstants.CM_HIT, 5, 5, Direction.RIGHT)));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(false, 77), output.remove(0));
      assertTrue(output.isEmpty());
      assertTrue(observed.isEmpty());
    }
  }

  @Test
  void killingAMonsterEmitsStruckHealthDeathExperienceAndItemPackets() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();
      var spawned = world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT);
      world.tickOnce();
      int monsterId = spawned.join().id();

      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 5);
      sink.set(adapter);

      for (int swing = 0; swing < 20; swing++) {
        now.addAndGet(1_000);
        adapter.handle(action(ProtocolConstants.CM_HIT, 5, 5, Direction.RIGHT));
        world.tickOnce();
        if (idents(output).contains(ProtocolConstants.SM_DEATH)) break;
      }

      WirePacket struck = firstPacket(output, ProtocolConstants.SM_STRUCK);
      assertEquals(monsterId, struck.message().recog());
      assertEquals(MonsterTemplate.chicken().ability().maxHp(), struck.message().tag());
      assertEquals(16, SixBitCodec.decodeString(struck.encodedBody()).length);

      WirePacket health = firstPacket(output, ProtocolConstants.SM_HEALTHSPELLCHANGED);
      assertEquals(monsterId, health.message().recog());

      WirePacket death = firstPacket(output, ProtocolConstants.SM_DEATH);
      assertEquals(monsterId, death.message().recog());
      assertEquals(6, death.message().param());
      assertEquals(5, death.message().tag());
      assertEquals(new CharacterDescription(MonsterTemplate.chicken().feature(), 0),
          CharacterDescription.decode(death.encodedBody()));

      WirePacket experience = firstPacket(output, ProtocolConstants.SM_WINEXP);
      assertEquals(MonsterTemplate.chicken().experience(), experience.message().recog());
      assertEquals(MonsterTemplate.chicken().experience(), experience.message().param());

      WirePacket item = firstPacket(output, ProtocolConstants.SM_ITEMSHOW);
      assertEquals("鸡肉", WireMessageCodec.decodeBody(item.encodedBody()));
      assertEquals(6, item.message().param());
      assertEquals(5, item.message().tag());
      assertEquals(41, item.message().series());
    }
  }

  @Test
  void pickupUsesTheClientCellAndAcknowledgesWithAddItemThenItemHide() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();
      world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT);
      world.tickOnce();

      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 3);
      sink.set(adapter);
      for (int swing = 0; swing < 20; swing++) {
        now.addAndGet(1_000);
        adapter.handle(action(ProtocolConstants.CM_HIT, 5, 5, Direction.RIGHT));
        world.tickOnce();
        if (idents(output).contains(ProtocolConstants.SM_ITEMSHOW)) break;
      }
      int itemId = firstPacket(output, ProtocolConstants.SM_ITEMSHOW).message().recog();

      // Pickup from the wrong cell must fail without inventing an item.
      output.clear();
      assertTrue(adapter.handle(pickup(5, 5)));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(false, 3), output.remove(0));

      now.addAndGet(10_000);
      world.tickOnce();
      output.clear();
      adapter.handle(action(ProtocolConstants.CM_WALK, 6, 5, Direction.RIGHT));
      world.tickOnce();
      output.clear();

      assertTrue(adapter.handle(pickup(6, 5)));
      world.tickOnce();
      assertEquals(new GameOutbound.Status(true, 3), output.remove(0));
      WirePacket added = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_ADDITEM, added.message().ident());
      assertEquals(playerId, added.message().recog());
      assertEquals(1, added.message().series(), "SM_ADDITEM carries one item");
      // The body is the full 76-byte TClientItem, as SendAddItem sends it.
      BackpackItem bagEntry = ClientItemCodec.decode(added.encodedBody());
      assertEquals("鸡肉", bagEntry.name());
      assertEquals(StdItems.chickenMeat(), bagEntry.item());
      assertTrue(bagEntry.makeIndex() > 0);
      assertEquals(bagEntry.dura(), bagEntry.duraMax());
      WirePacket hidden = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_ITEMHIDE, hidden.message().ident());
      assertEquals(itemId, hidden.message().recog());
    }
  }

  @Test
  void queryBagItemsRepliesWithSmBagItemsAndStaysSilentOnEmptyBags() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      AtomicReference<WorldEventSink> sink = new AtomicReference<>(ignored -> {});
      var entered = world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT,
          event -> sink.get().send(event));
      world.tickOnce();
      int playerId = entered.join().id();
      world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT);
      world.tickOnce();

      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, playerId, output::add, () -> 3);
      sink.set(adapter);

      // An empty bag gets no SM_BAGITEMS at all, exactly like ObjBase.pas ClientQueryBagItems.
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          0, ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0))));
      world.tickOnce();
      assertFalse(idents(output).contains(ProtocolConstants.SM_BAGITEMS));

      // Kill the chicken, pick up the meat, then re-query the bag.
      for (int swing = 0; swing < 20; swing++) {
        now.addAndGet(1_000);
        adapter.handle(action(ProtocolConstants.CM_HIT, 5, 5, Direction.RIGHT));
        world.tickOnce();
        if (idents(output).contains(ProtocolConstants.SM_ITEMSHOW)) break;
      }
      now.addAndGet(10_000);
      world.tickOnce();
      output.clear();
      adapter.handle(action(ProtocolConstants.CM_WALK, 6, 5, Direction.RIGHT));
      world.tickOnce();
      output.clear();
      adapter.handle(pickup(6, 5));
      world.tickOnce();
      BackpackItem pickedUp = ClientItemCodec.decode(
          firstPacket(output, ProtocolConstants.SM_ADDITEM).encodedBody());

      // The client sends CM_QUERYBAGITEMS right after SM_LOGON (ClMain.pas:3860).
      output.clear();
      assertTrue(adapter.handle(new WirePacket(new DefaultMessage(
          0, ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0))));
      world.tickOnce();
      WirePacket bag = firstPacket(output, ProtocolConstants.SM_BAGITEMS);
      assertEquals(playerId, bag.message().recog());
      assertEquals(0, bag.message().param());
      assertEquals(0, bag.message().tag());
      assertEquals(1, bag.message().series(), "the series field carries the bag size");
      assertEquals(ClientItemCodec.encodeBag(List.of(pickedUp)), bag.encodedBody());
      assertTrue(bag.encodedBody().endsWith("/"), "Delphi terminates every entry with '/'");
    }
  }

  @Test
  void observedMonsterEventsBecomeLegacyPacketsWithoutAWorldRoundTrip() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      List<GameOutbound> output = new ArrayList<>();
      GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 1);
      WorldObjectSnapshot monster = new WorldObjectSnapshot(9, "鸡", WorldObjectType.MONSTER,
          "0", new Position(3, 4), Direction.UP, MonsterTemplate.chicken().feature(), 0,
          Ability.monster(6, 1, 2, 0, 0).withHp(2));

      adapter.send(new WorldEvent.ObjectAttacked(monster, AttackKind.BIG_HIT));
      WirePacket swing = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_BIGHIT, swing.message().ident());
      assertEquals(9, swing.message().recog());

      adapter.send(new WorldEvent.ObjectStruck(monster, 1, 4));
      WirePacket struck = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_STRUCK, struck.message().ident());
      assertEquals(2, struck.message().param());
      assertEquals(6, struck.message().tag());
      assertEquals(4, struck.message().series());

      adapter.send(new WorldEvent.ItemAppeared(new GroundItem(12, "金创药", 40, "0", new Position(7, 8))));
      WirePacket shown = ((GameOutbound.Packet) output.remove(0)).packet();
      assertEquals(ProtocolConstants.SM_ITEMSHOW, shown.message().ident());
      assertEquals(12, shown.message().recog());
      assertEquals("金创药", WireMessageCodec.decodeBody(shown.encodedBody()));

      adapter.send(new WorldEvent.ItemDisappeared(new GroundItem(12, "金创药", 40, "0", new Position(7, 8))));
      assertEquals(ProtocolConstants.SM_ITEMHIDE,
          ((GameOutbound.Packet) output.remove(0)).packet().message().ident());
      assertTrue(output.isEmpty());
    }
  }

  private WorldEngine engine(GameMap map) {
    return new WorldEngine(new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000),
        List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }

  private static WirePacket firstPacket(List<GameOutbound> output, int ident) {
    return output.stream()
        .filter(GameOutbound.Packet.class::isInstance)
        .map(outbound -> ((GameOutbound.Packet) outbound).packet())
        .filter(packet -> packet.message().ident() == ident)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no packet with ident " + ident + " in " + idents(output)));
  }

  private static List<Integer> idents(List<GameOutbound> output) {
    return output.stream()
        .filter(GameOutbound.Packet.class::isInstance)
        .map(outbound -> ((GameOutbound.Packet) outbound).packet().message().ident())
        .toList();
  }

  private static WirePacket action(int ident, int x, int y, Direction direction) {
    return new WirePacket(new DefaultMessage((y << 16) | x, ident, 0, direction.code(), 0));
  }

  private static WirePacket pickup(int x, int y) {
    return new WirePacket(new DefaultMessage(0, ProtocolConstants.CM_PICKUP, x, y, 0));
  }
}
