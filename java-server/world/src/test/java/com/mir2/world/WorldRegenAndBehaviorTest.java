package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MonGen-style respawn spawners, the TChickenDeer flee AI and the periodic online save. */
class WorldRegenAndBehaviorTest {
  private final AtomicLong now = new AtomicLong(1_000_000);

  @Test
  void fleeingDeerRunsAwayFromThePlayerAndNeverAttacks() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 40, 40))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT, events::add));
      WorldObjectSnapshot deer = run(world,
          world.spawnMonster(MonsterTemplate.deer(), "0", new Position(22, 20), Direction.LEFT));
      int initialDistance = new Position(22, 20).distanceTo(new Position(20, 20));
      events.clear();

      for (int tick = 0; tick < 10; tick++) {
        advance(1_000);
        world.tickOnce();
      }

      WorldObjectSnapshot fled = run(world, world.snapshot(deer.id()));
      assertTrue(fled.position().distanceTo(new Position(20, 20)) > initialDistance,
          "the deer must increase its distance to the player");
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectMoved
              || event instanceof WorldEvent.ObjectDisappeared),
          "the flight must be broadcast to the observing player");
      assertFalse(events.stream().anyMatch(event ->
              event instanceof WorldEvent.ObjectStruck struck && struck.victim().id() == player.id()),
          "a fleeing animal never attacks");
      assertEquals(Ability.defaultPlayer().hp(), run(world, world.snapshot(player.id())).ability().hp());
    }
  }

  @Test
  void spawnerMaterialisesItsPopulationAndRefillsKilledMonstersAfterTheRespawnInterval() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 40, 40))) {
      // 10-minute respawn keeps the refill far away from the kill loop's virtual-clock budget.
      run(world, world.addSpawner(MonsterTemplate.chicken(), "0",
          new MonsterSpawnDefinition("0", 10, 10, "鸡", 3, 4, 600_000, 0)));

      // The next regen pass (>=200ms after the previous one) populates the row.
      advance(1_000);
      world.tickOnce();
      assertEquals(4, run(world, world.liveMonsters()));

      // A full population must not over-spawn on later passes.
      advance(1_000);
      world.tickOnce();
      assertEquals(4, run(world, world.liveMonsters()));

      // Kill one chicken; before the respawn interval elapses the population stays reduced.
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("猎人", "0", new Position(10, 14), Direction.UP, events::add));
      killOneChicken(world, player, events);
      int afterKill = run(world, world.liveMonsters());
      assertEquals(3, afterKill);
      advance(5_000);
      world.tickOnce();
      assertEquals(3, run(world, world.liveMonsters()),
          "no refill may happen before the MonGen respawn interval");

      // Once the interval has passed, the spawner tops the area back up to four.
      advance(601_000);
      world.tickOnce();
      assertEquals(4, run(world, world.liveMonsters()));
    }
  }

  @Test
  void spawnerForUnknownMapIsRejected() {
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      CompletableFuture<Void> future = world.addSpawner(MonsterTemplate.chicken(), "missing",
          new MonsterSpawnDefinition("missing", 5, 5, "鸡", 2, 1, 60_000, 0));
      world.tickOnce();
      assertThrows(Exception.class, future::get);
    }
  }

  @Test
  void onlinePlayersAreSavedPeriodicallyWithoutAnyTriggeringEvent() {
    RecordingStore store = new RecordingStore();
    WorldEngine.Config config = new WorldEngine.Config(
        Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000, 200, 10_000);
    try (WorldEngine world = new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)),
        now::get, new Random(7L), store, ItemDatabase.of(StdItems.defaults()))) {
      run(world, world.enterPlayer(UUID.randomUUID(), "存档者", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, event -> {}));
      int savesAfterEnter = store.saves;
      assertTrue(savesAfterEnter > 0, "entering persists the initial state");

      // Idle below the save interval: no additional saves.
      advance(9_000);
      world.tickOnce();
      assertEquals(savesAfterEnter, store.saves);

      // Crossing the interval triggers exactly one periodic save per player.
      advance(2_000);
      world.tickOnce();
      assertEquals(savesAfterEnter + 1, store.saves);

      // And it keeps saving on the following intervals.
      advance(10_500);
      world.tickOnce();
      assertEquals(savesAfterEnter + 2, store.saves);
    }
  }

  @Test
  void firstTenTemplateCatalogResolvesChineseAndAsciiNames() {
    assertEquals("鹿", MonsterTemplate.forName("鹿").name());
    assertEquals("鹿", MonsterTemplate.forName("deer").name());
    assertEquals(MonsterBehavior.PASSIVE_FLEE, MonsterTemplate.forName("deer").behavior());
    assertEquals("稻草人", MonsterTemplate.forName("Scarecrow").name());
    assertEquals("多钩猫", MonsterTemplate.forName("多钩猫").name());
    assertEquals("钉耙猫", MonsterTemplate.forName("rakecat").name());
    assertEquals("洞蛆", MonsterTemplate.forName("cavemaggot").name());
    assertEquals("蝎子", MonsterTemplate.forName("蝎子").name());
    assertEquals("半兽勇士", MonsterTemplate.forName("orcwarrior").name());
    assertEquals("半兽战士", MonsterTemplate.forName("半兽战士").name());
    assertEquals(MonsterBehavior.AGGRESSIVE, MonsterTemplate.forName("orc").behavior());
    assertThrows(IllegalArgumentException.class, () -> MonsterTemplate.forName("dragon"));
  }

  /**
   * Chickens are aggressive in the Java slice, so one closes in on the hunter by itself;
   * we swing at the cell a monster was last observed on until one dies.
   */
  private void killOneChicken(WorldEngine world, WorldObjectSnapshot player, List<WorldEvent> events) {
    java.util.Map<Integer, Position> lastSeen = new java.util.HashMap<>();
    for (int round = 0; round < 200; round++) {
      advance(1_000);
      world.tickOnce();
      for (WorldEvent event : events) {
        WorldObjectSnapshot object = switch (event) {
          case WorldEvent.MapEntered entered -> null; // handled below
          case WorldEvent.ObjectAppeared appeared -> appeared.object();
          case WorldEvent.ObjectMoved moved -> moved.object();
          case WorldEvent.ObjectAttacked attacked -> attacked.attacker();
          case WorldEvent.ObjectStruck struck -> struck.victim();
          default -> null;
        };
        if (event instanceof WorldEvent.MapEntered entered) {
          for (WorldObjectSnapshot visible : entered.visibleObjects()) {
            if (visible.type() == WorldObjectType.MONSTER) lastSeen.put(visible.id(), visible.position());
          }
        }
        if (object != null && object.type() == WorldObjectType.MONSTER) {
          lastSeen.put(object.id(), object.position());
        }
      }
      events.clear();
      Position self = run(world, world.snapshot(player.id())).position();
      Position adjacent = lastSeen.values().stream()
          .filter(position -> position.distanceTo(self) == 1)
          .findFirst()
          .orElse(null);
      if (adjacent == null) continue;
      Direction direction = Direction.toward(self, adjacent);
      AttackResult result = run(world, world.attack(player.id(), self, direction, AttackKind.HIT));
      if (result.accepted() && result.victimSnapshot().isPresent()
          && !result.victimSnapshot().orElseThrow().alive()) {
        return;
      }
    }
    throw new AssertionError("no chicken could be killed within the tick budget");
  }

  private WorldEngine engine(GameMap map) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(StdItems.defaults()));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static final class RecordingStore implements PlayerStateStore {
    private int saves;

    @Override
    public Optional<PlayerState> load(UUID characterId) {
      return Optional.empty();
    }

    @Override
    public void save(PlayerState state) {
      saves++;
    }
  }
}
