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
 * W45: source-driven world semantics for 抗拒火环 ({@code SKILL_FIREWIND}=8).
 *
 * <p>{@code Magic.pas:384} gives the skill a one-line case body —
 * {@code if MagPushArround(PlayObject, UserMagic.btLevel) > 0 then boTrain := True;} — so the
 * whole slice is {@code MagPushArround} (Magic.pas:146) and {@code CharPushed}
 * (ObjBase.pas:2504). These tests pin the filter chain, the two independent randomness draws
 * (gate and distance), the inclusive 3&times;3 window, and the two source quirks the Delphi
 * body keeps: the gate roll happens <em>before</em> {@code IsProperTarget}, and
 * {@code Inc(Result)} counts a push whose target never moved because the first cell was solid.
 */
class WorldPushArroundTest {
  private static final int SKILL_FIREWIND = 8;
  /** {@code GetSpellPoint} at level zero: ROUND(8 / 4 * 1) + defSpell(0). */
  private static final int MANA_COST = 2;

  /**
   * {@code GetSpellPoint} (Magic.pas:59) for Magic.DB row 8: {@code ROUND(spell / 4 *
   * (btLevel + 1)) + defSpell} with {@code spell = 8} and {@code defSpell = 0}, i.e. two mana
   * per skill level.
   */
  private static int manaCost(int skillLevel) {
    return (int) Math.rint(8 / 4.0 * (skillLevel + 1));
  }
  /** {@code Magic.DB} row 8: {@code NeedL1 = 12}. */
  private static final int NEED_L1 = 12;

  private final AtomicLong now = new AtomicLong();

