package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W14: level-up ({@code GetExp}/{@code HasLevelUp}/{@code RecalcLevelAbilitys}), player death
 * with the bag scatter penalty, revival ({@code ReAlive}) and the ghost timeout.
 */
class WorldLevelAndDeathTest {
  private final AtomicLong now = new AtomicLong(1_000);

  @Test
  void killingMonstersRaisesTheLevelAndRebuildsTheAbilityFromTheJobCurve() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(
          java.util.UUID.randomUUID(), "战士", "0", new Position(5, 5), Direction.RIGHT,
          0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      // A fresh character is the UsrEngn.pas:503 block: level 1, 15 HP, 100 MaxExp.
      assertEquals(1, player.ability().level());
      assertEquals(15, player.ability().maxHp());
      assertEquals(100, player.ability().maxExperience());

      // 鸡 awards its Monster.DB experience of 9 apiece; a level-one warrior (DC 1-2) can
      // actually beat one. Eleven of them reach 99, just short of the 100-point threshold.
      for (int kill = 0; kill < 11; kill++) {
        killAdjacentMonster(world, player.id(), MonsterTemplate.chicken(), new Position(6, 5));
      }
      events.clear();
      killAdjacentMonster(world, player.id(), MonsterTemplate.chicken(), new Position(6, 5));

      WorldEvent.LevelUp levelUp = events.stream()
          .filter(WorldEvent.LevelUp.class::isInstance)
          .map(WorldEvent.LevelUp.class::cast)
          .findFirst().orElseThrow();
      assertEquals(2, levelUp.level());
      // GetExp deducts exactly one threshold: 12 * 9 - 100 = 8 carried into level 2.
      assertEquals(8, levelUp.experience());
      // HasLevelUp refreshes MaxExp from GetLevelExp(2) = 200.
      assertEquals(200, levelUp.ability().maxExperience());
      // RecalcLevelAbilitys: a level-2 warrior is 24 HP / 18 MP, and its DC narrows from the
      // creation literal 1-2 to the curve's 1-1 (MakeLong(_MAX(L div 5 - 1, 1), ...)).
      assertEquals(24, levelUp.ability().maxHp());
      assertEquals(18, levelUp.ability().maxMp());
      assertEquals(1, levelUp.ability().minDc());
      assertEquals(1, levelUp.ability().maxDc());
      // HasLevelUp ends in IncHealthSpell(2000, 2000): the pools are topped up, not just raised.
      assertEquals(24, levelUp.ability().hp());
      assertEquals(18, levelUp.ability().mp());

      // RM_LEVELUP also pushes the full ability block down to the client.
      assertTrue(events.stream().anyMatch(event ->
          event instanceof WorldEvent.AbilityChanged changed && changed.ability().level() == 2));

      WorldObjectSnapshot after = run(world, world.snapshot(player.id()));
      assertEquals(2, after.ability().level());
      assertEquals(8, after.ability().experience());
    }
  }

  @Test
  void levelUpSurvivesALogoutAndMaxExpIsRederivedOnLogin() {
    java.util.UUID characterId = java.util.UUID.randomUUID();
    InMemoryPlayerStateStore store = new InMemoryPlayerStateStore();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), store)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(
          characterId, "法师", "0", new Position(5, 5), Direction.RIGHT, 0, 0,
          LevelAbilities.JOB_WIZARD, event -> {}));
      // The GM @Level command (CmdChangeLevel) sets the level outright and runs HasLevelUp(1).
      assertEquals(20, run(world, world.setLevel(player.id(), 20)));
      run(world, world.leavePlayer(player.id()));
    }

    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), store)) {
      WorldObjectSnapshot restored = run(world, world.enterPlayer(
          characterId, "法师", "0", new Position(5, 5), Direction.RIGHT, 0, 0,
          LevelAbilities.JOB_WIZARD, event -> {}));
      assertEquals(20, restored.ability().level());
      // MaxExp is derived state Delphi never persists; it must come back from GetLevelExp(20).
      assertEquals(140_000, restored.ability().maxExperience());
      // The wizard curve, not the warrior one, survived the round trip.
      assertEquals(77, restored.ability().maxHp());
      assertEquals(277, restored.ability().maxMp());
    }
  }

  @Test
  void deathScattersPartOfTheBagAndBroadcastsTheRemovalList() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      // Fill the bag through the ordinary pickup path so every entry is a real instance.
      for (int index = 0; index < 12; index++) {
        run(world, world.spawnGroundItem("鹿肉", 42, "0", new Position(5, 5)));
        assertTrue(run(world, world.pickUp(player.id(), new Position(5, 5))));
      }
      assertEquals(12, run(world, world.playerState(player.id())).backpack().size());

      events.clear();
      killPlayerWithMonster(world, player.id());

      WorldEvent.ObjectDied died = events.stream()
          .filter(WorldEvent.ObjectDied.class::isInstance)
          .map(WorldEvent.ObjectDied.class::cast)
          .filter(event -> event.victim().id() == player.id())
          .findFirst().orElseThrow();
      assertFalse(died.victim().ability().alive());

      WorldEvent.ItemsRemoved removed = events.stream()
          .filter(WorldEvent.ItemsRemoved.class::isInstance)
          .map(WorldEvent.ItemsRemoved.class::cast)
          .findFirst().orElseThrow();
      // g_Config.nDieScatterBagRate = 3, so roughly a third of twelve entries drop; the exact
      // count is seeded and deterministic, but only the invariant is asserted here.
      assertTrue(removed.items().size() > 0 && removed.items().size() < 12,
          "a 1-in-3 scatter must drop some but not all of a twelve-item bag, got "
              + removed.items().size());
      List<BackpackItem> left = run(world, world.playerState(player.id())).backpack();
      assertEquals(12 - removed.items().size(), left.size());
      // Every scattered entry becomes a ground item within DropWide = 2 cells of the corpse.
      long visible = events.stream().filter(WorldEvent.ItemAppeared.class::isInstance).count();
      assertEquals(removed.items().size(), visible);
      events.stream()
          .filter(WorldEvent.ItemAppeared.class::isInstance)
          .map(WorldEvent.ItemAppeared.class::cast)
          .forEach(appeared -> {
            Position cell = appeared.item().position();
            assertTrue(Math.abs(cell.x() - 5) <= 2 && Math.abs(cell.y() - 5) <= 2,
                "scattered item must land within DropWide = 2 cells: " + cell);
          });
    }
  }

  @Test
  void reviveBringsADeadPlayerBackOnTheSpotWithFullHealth() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      killPlayerWithMonster(world, player.id());
      assertFalse(run(world, world.snapshot(player.id())).ability().alive());
      // A corpse cannot act.
      assertFalse(run(world, world.attack(
          player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT)).accepted());

      events.clear();
      assertTrue(run(world, world.revive(player.id())));
      WorldEvent.ObjectRevived revived = events.stream()
          .filter(WorldEvent.ObjectRevived.class::isInstance)
          .map(WorldEvent.ObjectRevived.class::cast)
          .findFirst().orElseThrow();
      assertEquals(player.id(), revived.object().id());
      // CmdReAlive tops the health up and leaves the player standing where it fell.
      assertTrue(revived.object().ability().alive());
      assertEquals(revived.object().ability().maxHp(), revived.object().ability().hp());
      assertEquals(new Position(5, 5), revived.object().position());
      // Reviving a living player is a no-op.
      assertFalse(run(world, world.revive(player.id())));
    }
  }

  @Test
  void aCorpseTurnsIntoAGhostThreeMinutesAfterDeath() {
    List<WorldEvent> watcherEvents = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, event -> {}));
      run(world, world.enterPlayer("观察者", "0", new Position(5, 8), Direction.UP,
          watcherEvents::add));
      killPlayerWithMonster(world, player.id());

      // g_Config.dwMakeGhostTime is 3 minutes; just under it the corpse is still there.
      watcherEvents.clear();
      advance(3 * 60 * 1000 - 1);
      world.tickOnce();
      assertEquals(1, run(world, world.onlinePlayers()) - 1, "the corpse is still in the world");

      advance(2);
      world.tickOnce();
      assertTrue(watcherEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectDisappeared gone && gone.objectId() == player.id()),
          "MakeGhost must remove the corpse from every observer's view");
    }
  }

  @Test
  void healthAndManaRefillOnTheirOwnClocksButNotWhileDead() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20))) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      // A sturdy level-20 warrior (224 HP) so a single chicken cannot out-damage the regen.
      run(world, world.setLevel(player.id(), 20));
      run(world, world.spawnMonster(MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));
      int wounded = 0;
      // W33: a chicken (Monster.DB HIT = 3) misses a DEFSPEED = 15 character about three
      // swings in four, and it only swings every 3 s, so the window has to be generous.
      for (int tick = 0; tick < 200; tick++) {
        advance(1_000);
        world.tickOnce();
        wounded = run(world, world.snapshot(player.id())).ability().hp();
        if (wounded < 224) break;
      }
      assertTrue(wounded > 0 && wounded < 224, "the chicken must land at least one hit");
      // Kill the attacker so nothing competes with the regeneration below.
      for (int swing = 0; swing < 30; swing++) {
        advance(1_000);
        AttackResult result = run(world,
            world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        if (result.victimSnapshot().isPresent() && !result.victimSnapshot().orElseThrow().alive()) break;
      }
      assertEquals(0, run(world, world.liveMonsters()));

      // nHealthFillTime = 300 counter units at 20ms each = 6 seconds, restoring MaxHP/75 + 1.
      int beforeRegen = run(world, world.snapshot(player.id())).ability().hp();
      assertTrue(beforeRegen < 224, "the fight must leave the warrior wounded");
      advance(6_000);
      world.tickOnce();
      int afterRegen = run(world, world.snapshot(player.id())).ability().hp();
      assertEquals(Math.min(224, beforeRegen + 224 / 75 + 1), afterRegen,
          "one nHealthFillTime window restores MaxHP div 75 + 1");

      // A dead player regenerates nothing, however long the clock runs.
      killPlayerWithMonster(world, player.id());
      assertEquals(0, run(world, world.snapshot(player.id())).ability().hp());
      advance(60_000);
      world.tickOnce();
      assertEquals(0, run(world, world.snapshot(player.id())).ability().hp(),
          "TBaseObject.Run skips the regen branch entirely while m_boDeath is set");
    }
  }

  /**
   * Spawns a monster next to the player and swings until it dies. Passing a null template
   * just clears whatever is already standing in front of the player.
   */
  private void killAdjacentMonster(
      WorldEngine world, int playerId, MonsterTemplate template, Position cell) {
    if (template != null) run(world, world.spawnMonster(template, "0", cell, Direction.LEFT));
    AttackResult last = null;
    for (int swing = 0; swing < 60; swing++) {
      advance(1_000);
      last = run(world, world.attack(playerId, new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      if (last.victimSnapshot().isPresent() && !last.victimSnapshot().orElseThrow().alive()) break;
    }
    assertNotNull(last);
    assertFalse(last.victimSnapshot().orElseThrow().alive(), "the monster must die");
    // Let the corpse clear so the next spawn can reuse the cell, then let the natural
    // regeneration heal the fight off — a faithful 15 HP level-one character really does
    // die to a handful of chickens otherwise.
    advance(10_000);
    world.tickOnce();
    for (int rest = 0; rest < 40; rest++) {
      advance(6_000);
      world.tickOnce();
    }
  }

  /**
   * Lets an orc beat the player to death; a fresh character only has 15 HP. Since W33 the
   * orc's Monster.DB HIT of 6 is rolled against the victim's 敏捷, so roughly half its swings
   * are dodged and a level-20 warrior out-regenerates it for several minutes of world time.
   */
  private void killPlayerWithMonster(WorldEngine world, int playerId) {
    run(world, world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
    for (int tick = 0; tick < 1_500; tick++) {
      advance(1_000);
      world.tickOnce();
      if (!run(world, world.snapshot(playerId)).ability().alive()) return;
    }
    throw new AssertionError("the orc failed to kill the player");
  }

  private WorldEngine engine(GameMap map) {
    return engine(map, PlayerStateStore.none());
  }

  private WorldEngine engine(GameMap map, PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L), store,
        ItemDatabase.of(StdItems.defaults()));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  /** Minimal store so the logout/login round trip can be observed without SQLite. */
  private static final class InMemoryPlayerStateStore implements PlayerStateStore {
    private PlayerState saved;

    @Override
    public java.util.Optional<PlayerState> load(java.util.UUID characterId) {
      return java.util.Optional.ofNullable(saved);
    }

    @Override
    public void save(PlayerState state) {
      this.saved = state;
    }
  }
}
