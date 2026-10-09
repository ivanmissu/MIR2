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
 * W38 skill batch: 地狱火 ({@code SKILL_FIRE} = 9, Magic.pas:387) and 疾光电影 ({@code
 * SKILL_SHOOTLIGHTEN} = 10, Magic.pas:397), the two line-piercing wizard bolts sharing
 * {@code TBaseObject.MagPassThroughMagic} (ObjBase.pas:2536). The engine's first no-target
 * ground-click skills: the client may aim at empty map cells, and every object standing on the
 * beam's cells takes a 600 ms-delayed RM_MAGSTRUCK bound to the object, not the cell.
 */
class WorldLinePiercingSkillTest {
  private static final int SKILL_FIRE = 9;
  private static final int SKILL_SHOOTLIGHTEN = 10;
  /** 瞬息移动 (Magic.DB row 21, wizard, NeedL1 21): still outside the implemented set (red line). */
  private static final int SKILL_SPACEMOVE = 21;

  private final AtomicLong now = new AtomicLong();

  @Test
  void fireBeamStrikesEveryCellUpToItsReachAndBroadcastsTheBeamEnd() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot first = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(7, 5), Direction.LEFT));
      WorldObjectSnapshot atEnd = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(10, 5), Direction.LEFT));
      WorldObjectSnapshot beyond = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(11, 5), Direction.LEFT));
      int hp = run(world, world.snapshot(beyond.id())).ability().hp();
      int mana = run(world, world.snapshot(wizard.id())).ability().mp();
      events.clear();

      // A ground click with no target id at all — the defining shape of these two skills.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(11, 5), 0)));
      // Magic.DB id 9: manaCost(0) = Round(10/4*1) + 10 = 12.
      assertEquals(mana - 12, run(world, world.snapshot(wizard.id())).ability().mp());

      // Magic.pas:389 mutates nTargetX/nTargetY to the beam end before RM_MAGICFIRE goes out.
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(10, 5), fired.target(), "the packet rides the beam end, not the click");
      assertEquals(0, fired.targetId(), "nothing was clicked, so no object is named");
      assertEquals(SKILL_FIRE, fired.magic().id());

      // RM_MAGSTRUCK is delivered after 600 ms (ObjBase.pas:2554) — nothing is immediate.
      assertEquals(hp, run(world, world.snapshot(first.id())).ability().hp());
      assertEquals(hp, run(world, world.snapshot(atEnd.id())).ability().hp());
      now.addAndGet(600);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(first.id())).ability().hp() < hp,
          "a target two cells ahead is on the beam");
      assertTrue(run(world, world.snapshot(atEnd.id())).ability().hp() < hp,
          "the beam-end cell itself is checked before the walk stops");
      assertEquals(hp, run(world, world.snapshot(beyond.id())).ability().hp(),
          "the beam stops exactly at its 5-cell reach");
      assertEquals(2, events.stream().filter(WorldEvent.ObjectStruck.class::isInstance).count());
    }
  }

  @Test
  void shootLightenCompoundsTheUndeadMultiplierOncePerProperTarget() {
    // ObjBase.pas:2550 — `magpwr := Round(magpwr * 1.5)` mutates the loop variable itself, so
    // every proper target — undead or not — leaves the beam stronger for the cells behind it.
    // FixedRandom collapses the wide power/MC bands to their minimum so the compound chain is
    // exact: P, Round(P*1.5), Round(Round(P*1.5)*1.5), ...
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 26, SKILL_SHOOTLIGHTEN));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot one = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(7, 5), Direction.LEFT));
      WorldObjectSnapshot two = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(9, 5), Direction.LEFT));
      WorldObjectSnapshot three = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(11, 5), Direction.LEFT));

      int minMc = run(world, world.snapshot(wizard.id())).ability().minMc();
      // getMagicPower(magicId 10, level 0, MPow floor): scalePower rint(12/4*1)=3 and the flat
      // def-range 12..12, then `+ LoWord(MC)` with zero additional MC draw under FixedRandom.
      int power = 3 + 12 + minMc;
      int first = (int) Math.rint(power * 1.5);
      int second = (int) Math.rint(first * 1.5);
      int third = (int) Math.rint(second * 1.5);
      int hp = run(world, world.snapshot(one.id())).ability().hp();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SHOOTLIGHTEN, new Position(13, 5), 0)));
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(13, 5), fired.target(), "疾光电影 reaches the +8 cell");

      now.addAndGet(600);
      world.tickOnce();
      assertEquals(first, hp - run(world, world.snapshot(one.id())).ability().hp());
      assertEquals(second, hp - run(world, world.snapshot(two.id())).ability().hp());
      assertEquals(third, hp - run(world, world.snapshot(three.id())).ability().hp());
      assertTrue(third > second && second > first, "the mutation *compounds* along the beam");
    }
  }

  @Test
  void beamPunchesThroughBlockedTerrainAndNpcCells() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));

    try (WorldEngine world = engine(store, List.of(new Position(7, 5), new Position(8, 5)))) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot npc = run(world, world.spawnNpc(
          "老兵", "0", new Position(6, 5), 3, Direction.DOWN));
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(9, 5), Direction.LEFT));
      int hp = run(world, world.snapshot(dummy.id())).ability().hp();
      events.clear();

      // GetNextPosition never consults walkability (Envir.pas:1110) — walls do not shade the
      // beam; an NPC is simply not a proper target, and it does not block the cells behind it.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(12, 5), 0)));
      now.addAndGet(600);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(dummy.id())).ability().hp() < hp,
          "blocked terrain between caster and target must not shade the beam");
      assertTrue(events.stream().filter(WorldEvent.ObjectStruck.class::isInstance)
          .map(WorldEvent.ObjectStruck.class::cast)
          .noneMatch(struck -> struck.victim().id() == npc.id()),
          "IsAttackTarget is False for TNormNpc/TMerchant — the NPC is not struck");
    }
  }

  @Test
  void mapEdgeForeshortensTheBeamToTheLastInMapCells() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 29, 25, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(29, 27), Direction.LEFT));
      WorldObjectSnapshot south = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(29, 29), Direction.LEFT));
      int hp = run(world, world.snapshot(dummy.id())).ability().hp();
      int southHp = run(world, world.snapshot(south.id())).ability().hp();
      events.clear();

      // The wizard clicks south from (29,25); the +5 GetNextPosition (y=30) leaves the 30-row
      // map, so Delphi's var pair keeps the click as the end — only (29,26)/(29,27) are walked.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(29, 27), dummy.id())));
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(29, 27), fired.target(),
          "a foreshortened GetNextPosition leaves nTargetX/Y on the snapped click");
      assertEquals(dummy.id(), fired.targetId(), "the clicked object is still named");
      now.addAndGet(600);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(dummy.id())).ability().hp() < hp,
          "the foreshortened beam still strikes its surviving cells");
      assertEquals(southHp, run(world, world.snapshot(south.id())).ability().hp(),
          "the end of the beam stopped at the click, not at full reach");
    }
  }

  @Test
  void beamSkippedAtTheEdgeStillCostsManaAndFiresWithTheRawClick() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 29, 0, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(29, 2), Direction.DOWN));
      int hp = run(world, world.snapshot(dummy.id())).ability().hp();
      int mana = run(world, world.snapshot(wizard.id())).ability().mp();
      events.clear();

      // Standing on the north border facing UP: even the +1 GetNextPosition fails, so the whole
      // case body is skipped — no beam cell, no training — but ClientSpellXY already spent the
      // mana and DoSpell still emits RM_MAGICFIRE on the unmutated click coordinates.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(29, -2), 0)));
      assertEquals(mana - 12, run(world, world.snapshot(wizard.id())).ability().mp());
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(29, -2), fired.target(), "the raw click, because the beam never ran");
      assertEquals(0, fired.targetId());
      now.addAndGet(600);
      world.tickOnce();
      assertEquals(hp, run(world, world.snapshot(dummy.id())).ability().hp(),
          "no beam cell was ever walked, so nothing is struck");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "a cast that never hatched a beam is not boTrain either");
    }
  }

  @Test
  void beamDamageFollowsTheVictimNotTheCellItStoodOn() {
    RecordingStore store = new RecordingStore();
    UUID wizardId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    store.save(state(wizardId, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));
    store.save(new PlayerState(victimId, Ability.defaultPlayer(), List.of(), Equipment.empty(),
        0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          events);
      WorldObjectSnapshot victim = enter(world, victimId, "路人", 7, 5, LevelAbilities.JOB_WARRIOR,
          new ArrayList<>());
      int hp = victim.ability().hp();
      events.clear();

      // The click snaps onto the victim (CretInNearXY, ObjBase.pas:16854), so the RM_MAGICFIRE
      // names it; then the victim runs *out of the beam* before the delayed RM_MAGSTRUCK
      // arrives — Delphi has no arrival-time position gate here, unlike the W28 bolt queue.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(7, 5), victim.id())));
      assertEquals(victim.id(), one(events, WorldEvent.MagicFired.class).targetId());
      assertTrue(run(world,
          world.move(victim.id(), new Position(7, 7), Direction.DOWN, MovementKind.RUN)).moved());

      now.addAndGet(600);
      world.tickOnce();
      int damage = hp - run(world, world.snapshot(victim.id())).ability().hp();
      assertTrue(damage > 0, "RM_MAGSTRUCK binds the object, not the cell it stood on");
      assertEquals(damage, one(events, WorldEvent.ObjectStruck.class).damage());
    }
  }

  @Test
  void clickedObjectFurtherThanOneCellDoesNotSteerTheBeamOrGetNamed() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(7, 5), Direction.LEFT));
      int hp = run(world, world.snapshot(dummy.id())).ability().hp();
      events.clear();

      // The client passed the monster's id but clicked three cells away from it: CretInNearXY
      // only snaps within ±1, so the packet names no object — the beam still strikes the
      // monster because it stands on a beam cell.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(10, 5), dummy.id())));
      assertEquals(0, one(events, WorldEvent.MagicFired.class).targetId());
      now.addAndGet(600);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(dummy.id())).ability().hp() < hp);
    }
  }

  @Test
  void beamHonoursPlayerAntiMagicBeforeQueuingMagStruck() {
    RecordingStore store = new RecordingStore();
    UUID wizardId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    store.save(state(wizardId, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));
    store.save(new PlayerState(victimId, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = enter(world, victimId, "抗性人", 7, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      int hp = victim.ability().hp();
      events.clear();

      // MagPassThroughMagic rolls Random(10) >= m_nAntiMagic per proper target. A naked player
      // has anti-magic 1, so FixedRandom's zero draw resists the beam even though RM_MAGICFIRE
      // still names the clicked object.
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, victim.position(), victim.id())));
      assertEquals(victim.id(), one(events, WorldEvent.MagicFired.class).targetId());
      now.addAndGet(600);
      world.tickOnce();
      assertEquals(hp, run(world, world.snapshot(victim.id())).ability().hp());
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance));
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
    }
  }

  @Test
  void aHitTrainsTheSkillAndThePointsSurviveRelog() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 16, SKILL_FIRE));
    int trainedPoints;

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      run(world, world.spawnMonster(stationaryDummy("木桩"), "0", new Position(7, 5), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(10, 5), 0)));
      // Magic.pas:700 — boTrain, so TrainSkill(Random(3)+1) ran once for the cast (not per cell).
      WorldEvent.SkillTrainingChanged changed = one(events, WorldEvent.SkillTrainingChanged.class);
      assertEquals(SKILL_FIRE, changed.magic().skill().magicId());
      trainedPoints = changed.magic().skill().trainingPoints();
      assertTrue(trainedPoints >= 1 && trainedPoints <= 3);

      // A cast that strikes nothing does not train (boTrain stays False).
      events.clear();
      now.addAndGet(2_000);
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_FIRE, new Position(5, 13), 0)));
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
      run(world, world.leavePlayer(wizard.id()));
    }

    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      assertEquals(trainedPoints, run(world, world.skills(wizard.id())).stream()
          .filter(skill -> skill.magicId() == SKILL_FIRE).findFirst().orElseThrow().trainingPoints());
    }
  }

  @Test
  void castGatesStillApplyBeforeTheBeam() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    // The wizard knows 疾光电影 and — as the control — 瞬息移动 (21), which stays unimplemented.
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WIZARD, 26), List.of(),
        Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(SKILL_SHOOTLIGHTEN), PlayerSkill.learned(SKILL_SPACEMOVE))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      events.clear();

      // 瞬息移动 is a wizard-row skill still outside the implemented set, line or not.
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_SPACEMOVE, new Position(10, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.UNSUPPORTED_SKILL,
          one(events, WorldEvent.SpellRejected.class).reason());

      // A click farther than MAGIC_ATTACK_RANGE is refused before the beam is even aimed.
      events.clear();
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_SHOOTLIGHTEN, new Position(20, 20), 0)));
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE,
          one(events, WorldEvent.SpellRejected.class).reason());

      // A second cast inside MAGIC_HIT_INTERVAL + Magic.DB Delay(100) is TOO_FAST.
      events.clear();
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_SHOOTLIGHTEN, new Position(10, 5), 0)));
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_SHOOTLIGHTEN, new Position(10, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.TOO_FAST,
          one(events, WorldEvent.SpellRejected.class).reason());
    }
  }

  private PlayerState state(UUID id, int job, int level, int magicId) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(magicId)));
  }

  /** The 木桩 shape shared with WorldMagicTest: huge HP, zero MAC, far-apart AI intervals. */
  private static MonsterTemplate stationaryDummy(String name) {
    return new MonsterTemplate(name, 0, Ability.monster(100_000, 0, 0, 0, 0),
        1, 1_000_000, 1_000_000, 0, List.of());
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.DOWN,
        0, 0, job, events::add));
  }

  private WorldEngine engine(PlayerStateStore store) {
    return engine(store, List.of());
  }

  private WorldEngine engine(PlayerStateStore store, List<Position> blocked) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    GameMap map = blocked.isEmpty()
        ? GameMap.empty("0", "PoC", 30, 30)
        : GameMap.withBlockedCells("0", "PoC", 30, 30, blocked);
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L), store,
        ItemDatabase.of(StdItemsDb.all()));
  }

  /** Same wiring as {@link #engine}, but every random draw collapses to its range minimum. */
  private WorldEngine deterministicEngine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

  /** {@code nextInt(bound)} always answers 0, so every {@code WorldRandom} draw is the range's
   *  lower bound — used to strip roll noise away from a single deterministic relationship. */
  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
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
