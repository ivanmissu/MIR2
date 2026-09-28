package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * W36 — 技能逐项接入·第五批: 野蛮冲撞 ({@code SKILL_MOOTEBO = 27}).
 *
 * <p>Delphi {@code ObjBase.pas:9112} ({@code ClientSpellXY}) and {@code ObjBase.pas:21703}
 * ({@code DoMotaebo}):
 * <ul>
 *   <li>3-second cooldown gate ({@code m_dwDoMotaeboTick > 3 * 1000}), answered {@code +GOOD}
 *       regardless;</li>
 *   <li>Mana cost according to Magic.DB (id 27: 4 / 8 / 11 / 15 MP at levels 0..3);</li>
 *   <li>Empty rush traverses 3 steps (lv 0/1), 4 steps (lv 2), 5 steps (lv 3);</li>
 *   <li>Hitting impassable terrain emits {@code RM_RUSHKUNG} + red 「冲撞力不够...」 hint and
 *       deals recoil damage to the pusher;</li>
 *   <li>Pushing lower-level targets ({@code player.level > target.level}) pushes the target
 *       1 tile back ({@code RM_PUSH} / backstep) and moves the pusher into the cell ({@code RM_RUSH});</li>
 *   <li>Level 3 pushes up to two aligned targets in front;</li>
 *   <li>Equal or higher level targets resist the push ({@code RM_RUSHKUNG} + hint);</li>
 *   <li>Penetrating push awards skill training ({@code Random(3) + 1}) and levels up.</li>
 * </ul>
 */
class WorldMotaeboSkillTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void motaeboIsWarriorOnlyAndRequiresLevelThreshold() {
    RecordingStore warriorStore = new RecordingStore();
    UUID warriorId = UUID.randomUUID();
    StdItem book = StdItemsDb.byName("野蛮冲撞").orElseThrow();
    Ability lowLevel = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 29,
        Ability.defaultPlayer()).restored();
    warriorStore.save(new PlayerState(warriorId, lowLevel,
        List.of(BackpackItem.of(book, 1)), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(warriorStore, new FixedRandom())) {
      WorldObjectSnapshot warrior = enter(world, warriorId, "低级战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      // Required level for 野蛮冲撞 is 30; level 29 cannot read the book.
      assertFalse(run(world, world.useItem(warrior.id(), 1, "野蛮冲撞")));
      assertTrue(run(world, world.skills(warrior.id())).isEmpty());
    }

    // Wrong job (法师 / 道士) cannot read the book either.
    RecordingStore wizardStore = new RecordingStore();
    UUID wizardId = UUID.randomUUID();
    Ability wizardLevel = LevelAbilities.forLevel(LevelAbilities.JOB_WIZARD, 35,
        Ability.defaultPlayer()).restored();
    wizardStore.save(new PlayerState(wizardId, wizardLevel,
        List.of(BackpackItem.of(book, 1)), Equipment.empty(), 0, 0, 0));
    try (WorldEngine world = engine(wizardStore, new FixedRandom())) {
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, new ArrayList<>());
      assertFalse(run(world, world.useItem(wizard.id(), 1, "野蛮冲撞")));
      assertTrue(run(world, world.skills(wizard.id())).isEmpty());
    }

    // Level 30 warrior learns the skill.
    RecordingStore readyStore = new RecordingStore();
    UUID readyId = UUID.randomUUID();
    Ability readyLevel = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 30,
        Ability.defaultPlayer()).restored();
    readyStore.save(new PlayerState(readyId, readyLevel,
        List.of(BackpackItem.of(book, 1)), Equipment.empty(), 0, 0, 0));
    try (WorldEngine world = engine(readyStore, new FixedRandom())) {
      WorldObjectSnapshot ready = enter(world, readyId, "达标战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      assertTrue(run(world, world.useItem(ready.id(), 1, "野蛮冲撞")));
      PlayerSkill learned = run(world, world.skills(ready.id())).getFirst();
      assertEquals(HitSpeed.SKILL_MOOTEBO, learned.magicId());
      assertEquals(0, learned.level());
    }
  }

  @Test
  void emptyRushTraversesStepsAccordingToSkillLevelAndSpendsMana() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 35,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 0, 0, 0))));

    // Level 0: traverses 3 tiles forward.
    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "冲撞者", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      int manaBefore = player.ability().mp();
      events.clear();

      // Rush facing RIGHT. Target is encoded as direction 2 (RIGHT) in x, 0 in y.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      WorldObjectSnapshot after = run(world, world.snapshot(player.id()));
      assertEquals(new Position(8, 5), after.position(), "level 0 rush traverses 3 tiles");
      assertEquals(Direction.RIGHT, after.direction());
      // Level 0 mana cost = 4.
      assertEquals(manaBefore - 4, after.ability().mp());

      long rushedCount = events.stream().filter(WorldEvent.ObjectRushed.class::isInstance).count();
      assertEquals(3, rushedCount, "must emit ObjectRushed 3 times along the path");
    }

    // Level 2: traverses 4 tiles. Level 3: traverses 5 tiles.
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 3, 0, 0))));
    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "高级冲撞者", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      int manaBefore = player.ability().mp();
      events.clear();

      now.addAndGet(5_000);
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      WorldObjectSnapshot after = run(world, world.snapshot(player.id()));
      assertEquals(new Position(10, 5), after.position(), "level 3 rush traverses 5 tiles");
      // Level 3 mana cost = 15.
      assertEquals(manaBefore - 15, after.ability().mp());
      long rushedCount = events.stream().filter(WorldEvent.ObjectRushed.class::isInstance).count();
      assertEquals(5, rushedCount, "level 3 emits 5 rush events");
    }
  }

  @Test
  void cooldownGateSuppressesSubsequentRushWithinThreeSeconds() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 35,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 0, 0, 0))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "速撞", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      events.clear();

      // First cast: succeeds and advances 3 tiles.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));
      assertEquals(new Position(8, 5), run(world, world.snapshot(player.id())).position());
      int mpAfterFirst = run(world, world.snapshot(player.id())).ability().mp();

      // Second cast 1 second later: cooldown not ready (> 3000ms strictly).
      now.addAndGet(1_000);
      events.clear();
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)), "CM_SPELL still answers +GOOD");
      assertEquals(new Position(8, 5), run(world, world.snapshot(player.id())).position(),
          "position unchanged during cooldown");
      assertEquals(mpAfterFirst, run(world, world.snapshot(player.id())).ability().mp(),
          "no mana consumed during cooldown");
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectRushed.class::isInstance));

      // 3001 ms after first cast: cooldown expires, rush fires again.
      now.addAndGet(2_001);
      events.clear();
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));
      assertEquals(new Position(11, 5), run(world, world.snapshot(player.id())).position());
      assertEquals(mpAfterFirst - 4, run(world, world.snapshot(player.id())).ability().mp());
    }
  }

  @Test
  void hittingSolidObstacleEmitsRushFailedAndDealsRecoilDamage() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 35,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 0, 0, 0))));

    // Map with blocked obstacle at (6, 5) — right in front of the player at (5, 5).
    GameMap map = GameMap.withBlockedCells("0", "PoC", 20, 20, List.of(new Position(6, 5)));
    try (WorldEngine world = engineWithMap(store, map, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "撞墙战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      int hpBefore = player.ability().hp();
      events.clear();

      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      // Player could not move.
      WorldObjectSnapshot after = run(world, world.snapshot(player.id()));
      assertEquals(new Position(5, 5), after.position());

      // Rush failed event (SM_RUSHKUNG) and red hint emitted.
      WorldEvent.ObjectRushFailed failed = events.stream()
          .filter(WorldEvent.ObjectRushFailed.class::isInstance)
          .map(WorldEvent.ObjectRushFailed.class::cast)
          .findFirst().orElseThrow();
      assertEquals(new Position(6, 5), failed.targetCell());
      assertEquals(Direction.RIGHT, failed.direction());

      WorldEvent.SystemMessage sysMsg = events.stream()
          .filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast)
          .findFirst().orElseThrow();
      assertEquals("冲撞力不够...", sysMsg.message());

      // Recoil damage applied to player (n28 > 0 when failing before steps finished).
      assertTrue(after.ability().hp() < hpBefore, "recoil damage reduces player HP");
    }
  }

  @Test
  void pushingLowerLevelMonsterPushesMonsterMovesPlayerDealsDamageAndTrains() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 35,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 0, 0, 0))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "推怪战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      // Spawn trainer dummy (level 1) at (6, 5) facing LEFT.
      WorldObjectSnapshot monster = run(world, world.spawnMonster(
          MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      // Player level 35 > monster level 1: monster is pushed 3 steps right to (9, 5).
      // Player moves 3 steps to (8, 5).
      WorldObjectSnapshot playerAfter = run(world, world.snapshot(player.id()));
      WorldObjectSnapshot monsterAfter = run(world, world.snapshot(monster.id()));

      assertEquals(new Position(8, 5), playerAfter.position());
      assertEquals(new Position(9, 5), monsterAfter.position());
      // Monster's facing flipped to opposite (LEFT -> RIGHT).
      assertEquals(Direction.RIGHT, monsterAfter.direction());

      // Target took collision damage.
      assertTrue(monsterAfter.ability().hp() < monster.ability().hp());

      // Events include ObjectPushed and ObjectRushed.
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectPushed.class::isInstance));
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectRushed.class::isInstance));

      // Skill trained (level 35 > needL1 30).
      PlayerSkill skillAfter = run(world, world.skills(player.id())).getFirst();
      assertTrue(skillAfter.trainingPoints() > 0);
      assertNotNull(events.stream()
          .filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .findFirst().orElse(null));
    }
  }

  @Test
  void failingToPushEqualOrHigherLevelTarget() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    // Level 30 warrior.
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 30,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 0, 0, 0))));

    UUID targetId = UUID.randomUUID();
    // Level 35 target player.
    Ability targetAbility = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 35,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(targetId, targetAbility, List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "小战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      WorldObjectSnapshot target = enter(world, targetId, "大战士", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      events.clear();

      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      // Player level 30 <= target level 35: push fails.
      assertEquals(new Position(5, 5), run(world, world.snapshot(player.id())).position());
      assertEquals(new Position(6, 5), run(world, world.snapshot(target.id())).position());

      // System message and rush failed emitted.
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectRushFailed.class::isInstance));
      WorldEvent.SystemMessage msg = events.stream()
          .filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast)
          .findFirst().orElseThrow();
      assertEquals("冲撞力不够...", msg.message());
    }
  }

  @Test
  void levelThreeMotaeboPushesTwoAlignedTargets() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 40,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_MOOTEBO, 3, 0, 0))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      WorldObjectSnapshot player = enter(world, id, "双推战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot mon1 = run(world, world.spawnMonster(
          MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      WorldObjectSnapshot mon2 = run(world, world.spawnMonster(
          MonsterTemplate.trainer(), "0", new Position(7, 5), Direction.LEFT));

      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_MOOTEBO,
          new Position(Direction.RIGHT.code(), 0), 0)));

      // Level 3 pushes both targets ahead.
      WorldObjectSnapshot playerAfter = run(world, world.snapshot(player.id()));
      WorldObjectSnapshot mon1After = run(world, world.snapshot(mon1.id()));
      WorldObjectSnapshot mon2After = run(world, world.snapshot(mon2.id()));

      assertTrue(playerAfter.position().x() > 5, "player advanced");
      assertTrue(mon1After.position().x() > 6, "first monster pushed");
      assertTrue(mon2After.position().x() > 7, "second monster pushed");
      assertEquals(playerAfter.position().x() + 1, mon1After.position().x());
      assertEquals(mon1After.position().x() + 1, mon2After.position().x());
    }
  }

  // ------------------------------------------------------------------ helpers

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.RIGHT,
        0, 0, job, events::add));
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    return engineWithMap(store, GameMap.empty("0", "PoC", 30, 30), random);
  }

  private WorldEngine engineWithMap(PlayerStateStore store, GameMap map, Random random) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  private static final class RecordingStore implements PlayerStateStore {
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
