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
 * W33: the first passive melee-skill batch from {@code Magic.pas}/{@code ObjBase.pas}.
 *
 * <p>Basic Sword (3) and Spirit Power (4) do not have a separate spell effect: they alter the
 * classic hit-point calculation and train on a penetrating melee hit.  The test keeps the
 * original job gate, accuracy comparison, one-point-at-a-time level-up and durable save path
 * visible instead of treating the two Magic.DB rows as merely learnable names.
 */
class WorldMeleeSkillTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void basicSwordIsWarriorOnlyAndAHitTrainsTheDurableSkill() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    StdItem book = skillBook("基本剑术");
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 27,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(BackpackItem.of(book, 1)),
        Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, id, "战士", LevelAbilities.JOB_WARRIOR, events);
      events.clear();

      assertTrue(run(world, world.useItem(warrior.id(), 1, "基本剑术")));
      PlayerSkill learned = run(world, world.skills(warrior.id())).stream()
          .filter(skill -> skill.magicId() == 3).findFirst().orElseThrow();
      assertEquals(0, learned.level());
      assertEquals(0, learned.trainingPoints());
      assertTrue(events.stream().anyMatch(WorldEvent.SkillLearned.class::isInstance));

      run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();
      AttackResult result = run(world,
          world.attack(warrior.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(result.accepted());
      assertTrue(result.damage() > 0, "a penetrating hit is required to train the skill");

      PlayerSkill trained = run(world, world.skills(warrior.id())).stream()
          .filter(skill -> skill.magicId() == 3).findFirst().orElseThrow();
      assertEquals(1, trained.trainingPoints(), "Random(3)+1 with a fixed zero draw");
      WorldEvent.SkillTrainingChanged training = events.stream()
          .filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .map(WorldEvent.SkillTrainingChanged.class::cast)
          .findFirst().orElseThrow();
      assertEquals(3, training.magic().skill().magicId());
      assertEquals(1, training.magic().skill().trainingPoints());

      // Re-entering the same durable character must expose the trained row, not reset it.
      run(world, world.leavePlayer(warrior.id()));
    }

    try (WorldEngine world = engine(store, new FixedRandom())) {
      WorldObjectSnapshot restored = enter(world, id, "战士", LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      assertEquals(new PlayerSkill(3, 0, 1, 0), run(world, world.skills(restored.id())).getFirst());
    }
  }

  @Test
  void bothBooksRejectTheWrongJobWithoutConsumingTheBook() {
    RecordingStore warriorStore = new RecordingStore();
    UUID warriorId = UUID.randomUUID();
    Ability wizardLevel = LevelAbilities.forLevel(LevelAbilities.JOB_WIZARD, 27,
        Ability.defaultPlayer()).restored();
    warriorStore.save(new PlayerState(warriorId, wizardLevel,
        List.of(BackpackItem.of(skillBook("基本剑术"), 1)), Equipment.empty(), 0, 0, 0));
    try (WorldEngine world = engine(warriorStore, new FixedRandom())) {
      WorldObjectSnapshot wizard = enter(world, warriorId, "错误职业法师", LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      assertFalse(run(world, world.useItem(wizard.id(), 1, "基本剑术")));
      assertEquals(1, run(world, world.playerState(wizard.id())).backpack().size());
      assertTrue(run(world, world.skills(wizard.id())).isEmpty());
    }

    RecordingStore taoistStore = new RecordingStore();
    UUID taoistId = UUID.randomUUID();
    Ability warriorLevel = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 17,
        Ability.defaultPlayer()).restored();
    taoistStore.save(new PlayerState(taoistId, warriorLevel,
        List.of(BackpackItem.of(skillBook("精神力战法"), 1)), Equipment.empty(), 0, 0, 0));
    try (WorldEngine world = engine(taoistStore, new FixedRandom())) {
      WorldObjectSnapshot warrior = enter(world, taoistId, "错误职业战士", LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      assertFalse(run(world, world.useItem(warrior.id(), 1, "精神力战法")));
      assertEquals(1, run(world, world.playerState(warrior.id())).backpack().size());
      assertTrue(run(world, world.skills(warrior.id())).isEmpty());
    }
  }

  @Test
  void ilkwangIsTaoistOnlyAndUsesItsOwnAccuracyCurve() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    // Level 17 keeps the Taoist's natural DC above zero, so the fixed draw produces a
    // penetrating hit rather than a legitimate zero-damage strike.
    Ability ability = LevelAbilities.forLevel(LevelAbilities.JOB_TAOIST, 17,
        Ability.defaultPlayer()).restored();
    store.save(new PlayerState(id, ability, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(4))));

    try (WorldEngine world = engine(store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", LevelAbilities.JOB_TAOIST, events);
      run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();

      AttackResult result = run(world,
          world.attack(taoist.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(result.accepted());
      assertTrue(result.damage() > 0);
      PlayerSkill skill = run(world, world.skills(taoist.id())).getFirst();
      assertEquals(4, skill.magicId());
      assertEquals(1, skill.trainingPoints());
      assertNotNull(events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .findFirst().orElse(null));
    }
  }

  @Test
  void basicSwordAccuracyUsesTheObjBaseHitVersusSpeedComparison() {
    UUID missId = UUID.randomUUID();
    RecordingStore missStore = new RecordingStore();
    Ability levelOne = LevelAbilities.forLevel(LevelAbilities.JOB_WARRIOR, 7,
        Ability.defaultPlayer()).restored();
    missStore.save(new PlayerState(missId, levelOne, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(3))));

    // At skill level 0: hit = DEFHIT 5, target speed = DEFSPEED 15.  Fourteen misses.
    try (WorldEngine world = engine(missStore, new SequenceRandom(14))) {
      WorldObjectSnapshot player = enter(world, missId, "未熟战士", LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(result.accepted());
      assertTrue(result.hitNothing(), "the accuracy roll must reject the adjacent target");
      assertEquals(target.ability().hp(), run(world, world.snapshot(target.id())).ability().hp());
    }

    UUID hitId = UUID.randomUUID();
    RecordingStore hitStore = new RecordingStore();
    hitStore.save(new PlayerState(hitId, levelOne, List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(3, 3, 0, 0))));
    // At skill level 3: hit = 5 + round(9 / 3 * 3) = 14.  Thirteen hits.
    try (WorldEngine world = engine(hitStore, new SequenceRandom(13, 0, 0))) {
      WorldObjectSnapshot player = enter(world, hitId, "熟练战士", LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(result.accepted());
      assertEquals(target.ability().hp() - result.damage(),
          run(world, world.snapshot(target.id())).ability().hp());
    }
  }

  private static StdItem skillBook(String name) {
    // StdMode 4 is a skill book.  The actual spell row is resolved by its exact name.
    return new StdItem(name, 4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 100);
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int job,
      List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(5, 5), Direction.RIGHT,
        0, 0, job, events::add));
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  private static final class SequenceRandom extends Random {
    private final int[] values;
    private int index;

    private SequenceRandom(int... values) {
      this.values = values;
    }

    @Override
    public int nextInt(int bound) {
      if (bound < 1) throw new IllegalArgumentException("bound must be positive");
      int value = index < values.length ? values[index++] : 0;
      return Math.floorMod(value, bound);
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

    @Override
    public long itemMakeIndexHighWater() {
      return 0L;
    }
  }
}
