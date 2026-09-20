package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates W11 Day/Night cycle, MapFlags, and Chat / Whisper / Shout / System message routing.
 */
class WorldChatAndDayCycleTest {

  @Test
  void gameTimeFromHourMatchesDelphiGetGameTimeMapping() {
    // 5..10, 16..22 -> 1 (daytime)
    assertEquals(1, WorldEngine.gameTimeFromHour(5));
    assertEquals(1, WorldEngine.gameTimeFromHour(8));
    assertEquals(1, WorldEngine.gameTimeFromHour(10));
    assertEquals(1, WorldEngine.gameTimeFromHour(16));
    assertEquals(1, WorldEngine.gameTimeFromHour(20));
    assertEquals(1, WorldEngine.gameTimeFromHour(22));

    // 11, 23 -> 2 (twilight)
    assertEquals(2, WorldEngine.gameTimeFromHour(11));
    assertEquals(2, WorldEngine.gameTimeFromHour(23));

    // 4, 15 -> 0 (dawn / transition)
    assertEquals(0, WorldEngine.gameTimeFromHour(4));
    assertEquals(0, WorldEngine.gameTimeFromHour(15));

    // 0..3, 12..14 -> 3 (night)
    assertEquals(3, WorldEngine.gameTimeFromHour(0));
    assertEquals(3, WorldEngine.gameTimeFromHour(1));
    assertEquals(3, WorldEngine.gameTimeFromHour(3));
    assertEquals(3, WorldEngine.gameTimeFromHour(12));
    assertEquals(3, WorldEngine.gameTimeFromHour(14));
  }

  @Test
  void dayBrightHonoursMapFlagsAndGameTime() {
    MapFlags normal = MapFlags.DEFAULT;
    MapFlags dark = new MapFlags(false, true, false, false, false, false, false, "", false, true, false, -1, -1);
    MapFlags day = new MapFlags(false, false, true, false, false, false, false, "", false, true, false, -1, -1);

    // Normal map: 1 -> 0 (bright), 3 -> 1 (dark), 0/2 -> 2 (twilight)
    assertEquals(0, WorldEngine.calculateDayBright(normal, 1));
    assertEquals(1, WorldEngine.calculateDayBright(normal, 3));
    assertEquals(2, WorldEngine.calculateDayBright(normal, 0));
    assertEquals(2, WorldEngine.calculateDayBright(normal, 2));

    // DARK map flag: always dark 1 regardless of time
    assertEquals(1, WorldEngine.calculateDayBright(dark, 1));
    assertEquals(1, WorldEngine.calculateDayBright(dark, 3));
    assertEquals(1, WorldEngine.calculateDayBright(dark, 0));

    // DAY map flag: always bright 0 regardless of time
    assertEquals(0, WorldEngine.calculateDayBright(day, 3));
    assertEquals(0, WorldEngine.calculateDayBright(day, 2));
  }

  @Test
  void gameTimeChangesBroadcastDayChangingToOnlinePlayers() {
    GameMap map0 = GameMap.empty("0", "比奇省", 50, 50);
    AtomicInteger hour = new AtomicInteger(8); // gameTime = 1 (day)
    try (WorldEngine world = new WorldEngine(WorldEngine.Config.defaults(), List.of(map0),
        () -> 1000L, new Random(1), PlayerStateStore.none(), ItemDatabase.empty(), hour::get)) {

      List<WorldEvent> aliceEvents = new ArrayList<>();
      var entered = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      world.tickOnce();
      int aliceId = entered.join().id();

      assertEquals(1, world.gameTime());
      assertEquals(0, world.dayBright(map0));

      // MapEntered carries dayBright=0
      WorldEvent.MapEntered mapEntered = (WorldEvent.MapEntered) aliceEvents.getFirst();
      assertEquals(0, mapEntered.dayBright());

      aliceEvents.clear();

      // Advance hour to midnight (hour 0 -> gameTime 3 -> dayBright 1)
      hour.set(0);
      world.tickOnce();

      assertEquals(3, world.gameTime());
      assertEquals(1, world.dayBright(map0));

      WorldEvent.DayChanging changing = (WorldEvent.DayChanging) aliceEvents.stream()
          .filter(e -> e instanceof WorldEvent.DayChanging)
          .findFirst()
          .orElseThrow();
      assertEquals(aliceId, changing.playerId());
      assertEquals(3, changing.gameTime());
      assertEquals(1, changing.dayBright());
    }
  }