  @Test
  void adjacentLowerLevelTargetIsPushedAndTrainsOnce() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));
    FixedRandom random = new FixedRandom();

    try (WorldEngine world = engine(config(12), store, random)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = spawn(world, "怯懦木桩", 6, 5, 1);
      int hp = run(world, world.snapshot(victim.id())).ability().hp();
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      events.clear();

      random.reset();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(6, 5), victim.id())));
      // one gate roll (Random(20)), one distance roll (Random(2)) and the DoSpell training
      // roll (Random(3) + 1); no damage power roll, no MAC roll.
      assertEquals(3, random.draws());
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp(),
          "ClientSpellXY spends GetSpellPoint before DoSpell runs");

      WorldObjectSnapshot pushed = run(world, world.snapshot(victim.id()));
      assertEquals(new Position(7, 5), pushed.position(),
          "level 0 with Random(2)=0 pushes exactly one cell straight away from the caster");
      assertEquals(Direction.LEFT, pushed.direction(),
          "CharPushed leaves RM_PUSH with GetBackDir(nDir) facing the caster");
      assertEquals(hp, pushed.ability().hp(), "the skill is pure displacement — no damage");

      WorldEvent.ObjectPushed backstep = one(events, WorldEvent.ObjectPushed.class);
      assertEquals(victim.id(), backstep.object().id());
      assertEquals(new Position(6, 5), backstep.from());
      assertEquals(Direction.LEFT, backstep.direction());
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(6, 5), fired.target(), "the click rides on RM_MAGICFIRE");
      assertEquals(victim.id(), fired.targetId());
      assertEquals(1, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count(), "DoSpell trains once per cast, not once per pushed object");
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance),
          "MagPushArround never sends RM_MAGSTRUCK");
    }
  }

  @Test
  void pushDistanceGrowsWithSkillLevel() {
    for (int skillLevel : new int[] {0, 1, 2, 3}) {
      Store store = new Store();
      UUID casterId = UUID.randomUUID();
      store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20,
          List.of(new PlayerSkill(SKILL_FIREWIND, skillLevel, 0, 0))));

      try (WorldEngine world = engine(config(12), store, new FixedRandom())) {
        List<WorldEvent> events = new ArrayList<>();
        WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
            LevelAbilities.JOB_WIZARD, events);
        WorldObjectSnapshot victim = spawn(world, "木桩", 6, 5, 1);
        events.clear();

        assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
            new Position(6, 5), victim.id())));
        // push := 1 + _MAX(0, nPushLevel - 1) + Random(2), and FixedRandom always draws 0.
        int expectedSteps = 1 + Math.max(0, skillLevel - 1);
        assertEquals(new Position(6 + expectedSteps, 5),
            run(world, world.snapshot(victim.id())).position(),
            "skill level " + skillLevel + " pushes " + expectedSteps + " cell(s)");
        assertEquals(expectedSteps,
            events.stream().filter(WorldEvent.ObjectPushed.class::isInstance).count(),
            "CharPushed sends one RM_PUSH per traversed cell");
      }
    }
  }

  @Test
  void gateRollUsesTheLevelGapAndTheSkillLevel() {
    // Random(20) < 6 + nPushLevel * 3 + levelgap, with a fixed draw of 19 (worst case).
    assertEquals(19, ConstantRandom.ROLL);

    // Level gap 5, skill level 0: 6 + 0 + 5 = 11, so 19 fails the gate.
    assertPushOutcome(/* casterLevel */ 20, /* victimLevel */ 15, /* skillLevel */ 0, false);
    // The same gap with skill level 3: 6 + 9 + 5 = 20, so 19 slips through.
    assertPushOutcome(20, 15, 3, true);
    // The level gap alone can carry a level-zero skill: 6 + 0 + 15 = 21.
    assertPushOutcome(20, 5, 0, true);
  }

  private void assertPushOutcome(int casterLevel, int victimLevel, int skillLevel,
      boolean expectedPush) {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, casterLevel,
        List.of(new PlayerSkill(SKILL_FIREWIND, skillLevel, 0, 0))));

    try (WorldEngine world = engine(config(12), store, new ConstantRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = spawn(world, "木桩", 6, 5, victimLevel);
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(6, 5), victim.id())));
      // push := 1 + _MAX(0, nPushLevel - 1) + Random(2); ConstantRandom's distance draw is the
      // gate value modulo two, i.e. one extra cell.
      int distanceRoll = ConstantRandom.ROLL % 2;
      int steps = 1 + Math.max(0, skillLevel - 1) + distanceRoll;
      Position expected = expectedPush ? new Position(6 + steps, 5) : new Position(6, 5);
      assertEquals(expected, run(world, world.snapshot(victim.id())).position(),
          "gate(" + victimLevel + " vs " + casterLevel + ", skill " + skillLevel + ")");
      assertEquals(expectedPush ? steps : 0,
          events.stream().filter(WorldEvent.ObjectPushed.class::isInstance).count());
      // DoSpell's training tail is `if (UserMagic.btLevel < 3) and boTrain`, so a maxed skill
      // is pushed but never trains.
      assertEquals(expectedPush && skillLevel < MagicDefinition.MAX_SKILL_LEVEL ? 1 : 0,
          events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count());
      // A failed gate still costs the mana and still plays RM_MAGICFIRE: ClientSpellXY spends
      // first and MagPushArround simply returns 0.
      assertEquals(mana - manaCost(skillLevel),
          run(world, world.snapshot(caster.id())).ability().mp());
      one(events, WorldEvent.MagicFired.class);
    }
  }

  @Test
  void pushUsesTheInclusiveThreeByThreeSquareAroundTheCaster() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));

    try (WorldEngine world = engine(config(12), store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 10, 10,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot corner = spawn(world, "角落木桩", 11, 11, 1);
      WorldObjectSnapshot upLeft = spawn(world, "左上木桩", 9, 9, 1);
      WorldObjectSnapshot distanceTwo = spawn(world, "隔一格木桩", 12, 10, 1);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(10, 10), 0)));
      assertEquals(new Position(12, 12), run(world, world.snapshot(corner.id())).position(),
          "a diagonal object is pushed along GetNextDirection(cast -> target)");
      assertEquals(new Position(8, 8), run(world, world.snapshot(upLeft.id())).position());
      assertEquals(new Position(12, 10), run(world, world.snapshot(distanceTwo.id())).position(),
          "abs(dx) <= 1 and abs(dy) <= 1 excludes two cells away — the map square is never "
              + "searched, only the caster's own visible neighbourhood");
      assertEquals(Direction.DOWN_RIGHT, run(world, world.snapshot(upLeft.id())).direction(),
          "CharPushed stores GetBackDir(nDir): an up-left push ends facing down-right");
    }
  }

  @Test
  void gateRollIsDrawnBeforeTheProperTargetCheck() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));
    RecordingRandom random = new RecordingRandom();

    try (WorldEngine world = engine(config(12), store, random)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      // TNormNpc is immortal (level 1) and never a proper target, so the level gate passes,
      // the Random(20) draw is consumed, and IsProperTarget then rejects it.
      WorldObjectSnapshot npc = run(world, world.spawnNpc(
          "老兵", "0", new Position(6, 5), 3, Direction.DOWN));
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      events.clear();
      random.reset();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(6, 5), npc.id())));

      assertEquals(List.of(20), random.bounds(),
          "exactly one gate roll: the rejected NPC gets no Random(2) distance roll");
      assertEquals(new Position(6, 5), run(world, world.snapshot(npc.id())).position());
      assertEquals(0, events.stream().filter(WorldEvent.ObjectPushed.class::isInstance).count());
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count(), "an unpushed NPC means MagPushArround returns 0 and boTrain stays unset");
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp());
      one(events, WorldEvent.MagicFired.class);
    }
  }

  @Test
  void blockedFirstStepStillCountsAsAPushAndStillTrains() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));

    GameMap map = GameMap.withBlockedCells("0", "PoC", 20, 20, List.of(new Position(7, 5)));
    try (WorldEngine world = engineWithMap(config(12), store, new FixedRandom(), map)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = spawn(world, "顶墙木桩", 6, 5, 1);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(6, 5), victim.id())));

      WorldObjectSnapshot after = run(world, world.snapshot(victim.id()));
      assertEquals(new Position(6, 5), after.position(), "CanWalk is false on the solid cell");
      assertEquals(Direction.DOWN, after.direction(),
          "CharPushed restores olddir when it moved zero cells");
      assertEquals(0, events.stream().filter(WorldEvent.ObjectPushed.class::isInstance).count());
      assertEquals(1, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count(),
          "Delphi's Inc(Result) sits outside CharPushed's step result — a push that moved "
              + "nothing still sets boTrain");
    }
  }

  @Test
  void equalOrHigherLevelTargetIsSkippedBeforeAnyRoll() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));
    store.save(state(targetId, LevelAbilities.JOB_WARRIOR, 20, 0));
    RecordingRandom random = new RecordingRandom();

    try (WorldEngine world = engine(config(12), store, random)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot peer = enter(world, targetId, "同级战士", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      events.clear();
      random.reset();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(6, 5), peer.id())));

      assertEquals(new Position(6, 5), run(world, world.snapshot(peer.id())).position(),
          "the level comparison is strict (m_Abil.Level > BaseObject.m_Abil.Level)");
      assertTrue(random.bounds().isEmpty(),
          "the level test precedes the Random(20) draw, so the peer consumes no randomness");
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance)
          .count());
    }
  }

  @Test
  void pushedAnimalDelaysItsNextWalkByEightHundredMillis() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));

    try (WorldEngine world = engine(config(12), store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 10, 10,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot chaser = spawnChaser(world, "追击木桩", 9, 10, 1);
      events.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(9, 10), chaser.id())));
      assertEquals(new Position(8, 10), run(world, world.snapshot(chaser.id())).position(),
          "the adjacent chaser is pushed one cell west");

      // CharPushed adds 800 ms to m_dwWalkTick for every m_btRaceServer >= RC_ANIMAL object.
      now.addAndGet(400);
      assertEquals(new Position(8, 10), run(world, world.snapshot(chaser.id())).position(),
          "400 ms after the push the chaser must not have walked yet");
      now.addAndGet(500);
      world.tickOnce();
      // The reading below ticks again at the same wall-clock instant: the chase already spent
      // its 50 ms cadence stamp on the first tick, so the monster stays put while the snapshot
      // is drained (a snapshot ordered before the tick body would otherwise observe the
      // pre-move cell).
      assertEquals(new Position(9, 10), run(world, world.snapshot(chaser.id())).position(),
          "after the 800 ms bump the chaser resumes and steps toward the caster");
    }
  }

  @Test
  void fireWindIsRefusedForEveryJobButWizardAndBelowItsRequiredLevel() {
    Store store = new Store();
    UUID warriorId = UUID.randomUUID();
    UUID taoistId = UUID.randomUUID();
    UUID juniorId = UUID.randomUUID();
    store.save(state(warriorId, LevelAbilities.JOB_WARRIOR, 30, SKILL_FIREWIND));
    store.save(state(taoistId, LevelAbilities.JOB_TAOIST, 30, SKILL_FIREWIND));
    store.save(state(juniorId, LevelAbilities.JOB_WIZARD, NEED_L1 - 1, SKILL_FIREWIND));

    try (WorldEngine world = engine(config(12), store, new FixedRandom())) {
      List<WorldEvent> warriorEvents = new ArrayList<>();
      List<WorldEvent> taoistEvents = new ArrayList<>();
      List<WorldEvent> juniorEvents = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, warriorId, "战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, warriorEvents);
      WorldObjectSnapshot taoist = enter(world, taoistId, "道士", 5, 7,
          LevelAbilities.JOB_TAOIST, taoistEvents);
      WorldObjectSnapshot junior = enter(world, juniorId, "小学徒", 5, 9,
          LevelAbilities.JOB_WIZARD, juniorEvents);

      // Magic.DB row 8 is job = 1 (法师); the warrior and taoist rows stay unimplemented.
      assertFalse(run(world, world.castSpell(warrior.id(), SKILL_FIREWIND,
          new Position(6, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(warriorEvents, WorldEvent.SpellRejected.class).reason());
      assertFalse(run(world, world.castSpell(taoist.id(), SKILL_FIREWIND,
          new Position(6, 7), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(taoistEvents, WorldEvent.SpellRejected.class).reason());

      // NeedL1 = 12: a level-11 wizard may own the row but cannot cast it.
      assertFalse(run(world, world.castSpell(junior.id(), SKILL_FIREWIND,
          new Position(6, 9), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(juniorEvents, WorldEvent.SpellRejected.class).reason());
    }
  }

  @Test
  void clickBeyondTheMagicAttackRangeIsRejected() {
    Store store = new Store();
    UUID casterId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_WIZARD, 20, SKILL_FIREWIND));

    try (WorldEngine world = engine(config(12), store, new FixedRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = spawn(world, "木桩", 6, 5, 1);
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      events.clear();

      // abs(dx) > nMagicAttackRage or abs(dy) > nMagicAttackRage exits before the case body.
      assertFalse(run(world, world.castSpell(caster.id(), SKILL_FIREWIND,
          new Position(14, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE,
          one(events, WorldEvent.SpellRejected.class).reason());
      assertEquals(new Position(6, 5), run(world, world.snapshot(victim.id())).position());
      assertEquals(mana, run(world, world.snapshot(caster.id())).ability().mp());
    }
  }

  // ------------------------------------------------------------------ helpers

  private static WorldEngine.Config config(int viewRange) {
    return new WorldEngine.Config(Duration.ofMillis(50), viewRange, 1_000, 900, 5_000, 180_000,
        200, 600_000, 0, 1, 2, 1);
  }

  private WorldEngine engine(WorldEngine.Config config, PlayerStateStore store, Random random) {
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get, random,
        store, ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldEngine engineWithMap(WorldEngine.Config config, PlayerStateStore store,
      Random random, GameMap map) {
    return new WorldEngine(config, List.of(map), now::get, random, store,
        ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.DOWN,
        0, 0, job, events::add));
  }

  private WorldObjectSnapshot spawn(WorldEngine world, String name, int x, int y, int level) {
    Ability ability = new Ability(100_000, 100_000, 0, 0, 0, 0, 0, 0, level, 0);
    MonsterTemplate target = new MonsterTemplate(name, 0, ability, 12, 1_000_000, 1_000_000,
        0, MonsterBehavior.STATIONARY, List.of());
    return run(world, world.spawnMonster(target, "0", new Position(x, y), Direction.DOWN));
  }

  /**
   * An {@link MonsterBehavior#AGGRESSIVE} dummy that walks on a 50 ms cadence but never attacks,
   * so the only movement it can show is the chase that the 800 ms walk-tick bump delays.
   */
  private WorldObjectSnapshot spawnChaser(WorldEngine world, String name, int x, int y, int level) {
    Ability ability = new Ability(100_000, 100_000, 0, 0, 0, 0, 0, 0, level, 0);
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

  /** Always draws zero, so the gate passes whenever the threshold is positive. */
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

  /** Always draws {@link #ROLL}: 19 is the largest value {@code Random(20)} can return. */
  private static final class ConstantRandom extends Random {
    static final int ROLL = 19;

    @Override
    public int nextInt(int bound) {
      return ROLL % bound;
    }
  }

  /** Records the bound of every draw so the source order of the two rolls is observable. */
  private static final class RecordingRandom extends Random {
    private final List<Integer> bounds = new ArrayList<>();

    @Override
    public int nextInt(int bound) {
      bounds.add(bound);
      return 0;
    }

    void reset() {
      bounds.clear();
    }

    List<Integer> bounds() {
      return List.copyOf(bounds);
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
