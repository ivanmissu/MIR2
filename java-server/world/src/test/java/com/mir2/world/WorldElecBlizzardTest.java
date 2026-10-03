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
import org.junit.jupiter.api.Test;

/** W43: source-driven world semantics for 地狱雷光 ({@code SKILL_LIGHTFLOWER}=24). */
class WorldElecBlizzardTest {
  private static final int SKILL_LIGHTFLOWER = 24;
  /** {@code GetSpellPoint} of Magic.DB row 24 at skill level zero: rint(35/4) + 20. */
  private static final int MANA_COST = 29;

  private final AtomicLong now = new AtomicLong();

  @Test
  void theBlastIsCasterCentredAndUndeadTakeTheFullRoll() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    // Magic.DB row 24 requires level 30 for the first training level.
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          events);
      WorldObjectSnapshot undead = spawn(world, "稻草人", 5, 7, 0, true);
      WorldObjectSnapshot living = spawn(world, "木桩", 7, 5, 0, false);
      // Three cells from the caster and three from the click: outside the square either way.
      WorldObjectSnapshot outside = spawn(world, "范围外木桩", 8, 5, 0, false);
      // Four cells from the caster even though the click lands exactly on it.
      WorldObjectSnapshot nearClick = spawn(world, "点击处木桩", 10, 5, 0, false);
      events.clear();

      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER, new Position(10, 5), 0)));

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(10, 5), fired.target(),
          "MagElecBlizzard never reads the click, but RM_MAGICFIRE still carries it");
      assertEquals(0, fired.targetId());
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp());

      List<WorldEvent.ObjectStruck> struck = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).toList();
      assertEquals(Set.of(undead.id(), living.id()),
          struck.stream().map(hit -> hit.victim().id())
              .collect(java.util.stream.Collectors.toSet()),
          "only the caster-centred square is hit; the click cell is irrelevant");
      assertTrue(struck.stream().allMatch(WorldEvent.ObjectStruck::magical),
          "RM_MAGSTRUCK must carry the magical hit marker");

      int undeadDamage = struck.stream().filter(hit -> hit.victim().id() == undead.id())
          .findFirst().orElseThrow().damage();
      int livingDamage = struck.stream().filter(hit -> hit.victim().id() == living.id())
          .findFirst().orElseThrow().damage();
      assertTrue(undeadDamage > 9, "the undead branch must carry the rolled nPower");
      assertEquals(undeadDamage / 10, livingDamage,
          "living targets receive nPower div 10; both dummies have zero MAC");
      assertEquals(1, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count(), "DoSpell trains once after any proper area target");
      assertEquals(1, run(world, world.skills(caster.id())).size());

      assertEquals(run(world, world.snapshot(caster.id())).ability().maxHp(),
          run(world, world.snapshot(caster.id())).ability().hp(),
          "IsAttackTarget excludes Self, so the caster is never caught in the blizzard");
      assertEquals(run(world, world.snapshot(outside.id())).ability().maxHp(),
          run(world, world.snapshot(outside.id())).ability().hp());
      assertEquals(run(world, world.snapshot(nearClick.id())).ability().maxHp(),
          run(world, world.snapshot(nearClick.id())).ability().hp());
    }
  }

  @Test
  void aSnappedClickOnlySteersTheBroadcast() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          events);
      WorldObjectSnapshot clicked = spawn(world, "点击木桩", 8, 5, 0, false);
      WorldObjectSnapshot inside = spawn(world, "近身木桩", 6, 4, 0, false);
      events.clear();

      // CretInNearXY snaps the click at (7,5) onto the object at (8,5).
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER, new Position(7, 5),
          clicked.id())));

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(clicked.id(), fired.targetId());
      List<WorldEvent.ObjectStruck> struck = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).toList();
      assertEquals(List.of(inside.id()), struck.stream().map(hit -> hit.victim().id()).toList(),
          "the snapped click is three cells away from the caster and stays unharmed");
    }
  }

  @Test
  void theObjectBoundImpactSurvivesLeavingTheSquare() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));
    store.save(state(victimId, LevelAbilities.JOB_WARRIOR, 30, 0));

    WorldEngine.Config config = config(2);
    WorldClock clock = WorldClock.manual(50);
    try (WorldEngine world = new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)),
        clock, WorldRandom.of(new FixedRandom()), store, ItemDatabase.of(StdItemsDb.all()),
        () -> 12)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          casterEvents);
      WorldObjectSnapshot victim = enter(world, victimId, "战士", 6, 6, LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      int before = run(world, world.snapshot(victim.id())).ability().hp();
      casterEvents.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER,
          new Position(6, 6), 0)));
      assertTrue(run(world, world.snapshot(victim.id())).ability().hp() == before,
          "manual worlds defer ordinary SendMsg effects until the next pumped tick");
      assertTrue(run(world, world.move(victim.id(), new Position(6, 8), Direction.DOWN,
          MovementKind.RUN)).moved());
      casterEvents.clear();

      var advancing = world.advanceTicks(1);
      world.tickOnce();
      advancing.join();

      assertTrue(run(world, world.snapshot(victim.id())).ability().hp() < before,
          "RM_MAGSTRUCK is addressed to the object, not to its old square cell");
      WorldEvent.ObjectStruck struck = one(casterEvents, WorldEvent.ObjectStruck.class);
      assertTrue(struck.magical());
    }
  }

  @Test
  void theConfiguredRadiusIsInclusiveAndEmptyGroundDoesNotTrain() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          events);
      WorldObjectSnapshot boundary = spawn(world, "半径二边界", 3, 5, 0, false);
      WorldObjectSnapshot outside = spawn(world, "半径二外", 2, 5, 0, false);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER, new Position(5, 5), 0)));
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == boundary.id()));
      assertFalse(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == outside.id()), "the square is inclusive but not wider");
    }

    Store emptyStore = new Store();
    UUID loneId = UUID.randomUUID();
    emptyStore.save(state(loneId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));
    try (WorldEngine world = engine(config(2), emptyStore)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, loneId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          events);
      events.clear();
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      int skillsBefore = run(world, world.skills(caster.id())).getFirst().trainingPoints();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER, new Position(5, 9), 0)));

      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp(),
          "an empty square still spends the mana and fires the RM_MAGICFIRE");
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count(), "boTrain needs at least one proper target");
      assertEquals(skillsBefore, run(world, world.skills(caster.id())).getFirst().trainingPoints());
    }
  }

  @Test
  void gridBoundaryClippingMatchesGetMapBaseObjects() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 30, SKILL_LIGHTFLOWER));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 0, 0, LevelAbilities.JOB_WIZARD,
          events);
      WorldObjectSnapshot corner = spawn(world, "角落木桩", 2, 2, 0, false);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_LIGHTFLOWER, new Position(0, 0), 0)));
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == corner.id()),
          "the square is clipped at the map edge instead of overflowing");
    }
  }

  @Test
  void elecBlizzardRangeRejectsValuesOutsideDelphiSpinEditLimits() {
    assertEquals(2, config(2).elecBlizzardRange(), "M2Share.pas ships nElecBlizzardRange = 2");
    assertEquals(1, config(1).elecBlizzardRange());
    assertEquals(12, config(12).elecBlizzardRange());
    assertThrows(IllegalArgumentException.class, () -> config(0));
    assertThrows(IllegalArgumentException.class, () -> config(13));
  }

  // ------------------------------------------------------------------ helpers

  private static WorldEngine.Config config(int radius) {
    return new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000,
        200, 600_000, 0, 1, radius);
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store) {
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.DOWN,
        0, 0, job, events::add));
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y, int mac,
      boolean undead) {
    Ability ability = new Ability(100_000, 100_000, 0, 0, 0, 0, 0, 0, mac, mac, 0, 0,
        0, 0, 1, 0, 0);
    MonsterTemplate target = new MonsterTemplate(name, 0, ability, 1, 1_000_000, 1_000_000,
        0, MonsterBehavior.STATIONARY, List.of(), List.of(), undead);
    return run(world, world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN));
  }

  private PlayerState state(UUID id, int job, int level, int magicId) {
    List<PlayerSkill> skills = magicId == 0 ? List.of() : List.of(PlayerSkill.learned(magicId));
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

  /** Collapses every roll to its minimum, exactly like the FireBoom/line-piercing tests. */
  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
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
