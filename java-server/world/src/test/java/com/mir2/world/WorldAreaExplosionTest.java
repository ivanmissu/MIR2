package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** W42: source-driven world semantics for 爆裂火焰 ({@code SKILL_FIREBOOM}=23). */
class WorldAreaExplosionTest {
  private static final int SKILL_FIREBOOM = 23;
  private static final int SKILL_MAGIC_SHIELD = 31;
  private static final int MANA_COST = 14;

  private final AtomicLong now = new AtomicLong();

  @Test
  void oneSharedPowerHitsTheInclusiveSquareAndTrainsOnce() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 22, SKILL_FIREBOOM));
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
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM, new Position(7, 5), 0)));
      assertEquals(1 + 3 + 1, random.draws(),
          "one power roll, one low-level stagger roll per hit and one training roll");

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(7, 5), fired.target());
      assertEquals(0, fired.targetId(), "a ground click need not name an object");
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp());

      List<WorldEvent.ObjectStruck> struck = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).toList();
      assertEquals(Set.of(center.id(), edge.id(), otherEdge.id()),
          struck.stream().map(hit -> hit.victim().id()).collect(java.util.stream.Collectors.toSet()),
          "the inclusive 3x3 square hits each live proper target, but not the next cell");
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

      int hp = run(world, world.snapshot(center.id())).ability().hp();
      assertEquals(Ability.monster(100_000, 0, 0, 0, 0).maxHp() - centerDamage, hp);
      assertEquals(Ability.monster(100_000, 0, 0, 0, 0).maxHp(),
          run(world, world.snapshot(outside.id())).ability().hp(), "outside target is untouched");
    }
  }

  @Test
  void aSnappedClickKeepsItsObjectBoundImpactWhenTheVictimMovesAway() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 22, SKILL_FIREBOOM));
    store.save(state(targetId, LevelAbilities.JOB_WARRIOR, 40, 0));

    WorldEngine.Config config = config(1);
    WorldClock clock = WorldClock.manual(50);
    try (WorldEngine world = new WorldEngine(config,
        List.of(GameMap.empty("0", "PoC", 30, 30)), clock,
        WorldRandom.of(new FixedRandom()), store, ItemDatabase.of(StdItemsDb.all()), () -> 12)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, casterEvents);
      WorldObjectSnapshot target = enter(world, targetId, "战士", 8, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      int before = run(world, world.snapshot(target.id())).ability().hp();
      casterEvents.clear();

      // CretInNearXY snaps the click at (7,5) to the named object at (8,5).
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM,
          new Position(7, 5), target.id())));
      WorldEvent.MagicFired fired = one(casterEvents, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(target.id(), fired.targetId());

      // FixedRandom always returns zero: Random(10) >= the player's base AntiMagic(1) would
      // reject a resist-checked bolt. FireBoom deliberately has no such resist gate.
      assertTrue(run(world, world.snapshot(target.id())).ability().hp() == before,
          "manual worlds defer ordinary SendMsg effects until the next pumped tick");
      assertTrue(run(world, world.move(target.id(), new Position(10, 5), Direction.RIGHT,
          MovementKind.RUN)).moved());
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
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 22, SKILL_FIREBOOM));

    try (WorldEngine world = engine(config(2), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot boundary = spawn(world, "半径二边界", 9, 5);
      WorldObjectSnapshot outside = spawn(world, "半径二外", 10, 5);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM, new Position(7, 5), 0)));
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == boundary.id()));
      assertFalse(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectStruck hit
          && hit.victim().id() == outside.id()), "the configured square is inclusive but not wider");

      // Use another legal empty-ground click; its square contains no other
      // object, so the cast still spends mana/fires but does not set DoSpell's boTrain flag.
      now.addAndGet(1_500);
      events.clear();
      int skillsBefore = run(world, world.skills(caster.id())).getFirst().trainingPoints();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM, new Position(5, 9), 0)));
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count());
      assertEquals(skillsBefore, run(world, world.skills(caster.id())).getFirst().trainingPoints());
    }
  }

  @Test
  void aLiveMagicShieldReducesTheSharedExplosionDamagePerTarget() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID shieldedId = UUID.randomUUID();
    UUID unshieldedId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 31, SKILL_FIREBOOM));
    store.save(state(shieldedId, LevelAbilities.JOB_WIZARD, 31, SKILL_MAGIC_SHIELD));
    store.save(state(unshieldedId, LevelAbilities.JOB_WIZARD, 31, 0));

    try (WorldEngine world = engine(config(1), store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot shielded = enter(world, shieldedId, "有盾法师", 7, 5,
          LevelAbilities.JOB_WIZARD, new ArrayList<>());
      WorldObjectSnapshot unshielded = enter(world, unshieldedId, "无盾法师", 8, 5,
          LevelAbilities.JOB_WIZARD, new ArrayList<>());

      assertTrue(run(world, world.castSpell(shielded.id(), SKILL_MAGIC_SHIELD,
          shielded.position(), shielded.id())));
      assertTrue(run(world, world.magicShieldActive(shielded.id())));
      int shieldedHp = run(world, world.snapshot(shielded.id())).ability().hp();
      int unshieldedHp = run(world, world.snapshot(unshielded.id())).ability().hp();
      now.addAndGet(1_500);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREBOOM,
          new Position(7, 5), 0)));

      int shieldedDamage = shieldedHp - run(world, world.snapshot(shielded.id())).ability().hp();
      int unshieldedDamage = unshieldedHp - run(world, world.snapshot(unshielded.id())).ability().hp();
      assertTrue(shieldedDamage > 0, "the shield absorbs only part of a penetrating hit");
      assertTrue(shieldedDamage < unshieldedDamage,
          "RM_MAGSTRUCK applies the live magic-shield reduction after target MAC");
      List<WorldEvent.ObjectStruck> hits = events.stream()
          .filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast).toList();
      assertEquals(2, hits.size());
      assertTrue(hits.stream().allMatch(WorldEvent.ObjectStruck::magical));
    }
  }

  @Test
  void fireBoomRangeRejectsValuesOutsideDelphiSpinEditLimits() {
    assertEquals(1, config(1).fireBoomRange());
    assertEquals(12, config(12).fireBoomRange());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> config(0));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> config(13));
  }

  // ------------------------------------------------------------------ helpers

  private static WorldEngine.Config config(int radius) {
    return new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000,
        200, 600_000, 0, radius);
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store) {
    return engine(config, store, new FixedRandom());
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store, Random random) {
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
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