  @Test
  void normalChatBroadcastsToObserversWithinTwelveCellsAndSelf() {
    GameMap map = GameMap.empty("0", "比奇省", 100, 100);
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<WorldEvent> aliceEvents = new ArrayList<>();
      List<WorldEvent> bobEvents = new ArrayList<>();
      List<WorldEvent> distantEvents = new ArrayList<>();

      var alice = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      var bob = world.enterPlayer("Bob", "0", new Position(15, 15), Direction.DOWN, bobEvents::add); // dist 5 <= 12
      var distant = world.enterPlayer("Distant", "0", new Position(40, 40), Direction.DOWN, distantEvents::add); // dist 30 > 12

      world.tickOnce();
      int aliceId = alice.join().id();
      bob.join();
      distant.join();

      aliceEvents.clear();
      bobEvents.clear();
      distantEvents.clear();

      world.say(aliceId, "大家好！传奇重现！");
      world.tickOnce();

      // Alice receives own ChatHeard
      assertTrue(aliceEvents.stream().anyMatch(e -> e instanceof WorldEvent.ChatHeard ch
          && ch.speakerId() == aliceId && ch.speakerName().equals("Alice") && ch.message().equals("大家好！传奇重现！")));

      // Bob receives Alice's ChatHeard
      assertTrue(bobEvents.stream().anyMatch(e -> e instanceof WorldEvent.ChatHeard ch
          && ch.speakerId() == aliceId && ch.speakerName().equals("Alice") && ch.message().equals("大家好！传奇重现！")));

      // Distant player receives nothing
      assertTrue(distantEvents.stream().noneMatch(e -> e instanceof WorldEvent.ChatHeard));
    }
  }

  @Test
  void noChatMapFlagBlocksSpeechWithSystemMessage() {
    MapFlags noChatFlags = new MapFlags(false, false, false, false, false, false, false, "", true, true, false, -1, -1);
    GameMap quietMap = GameMap.empty("quiet", "禁言地图", 50, 50, noChatFlags);
    try (WorldEngine world = new WorldEngine(List.of(quietMap))) {
      List<WorldEvent> events = new ArrayList<>();
      var alice = world.enterPlayer("Alice", "quiet", new Position(10, 10), Direction.DOWN, events::add);
      world.tickOnce();
      int aliceId = alice.join().id();
      events.clear();

      var result = world.say(aliceId, "有人吗？");
      world.tickOnce();

      assertFalse(result.join());
      assertTrue(events.stream().noneMatch(e -> e instanceof WorldEvent.ChatHeard));
      assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.SystemMessage sm
          && sm.message().contains("禁止发言")));
    }
  }

  @Test
  void whisperDeliversToTargetAndEchoesToSender() {
    GameMap map = GameMap.empty("0", "比奇省", 50, 50);
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<WorldEvent> aliceEvents = new ArrayList<>();
      List<WorldEvent> bobEvents = new ArrayList<>();
      List<WorldEvent> charlieEvents = new ArrayList<>();

      var alice = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      var bob = world.enterPlayer("Bob", "0", new Position(12, 12), Direction.DOWN, bobEvents::add);
      var charlie = world.enterPlayer("Charlie", "0", new Position(14, 14), Direction.DOWN, charlieEvents::add);

      world.tickOnce();
      int aliceId = alice.join().id();
      int bobId = bob.join().id();
      charlie.join();

      aliceEvents.clear();
      bobEvents.clear();
      charlieEvents.clear();

      var res = world.say(aliceId, "/Bob 私聊密令123");
      world.tickOnce();
      assertTrue(res.join());

      // Bob receives whisper
      assertTrue(bobEvents.stream().anyMatch(e -> e instanceof WorldEvent.Whisper w
          && w.senderName().equals("Alice") && w.recipientName().equals("Bob") && w.message().equals("私聊密令123")));

      // Alice receives echo whisper
      assertTrue(aliceEvents.stream().anyMatch(e -> e instanceof WorldEvent.Whisper w
          && w.senderName().equals("Alice") && w.recipientName().equals("Bob") && w.message().equals("私聊密令123")));

      // Charlie gets nothing
      assertTrue(charlieEvents.stream().noneMatch(e -> e instanceof WorldEvent.Whisper));
    }
  }

  @Test
  void whisperToOfflinePlayerReturnsSystemMessage() {
    GameMap map = GameMap.empty("0", "比奇省", 50, 50);
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<WorldEvent> aliceEvents = new ArrayList<>();
      var alice = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      world.tickOnce();
      int aliceId = alice.join().id();
      aliceEvents.clear();

      var res = world.say(aliceId, "/Ghost 在吗？");
      world.tickOnce();
      assertFalse(res.join());

      assertTrue(aliceEvents.stream().anyMatch(e -> e instanceof WorldEvent.SystemMessage sm
          && sm.message().contains("Ghost") && sm.message().contains("不在线")));
    }
  }

  @Test
  void shoutBroadcastsToAllPlayersOnSameMap() {
    GameMap map0 = GameMap.empty("0", "比奇省", 100, 100);
    GameMap map1 = GameMap.empty("1", "盟重省", 100, 100);
    try (WorldEngine world = new WorldEngine(List.of(map0, map1))) {
      List<WorldEvent> aliceEvents = new ArrayList<>();
      List<WorldEvent> bobEvents = new ArrayList<>();
      List<WorldEvent> otherMapEvents = new ArrayList<>();

      var alice = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, aliceEvents::add);
      var bob = world.enterPlayer("Bob", "0", new Position(80, 80), Direction.DOWN, bobEvents::add); // far away on same map
      var otherMap = world.enterPlayer("Dave", "1", new Position(10, 10), Direction.DOWN, otherMapEvents::add);

      world.tickOnce();
      int aliceId = alice.join().id();
      bob.join();
      otherMap.join();

      aliceEvents.clear();
      bobEvents.clear();
      otherMapEvents.clear();

      var res = world.say(aliceId, "!全服收裁决之杖");
      world.tickOnce();
      assertTrue(res.join());

      // Bob on same map receives Shout
      assertTrue(bobEvents.stream().anyMatch(e -> e instanceof WorldEvent.Shout s
          && s.speakerName().equals("Alice") && s.message().equals("全服收裁决之杖")));

      // Dave on another map does not receive Shout
      assertTrue(otherMapEvents.stream().noneMatch(e -> e instanceof WorldEvent.Shout));
    }
  }

  @Test
  void quizMapFlagBlocksShout() {
    MapFlags quizFlags = new MapFlags(false, false, false, false, false, true, false, "", false, true, false, -1, -1);
    GameMap quizMap = GameMap.empty("quiz", "答题地图", 50, 50, quizFlags);
    try (WorldEngine world = new WorldEngine(List.of(quizMap))) {
      List<WorldEvent> events = new ArrayList<>();
      var alice = world.enterPlayer("Alice", "quiz", new Position(10, 10), Direction.DOWN, events::add);
      world.tickOnce();
      int aliceId = alice.join().id();
      events.clear();

      var res = world.say(aliceId, "!求答案");
      world.tickOnce();
      assertFalse(res.join());

      assertTrue(events.stream().noneMatch(e -> e instanceof WorldEvent.Shout));
      assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.SystemMessage sm
          && sm.message().contains("禁止大喊")));
    }
  }

  @Test
  void onlinePlayerCountCommandsReturnSystemMessage() {
    GameMap map = GameMap.empty("0", "比奇省", 50, 50);
    try (WorldEngine world = new WorldEngine(List.of(map))) {
      List<WorldEvent> events = new ArrayList<>();
      var alice = world.enterPlayer("Alice", "0", new Position(10, 10), Direction.DOWN, events::add);
      var bob = world.enterPlayer("Bob", "0", new Position(15, 15), Direction.DOWN, ignored -> {});
      world.tickOnce();
      int aliceId = alice.join().id();
      bob.join();
      events.clear();

      world.say(aliceId, "/who");
      world.tickOnce();
      assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.SystemMessage sm
          && sm.message().contains("当前在线玩家: 2 人")));

      events.clear();
      world.say(aliceId, "@在线");
      world.tickOnce();
      assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.SystemMessage sm
          && sm.message().contains("当前在线玩家: 2 人")));
    }
  }

  @Test
  void noReconnectMapRedirectsSpawnToConfiguredMap() {
    MapFlags noreconn = new MapFlags(false, false, false, false, false, false, true, "0", false, true, false, -1, -1);
    GameMap dungeon = GameMap.empty("D01", "尸王殿", 20, 20, noreconn);
    GameMap safeMap = GameMap.empty("0", "比奇省", 50, 50);
    try (WorldEngine world = new WorldEngine(List.of(dungeon, safeMap))) {
      List<WorldEvent> events = new ArrayList<>();
      var player = world.enterPlayerNear("Hero", "D01", new Position(10, 10), Direction.DOWN, 0, 0, events::add);
      world.tickOnce();
      WorldObjectSnapshot snapshot = player.join();

      // Spawn redirected to map "0"
      assertEquals("0", snapshot.mapId());
      WorldEvent.MapEntered entered = (WorldEvent.MapEntered) events.getFirst();
      assertEquals("0", entered.map().id());
    }
  }
}
