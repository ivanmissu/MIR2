package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * W44: source-driven world semantics for 冰咆哮 ({@code SKILL_SNOWWIND}=33).
 *
 * <p>{@code Magic.pas:578} sends 冰咆哮 into the very same {@code MagBigExplosion} body as 爆裂火焰
 * ({@code Magic.pas:510}); the only difference in the Delphi source is the last argument —
 * {@code g_Config.nSnowWindRange} (M2Share.pas:2078, ships at 1) instead of
 * {@code g_Config.nFireBoomRage}. These tests therefore pin the shared shape <em>and</em> the one
 * thing that is genuinely SnowWind's own: the radius it reads.
 */
class WorldSnowWindTest {
  private static final int SKILL_SNOWWIND = 33;
  private static final int SKILL_FIREBOOM = 23;
  /** {@code GetSpellPoint} at level zero: ROUND(12 / 4 * 1) + defSpell(30). */
  private static final int MANA_COST = 33;
  /** {@code Magic.DB} row 33: {@code NeedL1 = 35}. */
  private static final int WIZARD_LEVEL = 35;

  private final AtomicLong now = new AtomicLong();

  @Test
  void oneSharedPowerHitsTheInclusiveSquareAndTrainsOnce() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL, SKILL_SNOWWIND));
    FixedRandom random = new FixedRandom();

    try (WorldEngine world = engine(config(1), store, random)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot center = spawn(world, "中心木桩", 7, 5);
      WorldObjectSnapshot edge = spawn(world, "边界木桩", 8, 6, 2);
      WorldObjectSnapshot otherEdge = spawn(world, "另一边界木桩", 6, 4);
      WorldObjectSnapshot outside = spawn(world, "范围外木桩", 9, 5);
      events.clear();

      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      random.reset();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND, new Position(7, 5), 0)));
      assertEquals(1 + 3 + 1, random.draws(),
          "one power roll, one low-level stagger roll per hit and one training roll");
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp());

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(7, 5), fired.target());
      assertEquals(0, fired.targetId(), "a ground click need not name an object");

      List<WorldEvent.ObjectStruck> struck = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).toList();
      assertEquals(Set.of(center.id(), edge.id(), otherEdge.id()),
          struck.stream().map(hit -> hit.victim().id()).collect(Collectors.toSet()),
          "the default radius-one square is the inclusive 3x3 around the click");
      assertTrue(struck.stream().allMatch(WorldEvent.ObjectStruck::magical),
          "RM_MAGSTRUCK must carry the magical hit marker");
      int centerDamage = struck.stream().filter(hit -> hit.victim().id() == center.id())
          .findFirst().orElseThrow().damage();
      assertEquals(centerDamage - 2, struck.stream().filter(hit -> hit.victim().id() == edge.id())
          .findFirst().orElseThrow().damage(),
          "the same nPower is shared, then each target gets its own MAC roll");
      assertEquals(centerDamage, struck.stream().filter(hit -> hit.victim().id() == otherEdge.id())
          .findFirst().orElseThrow().damage());
      assertEquals(1, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count(),
          "DoSpell trains once after any proper area target, not once per victim");

      assertEquals(Ability.monster(100_000, 0, 0, 0, 0).maxHp() - centerDamage,
          run(world, world.snapshot(center.id())).ability().hp());
      assertEquals(Ability.monster(100_000, 0, 0, 0, 0).maxHp(),
          run(world, world.snapshot(outside.id())).ability().hp(), "outside target is untouched");
    }
  }

  @Test
  void aSnappedClickKeepsItsObjectBoundImpactWhenTheVictimMovesAway() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL, SKILL_SNOWWIND));
    store.save(state(targetId, LevelAbilities.JOB_WARRIOR, 40, 0));

    // A manual clock freezes the periodic half of the tick, so the queued RM_MAGSTRUCK can be
    // pumped *after* the victim has walked out of the blast square.
    try (WorldEngine world = manualEngine(config(1), store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, casterEvents);
      WorldObjectSnapshot target = enter(world, targetId, "战士", 8, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      int before = run(world, world.snapshot(target.id())).ability().hp();
      casterEvents.clear();

      // CretInNearXY snaps the click at (7,5) onto the named object at (8,5).
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND,
          new Position(7, 5), target.id())));
      WorldEvent.MagicFired fired = one(casterEvents, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(target.id(), fired.targetId());

      assertTrue(run(world, world.move(target.id(), new Position(10, 5), Direction.RIGHT,
          MovementKind.RUN)).moved());
      assertEquals(new Position(10, 5), run(world, world.snapshot(target.id())).position(),
          "the victim is three cells from the snapped centre before the impact is pumped");
      assertTrue(run(world, world.snapshot(target.id())).ability().hp() == before,
          "a manual world defers ordinary SendMsg effects until the next pumped tick");
      casterEvents.clear();

      var advancing = world.advanceTicks(1);
      world.tickOnce();
      advancing.join();

      assertTrue(run(world, world.snapshot(target.id())).ability().hp() < before,
          "RM_MAGSTRUCK is bound to the selected object, not its old blast-square cell");
      WorldEvent.ObjectStruck struck = one(casterEvents, WorldEvent.ObjectStruck.class);
      assertEquals(target.id(), struck.victim().id());
      assertTrue(struck.magical());
    }
  }

  @Test
  void configuredRadiusIncludesItsBoundaryAndEmptyGroundDoesNotTrain() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL, SKILL_SNOWWIND));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot boundary = spawn(world, "半径二边界", 9, 5);
      WorldObjectSnapshot outside = spawn(world, "半径二外", 10, 5);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND, new Position(7, 5), 0)));
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == boundary.id()));
      assertFalse(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == outside.id()), "the configured square is inclusive but not wider");

      // Another legal empty-ground click: its square holds no object, so the cast still spends
      // mana and broadcasts RM_MAGICFIRE but leaves DoSpell's boTrain unset.
      now.addAndGet(1_500);
      events.clear();
      int skillsBefore = run(world, world.skills(caster.id())).getFirst().trainingPoints();
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND, new Position(5, 9), 0)));
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count());
      assertEquals(skillsBefore, run(world, world.skills(caster.id())).getFirst().trainingPoints());
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp(),
          "an empty square still costs the SnowWind mana");
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(5, 9), fired.target());
    }
  }

  @Test
  void snowWindReadsItsOwnConfiguredRadiusNotFireBooms() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL,
        List.of(PlayerSkill.learned(SKILL_FIREBOOM), PlayerSkill.learned(SKILL_SNOWWIND))));

    // FireBoom stays at its shipped radius of one while 冰咆哮 is configured at three.
    try (WorldEngine world = engine(config(1, 3), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot near = spawn(world, "半径一内", 8, 5);
      WorldObjectSnapshot far = spawn(world, "半径三边界", 7, 8);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND, new Position(7, 5), 0)));
      Set<Integer> snowWindVictims = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).map(hit -> hit.victim().id())
          .collect(Collectors.toSet());
      assertEquals(Set.of(near.id(), far.id()), snowWindVictims,
          "nSnowWindRange=3 reaches the (7,8) boundary three cells from the click");

      now.addAndGet(1_500);
      events.clear();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM, new Position(7, 5), 0)));
      Set<Integer> fireBoomVictims = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).map(hit -> hit.victim().id())
          .collect(Collectors.toSet());
      assertEquals(Set.of(near.id()), fireBoomVictims,
          "the very same click under nFireBoomRage=1 stops one cell short of (7,8)");
    }
  }

  @Test
  void snowWindLocksTheStruckMonsterOntoTheCaster() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID decoyId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL, SKILL_SNOWWIND));
    store.save(state(decoyId, LevelAbilities.JOB_WARRIOR, 40, 0));

    try (WorldEngine world = engine(config(1), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 10, 13,
          LevelAbilities.JOB_WIZARD, events);
      // The decoy stands adjacent to the monster, so without SetTargetCreat the creature would
      // chase (and attack) him instead of the caster three cells away.
      enter(world, decoyId, "战士", 9, 10, LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot monster = spawnChaser(world, "追击木桩", 10, 10);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SNOWWIND,
          new Position(10, 10), monster.id())));
      // The stagger the RM_MAGSTRUCK handler adds to a sub-level-50 monster is 800 + Random(1000).
      now.addAndGet(2_000);
      run(world, world.snapshot(monster.id()));

      assertEquals(new Position(10, 11), run(world, world.snapshot(monster.id())).position(),
          "MagBigExplosion's SetTargetCreat makes the struck monster chase the caster southwards");
    }
  }

  @Test
  void snowWindIsRefusedForEveryJobButWizard() {
    Store store = new Store();
    UUID warriorId = UUID.randomUUID();
    UUID taoistId = UUID.randomUUID();
    UUID juniorId = UUID.randomUUID();
    store.save(state(warriorId, LevelAbilities.JOB_WARRIOR, WIZARD_LEVEL, SKILL_SNOWWIND));
    store.save(state(taoistId, LevelAbilities.JOB_TAOIST, WIZARD_LEVEL, SKILL_SNOWWIND));
    store.save(state(juniorId, LevelAbilities.JOB_WIZARD, 34, SKILL_SNOWWIND));

    try (WorldEngine world = engine(config(1), store)) {
      List<WorldEvent> warriorEvents = new ArrayList<>();
      List<WorldEvent> taoistEvents = new ArrayList<>();
      List<WorldEvent> juniorEvents = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, warriorId, "战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, warriorEvents);
      WorldObjectSnapshot taoist = enter(world, taoistId, "道士", 5, 7,
          LevelAbilities.JOB_TAOIST, taoistEvents);
      WorldObjectSnapshot junior = enter(world, juniorId, "小学徒", 5, 9,
          LevelAbilities.JOB_WIZARD, juniorEvents);

      // Magic.DB row 33 is job = 1 (法师); the warrior and taoist rows stay unimplemented.
      assertFalse(run(world,
          world.castSpell(warrior.id(), SKILL_SNOWWIND, new Position(7, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(warriorEvents, WorldEvent.SpellRejected.class).reason());
      assertFalse(run(world,
          world.castSpell(taoist.id(), SKILL_SNOWWIND, new Position(7, 7), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(taoistEvents, WorldEvent.SpellRejected.class).reason());

      // NeedL1 = 35: a level-34 wizard may own the row but cannot fire it.
      assertFalse(run(world,
          world.castSpell(junior.id(), SKILL_SNOWWIND, new Position(7, 9), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(juniorEvents, WorldEvent.SpellRejected.class).reason());
    }
  }

  @Test
  void snowWindRangeRejectsValuesOutsideDelphiSpinEditLimits() {
    assertEquals(1, config(1).snowWindRange());
    assertEquals(12, config(12).snowWindRange());
    // FireBoom keeps its own shipped radius no matter what SnowWind is configured at.
    assertEquals(1, config(12).fireBoomRange());
    assertThrows(IllegalArgumentException.class, () -> config(0));
    assertThrows(IllegalArgumentException.class, () -> config(13));
  }

  // ------------------------------------------------------------------ helpers

  /** Config with only the SnowWind radius pinned; FireBoom/ElecBlizzard keep Delphi defaults. */
  private static WorldEngine.Config config(int snowWindRadius) {
    return config(1, snowWindRadius);
  }

  private static WorldEngine.Config config(int fireBoomRadius, int snowWindRadius) {
    return new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000,
        200, 600_000, 0, fireBoomRadius, 2, snowWindRadius);
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store) {
    return engine(config, store, new FixedRandom());
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store, Random random) {
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
  }

  /**
   * The same world under a {@link WorldClock.Mode#MANUAL} clock: {@code tickOnce()} drains
   * commands but leaves the periodic half to {@link WorldEngine#advanceTicks(int)}, which is
   * what lets a test move a victim between the cast and the delivery of its {@code RM_MAGSTRUCK}.
   */
  private WorldEngine manualEngine(WorldEngine.Config config, PlayerStateStore store) {
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)),
        WorldClock.manual(50), WorldRandom.of(new FixedRandom()), store,
        ItemDatabase.of(StdItemsDb.all()), () -> 12);
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.DOWN,
        0, 0, job, events::add));
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y) {
    return spawn(world, name, x, y, 0);
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y, int mac) {
    Ability ability = new Ability(100_000, 100_000, 0, 0, 0, 0, 0, 0, mac, mac, 0, 0,
        0, 0, 1, 0, 0);
    MonsterTemplate target = new MonsterTemplate(name, 0, ability, 1, 1_000_000, 1_000_000,
        0, List.of());
    return run(world, world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN));
  }

  /**
   * An {@link MonsterBehavior#AGGRESSIVE} dummy that walks within the test's virtual clock
   * (50 ms per step) but still never attacks (interval far above anything the test advances),
   * so the only thing it can do is chase whatever {@code m_TargetCret} holds.
   */
  private WorldObjectSnapshot spawnChaser(WorldEngine world, String name, int x, int y) {
    Ability ability = new Ability(100_000, 100_000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 1, 0, 0);
    MonsterTemplate chaser = new MonsterTemplate(name, 0, ability, 12, 50, 1_000_000, 0,
        MonsterBehavior.AGGRESSIVE, List.of());
    return run(world, world.spawnMonster(chaser, "0", new Position(x, y), Direction.DOWN));
  }

  private PlayerState state(UUID id, int job, int level, int magicId) {
    return state(id, job, level, magicId == 0 ? List.of() : List.of(PlayerSkill.learned(magicId)));
  }

  private PlayerState state(UUID id, int job, int level, List<PlayerSkill> skills) {
    return new PlayerState(id, LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored(),
        List.of(), Equipment.empty(), 0, 0, 0, skills);
  }

  private static <T extends WorldEvent> T one(List<WorldEvent> events, Class<T> type) {
    List<T> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matches.size(), "expected exactly one " + type.getSimpleName() + ": " + events);
    return matches.getFirst();
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static final class FixedRandom extends Random {
    private int draws;

    @Override
    public int nextInt(int bound) {
      draws++;
      return 0;
    }

    void reset() {
      draws = 0;
    }

    int draws() {
      return draws;
    }
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
