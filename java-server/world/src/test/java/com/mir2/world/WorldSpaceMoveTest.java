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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * W50 skill slice: 瞬息移动 {@code SKILL_SPACEMOVE}(21, Magic.pas:495 → {@code MagSaceMove},
 * Magic.pas:951) — the last learnable row of Magic.DB 1–33 and the engine's first
 * map-displacement (random-teleport) subsystem.
 *
 * <p>Shape under test: the branch's own {@code RM_MAGICFIRE} (the only one in the 1–33 block
 * that also clears {@code boSpellFire}); the {@code Random(11) < btLevel * 2 + 4} gate and its
 * 「已扣蓝、已播帧、不训练」 failure shape; the {@code RM_SPACEMOVE_FIRE2} departure broadcast;
 * {@code MapRandomMove}'s margin-derived start cell and {@code SpaceMove.GetRandXY}'s 201-step
 * walkable search (including the Java-only empty-cell requirement); the
 * {@code RM_CLEAROBJECTS}/{@code RM_CHANGEMAP}/{@code RM_SPACEMOVE_SHOW2} landing sequence with
 * its scene rebuild; the home-map semantics ({@code m_sHomeMap} = the login map, so a caster who
 * crossed a gate is thrown back); and the observers' hide/show fan-out.
 */
class WorldSpaceMoveTest {
  private static final int SKILL_SPACEMOVE = 21;
  /** Row 21 {@code Delay = 50}: the shared 1350 ms interval plus the row's own delay. */
  private static final long CAST_INTERVAL = 1_400;
  /**
   * Row 21 {@code spell = 10, defSpell = 8}: {@code Round(10 / 4 * 1)} = 2, so 10 at level 0.
   */
  private static final int MANA_LV0 = 10;
  /** Level 19 wizard: row 21's NeedL1. */
  private static final int WIZARD_LEVEL = 19;
  /**
   * A 30x30 map puts {@code MapRandomMove}'s margin at 20 (height is not below 30) and
   * {@code GetRandXY}'s own margin at 2, so a zero-drawing Random starts the search at (20, 20).
   */
  private static final Position ZERO_DRAW_START = new Position(20, 20);
  /** {@code GetRandXY}'s step is 3 on maps narrower than 80 cells (ObjBase.pas:4362). */
  private static final int STEP = 3;

  private final AtomicLong now = new AtomicLong();

  // ---------------------------------------------------------------- success shape

  @Test
  void successfulCastSpendsManaBroadcastsTheCastFrameThenTeleports() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)),
          "an empty-ground click with targetId = 0 is a legal 瞬息移动 cast (Magic.pas:495)");

      assertEquals(mana - MANA_LV0, ability(world, wizard.id()).mp());
      assertEquals(ZERO_DRAW_START, run(world, world.snapshot(wizard.id())).position());

      // Magic.pas:496 — the branch broadcasts the cast frame itself.
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(0, fired.targetId(), "no object was clicked: the frame carries 0");

      // Order: cast frame → departure frame → landing (player map change) → arrival frame.
      assertTrue(indexOf(events, WorldEvent.MagicFired.class)
          < indexOf(events, WorldEvent.SpaceMoveHidden.class));
      assertTrue(indexOf(events, WorldEvent.SpaceMoveHidden.class)
          < indexOf(events, WorldEvent.PlayerMapChanged.class));
      assertTrue(indexOf(events, WorldEvent.PlayerMapChanged.class)
          < indexOf(events, WorldEvent.SpaceMoveShown.class));

      WorldEvent.SpaceMoveShown shown = one(events, WorldEvent.SpaceMoveShown.class);
      assertEquals(wizard.id(), shown.object().id());
      assertEquals(ZERO_DRAW_START, shown.object().position());
      assertEquals(wizard.id(), one(events, WorldEvent.SpaceMoveHidden.class).objectId());
      // MagSaceMove's Result := True is unconditional inside the gate.
      assertEquals(1, one(events, WorldEvent.SkillTrainingChanged.class).magic().skill()
          .trainingPoints(), "FixedRandom collapses TrainSkill's Random(3) + 1 to its minimum");
    }
  }

  @Test
  void failedGateLeavesTheCasterInPlaceWithoutTraining() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    // Random(11) = 10 fails even a level-3 cast (10 < 3 * 2 + 4 = 10 is false).
    try (WorldEngine world = engine(store, new ScriptedRandom(10),
        GameMap.empty("0", "PoC", 30, 30))) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      // The mana is gone and the cast pose went out — but nothing else happened at all.
      assertEquals(mana - MANA_LV0, ability(world, wizard.id()).mp());
      assertEquals(new Position(5, 5), run(world, world.snapshot(wizard.id())).position());
      one(events, WorldEvent.MagicFired.class);
      assertEquals(0, eventsOf(events, WorldEvent.SpaceMoveHidden.class).size());
      assertEquals(0, eventsOf(events, WorldEvent.SpaceMoveShown.class).size());
      assertEquals(0, eventsOf(events, WorldEvent.PlayerMapChanged.class).size());
      assertEquals(0, eventsOf(events, WorldEvent.SkillTrainingChanged.class).size());
    }
  }

  @Test
  void gateThresholdIsLevelTimesTwoPlusFour() {
    for (int level = 0; level <= MagicDefinition.MAX_SKILL_LEVEL; level++) {
      assertTrue(runCastWithGateRoll(level, level * 2 + 3),
          "level " + level + ": a roll below level * 2 + 4 succeeds");
      assertFalse(runCastWithGateRoll(level, level * 2 + 4),
          "level " + level + ": a roll at level * 2 + 4 fails (Random(11) < bound is strict)");
    }
  }

  /** Casts once with the gate roll pinned to {@code roll}; true when the caster moved. */
  private boolean runCastWithGateRoll(int skillLevel, int roll) {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, skillLevel, levelForSkillLevel(skillLevel)));
    try (WorldEngine world = engine(store, new ScriptedRandom(roll),
        GameMap.empty("0", "PoC", 30, 30))) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0));
      return !eventsOf(events, WorldEvent.SpaceMoveShown.class).isEmpty();
    }
  }

  // ---------------------------------------------------------------- landing search

  @Test
  void landingSearchStepsOverBlockedTerrain() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    GameMap map = GameMap.withBlockedCells("0", "PoC", 30, 30, List.of(ZERO_DRAW_START));
    try (WorldEngine world = engine(store, new FixedRandom(), map)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      // GetRandXY (ObjBase.pas:4360) rejects the blocked start cell and steps x by 3.
      assertEquals(new Position(ZERO_DRAW_START.x() + STEP, ZERO_DRAW_START.y()),
          run(world, world.snapshot(wizard.id())).position());
      assertEquals(new Position(ZERO_DRAW_START.x() + STEP, ZERO_DRAW_START.y()),
          one(events, WorldEvent.SpaceMoveShown.class).object().position());
    }
  }

  @Test
  void landingSearchAlsoSkipsOccupiedCells() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    GameMap map = GameMap.empty("0", "PoC", 30, 30);
    try (WorldEngine world = engine(store, new FixedRandom(), map)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      // Delphi's CanWalk(..., True) ignores actors, so a random move may land on somebody; the
      // Java cell model keeps one moving object per cell, so the search steps past them.
      run(world, world.spawnMonster(
          statue("木桩", 100, 50), "0", ZERO_DRAW_START, Direction.LEFT));

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      assertEquals(new Position(ZERO_DRAW_START.x() + STEP, ZERO_DRAW_START.y()),
          run(world, world.snapshot(wizard.id())).position());
    }
  }

  @Test
  void exhaustedSearchLeavesTheCasterInPlaceButStillTrains() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    // Every cell except the one the caster stands on is blocked: GetRandXY burns its 201
    // attempts (ObjBase.pas:4384) and SpaceMove restores the old coordinates — with no frames
    // at all beyond the departure one, while MagSaceMove still returns True.
    List<Position> blocked = new ArrayList<>();
    for (int x = 0; x < 30; x++) {
      for (int y = 0; y < 30; y++) {
        if (x != 5 || y != 5) blocked.add(new Position(x, y));
      }
    }
    GameMap map = GameMap.withBlockedCells("0", "PoC", 30, 30, blocked);
    try (WorldEngine world = engine(store, new FixedRandom(), map)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      assertEquals(new Position(5, 5), run(world, world.snapshot(wizard.id())).position());
      one(events, WorldEvent.SpaceMoveHidden.class);
      assertEquals(0, eventsOf(events, WorldEvent.SpaceMoveShown.class).size());
      assertEquals(0, eventsOf(events, WorldEvent.PlayerMapChanged.class).size());
      one(events, WorldEvent.SkillTrainingChanged.class);
    }
  }

  // ---------------------------------------------------------------- scene rebuild

  @Test
  void casterRebuildsItsSceneOnTheLandingCell() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    GameMap map = GameMap.empty("0", "PoC", 30, 30);
    try (WorldEngine world = engine(store, new FixedRandom(), map)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot neighbour = run(world, world.spawnMonster(
          statue("木桩", 100, 50), "0", new Position(21, 20), Direction.LEFT));
      GroundItem item = run(world, world.spawnGroundItem("金创药", 5, "0", new Position(21, 21)));
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      assertEquals(ZERO_DRAW_START, run(world, world.snapshot(wizard.id())).position());
      // Delphi refills the mover's scene from the next SearchViewRange sweep; the Java engine
      // pushes the same content explicitly, exactly as the gate teleport does.
      assertTrue(events.stream().anyMatch(
          event -> event instanceof WorldEvent.ObjectAppeared appeared
              && appeared.object().id() == neighbour.id()));
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.ItemAppeared appeared
          && appeared.item().id() == item.id()));
    }
  }

  // ---------------------------------------------------------------- observers

  @Test
  void observersSeeTheDepartureThenTheArrivalOnTheirOwnSide() {
    RecordingStore store = new RecordingStore();
    GameMap map = GameMap.empty("0", "PoC", 30, 30);
    try (WorldEngine world = engine(store, new FixedRandom(), map)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> originEvents = new ArrayList<>();
      List<WorldEvent> landingEvents = new ArrayList<>();
      UUID casterId = UUID.randomUUID();
      store.save(wizardWith(casterId, 0, WIZARD_LEVEL));
      WorldObjectSnapshot caster =
          enter(world, casterId, "法师", 5, 5, LevelAbilities.JOB_WIZARD, casterEvents);
      enter(world, UUID.randomUUID(), "原地目击者", 6, 6, LevelAbilities.JOB_WIZARD, originEvents);
      enter(world, UUID.randomUUID(), "落点目击者", 20, 22, LevelAbilities.JOB_WIZARD,
          landingEvents);
      casterEvents.clear();
      originEvents.clear();
      landingEvents.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));

      // Everyone in range of the old cell sees the departure; only the landing cell's observers
      // see the arrival, and the caster sees both.
      assertEquals(1, eventsOf(originEvents, WorldEvent.SpaceMoveHidden.class).size());
      assertEquals(0, eventsOf(originEvents, WorldEvent.SpaceMoveShown.class).size());
      assertEquals(0, eventsOf(landingEvents, WorldEvent.SpaceMoveHidden.class).size());
      assertEquals(1, eventsOf(landingEvents, WorldEvent.SpaceMoveShown.class).size());
      assertEquals(1, eventsOf(casterEvents, WorldEvent.SpaceMoveHidden.class).size());
      assertEquals(1, eventsOf(casterEvents, WorldEvent.SpaceMoveShown.class).size());
      // The origin observer's actor list is repaired with an explicit disappearance.
      assertTrue(originEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectDisappeared disappeared
              && disappeared.objectId() == caster.id()));
      assertTrue(landingEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.ObjectAppeared appeared
              && appeared.object().id() == caster.id()));
    }
  }

  // ---------------------------------------------------------------- home map

  @Test
  void castingAfterAGateCrossingReturnsTheCasterToItsHomeMap() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    GameMap home = GameMap.empty("0", "比奇省", 30, 30);
    GameMap dungeon = GameMap.empty("1", "沃玛寺庙", 30, 30);
    try (WorldEngine world = engine(store, new FixedRandom(), home, dungeon)) {
      assertTrue(run(world, world.addRoute(
          new TeleportRoute("0", new Position(7, 5), "1", new Position(3, 3)))));
      List<WorldEvent> events = new ArrayList<>();
      // m_sHomeMap is the map the player entered the world on — the map every login resolves
      // to through MIR2_MAP_ID — so 瞬息移动 throws the caster home from a dungeon.
      WorldObjectSnapshot wizard = enter(world, id, "法师", 7, 6, LevelAbilities.JOB_WIZARD, events);
      run(world, world.move(wizard.id(), new Position(7, 5), Direction.UP, MovementKind.WALK));
      assertEquals("1", run(world, world.snapshot(wizard.id())).mapId());
      now.addAndGet(CAST_INTERVAL);
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(5, 3), 0)));

      WorldObjectSnapshot landed = run(world, world.snapshot(wizard.id()));
      assertEquals("0", landed.mapId());
      assertEquals(ZERO_DRAW_START, landed.position());
      one(events, WorldEvent.PlayerMapChanged.class);
      one(events, WorldEvent.SpaceMoveShown.class);
    }
  }

  // ---------------------------------------------------------------- cast gates

  @Test
  void nonWizardIsRejectedBeforeAnythingElseHappens() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL, LevelAbilities.JOB_WARRIOR));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot warrior =
          enter(world, id, "战士", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      events.clear();

      assertFalse(run(world,
          world.castSpell(warrior.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(events, WorldEvent.SpellRejected.class).reason());
    }
  }

  @Test
  void sharedCastGatesStillApply() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    // The gate roll is pinned to a failure so the caster stays on (5, 5): the cooldown check
    // below then sees an in-range click instead of one that the relocation pushed out of range.
    try (WorldEngine world = engine(store, new ScriptedRandom(10),
        GameMap.empty("0", "PoC", 30, 30))) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      events.clear();

      // A click beyond g_Config.nMagicAttackRage never reaches the branch.
      assertFalse(run(world,
          world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(20, 20), 0)));
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE,
          one(events, WorldEvent.SpellRejected.class).reason());

      events.clear();
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));
      // The shared 1350 ms interval plus row 21's Delay = 50 still holds the next cast.
      now.addAndGet(CAST_INTERVAL - 1);
      events.clear();
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(8, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.TOO_FAST,
          one(events, WorldEvent.SpellRejected.class).reason());
    }
  }

  @Test
  void clickIsSnappedOntoANamedObjectWithinOneCell() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(wizardWith(id, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot statue = run(world, world.spawnMonster(
          statue("木桩", 100, 50), "0", new Position(8, 5), Direction.LEFT));
      events.clear();

      // CretInNearXY (ObjBase.pas:9227) runs before DoSpell: the clicked cell rides along on the
      // cast frame as the object's cell, and the object id becomes the frame's target.
      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_SPACEMOVE, new Position(8, 6), statue.id())));

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(statue.id(), fired.targetId());
    }
  }

  // ---------------------------------------------------------------- harness

  private WorldEngine deterministicEngine(PlayerStateStore store) {
    return engine(store, new FixedRandom(), GameMap.empty("0", "PoC", 30, 30));
  }

  private WorldEngine engine(PlayerStateStore store, Random random, GameMap... maps) {
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900,
        5_000, 180_000, 200, 10 * 60 * 1_000L, 0, 1, 2, 1, 50, false);
    return new WorldEngine(config, List.of(maps), now::get, random, store,
        ItemDatabase.of(StdItemsDb.all()));
  }

  /** Every draw collapses to its range minimum. */
  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  /** Pinned draws (one per {@code nextInt} call, in order), then the range minimum. */
  private static final class ScriptedRandom extends Random {
    private final int[] draws;
    private int index;

    ScriptedRandom(int... draws) {
      this.draws = draws;
    }

    @Override
    public int nextInt(int bound) {
      return index < draws.length ? draws[index++] : 0;
    }
  }

  private static MonsterTemplate statue(String name, int hp, long experience) {
    return new MonsterTemplate(name, 0, new Ability(
        hp, hp, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, LevelExperience.forLevel(1)),
        12, 600_000, 600_000, experience, MonsterBehavior.AGGRESSIVE, List.of(), List.of(),
        false, 0, 0, 0);
  }

  private static PlayerState wizardWith(UUID id, int skillLevel, int level) {
    return wizardWith(id, skillLevel, level, LevelAbilities.JOB_WIZARD);
  }

  /** Row 21's NeedL1..3 (19/22/25): the player must be high enough to cast at that level. */
  private static int levelForSkillLevel(int skillLevel) {
    return Math.max(WIZARD_LEVEL,
        MagicCatalog.defaults().require(SKILL_SPACEMOVE).requiredLevel(skillLevel));
  }

  private static PlayerState wizardWith(UUID id, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(SKILL_SPACEMOVE, skillLevel, 0, 0)));
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.DOWN,
        0, 0, job, events::add));
  }

  private static Ability ability(WorldEngine world, int objectId) {
    return run(world, world.snapshot(objectId)).ability();
  }

  private static <T extends WorldEvent> T one(List<WorldEvent> events, Class<T> type) {
    List<T> matches = eventsOf(events, type);
    assertEquals(1, matches.size(), "expected exactly one " + type.getSimpleName() + ": " + events);
    return matches.getFirst();
  }

  private static <T extends WorldEvent> List<T> eventsOf(List<WorldEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static int indexOf(List<WorldEvent> events, Class<? extends WorldEvent> type) {
    for (int index = 0; index < events.size(); index++) {
      if (type.isInstance(events.get(index))) return index;
    }
    return -1;
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static final class RecordingStore implements PlayerStateStore {
    private final Map<UUID, PlayerState> states = new HashMap<>();

    @Override
    public Optional<PlayerState> load(UUID id) {
      return Optional.ofNullable(states.get(id));
    }

    @Override
    public void save(PlayerState state) {
      states.put(state.characterId(), state);
    }
  }
}
