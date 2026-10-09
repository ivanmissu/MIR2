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
 * W49 skill slice: 火墙 {@code SKILL_EARTHFIRE}(22, Magic.pas:501 → {@code MagMakeFireCross},
 * Magic.pas:1135) — the last learnable row of Magic.DB 1–33 and the engine's first map-event
 * (fire wall object) subsystem.
 *
 * <p>Shape under test: the ground-click cast (no {@code TargeTBaseObject} read, empty-ground
 * cast legal) with {@code CretInNearXY} snapping; the cross laid arm by arm in Delphi's order
 * with per-cell occupancy skip and the unconditional {@code Result := 1} training; the
 * {@code GetAttackPower(GetPower(MPow) + LoMC, HiMC - LoMC + 1)} damage roll shared with
 * 爆裂火焰/地狱雷光 and the {@code GetPower(10) + (GetRPow(MC) shr 1)} duration; the
 * {@code TFireBurnEvent} cadence (first burn on the first pass, then every 3000 ms, strictly
 * greater); the {@code RM_MAGSTRUCK_MINE} damage chain (MAC roll, no stagger, no resist,
 * magical {@code SM_STRUCK}); the owner-death/leave sweep that stops the fire from hurting
 * while the flames keep rendering; the expiry sweep with its {@code SM_HIDEEVENT} broadcast;
 * the walk-onto damage from {@code TBaseObject.Walk}; the safe-zone gate
 * ({@code boDisableInSafeZoneFireCross}); and the {@code SearchViewRange} visibility diff on
 * enter/move.
 */
class WorldFireWallTest {
  private static final int SKILL_EARTHFIRE = 22;
  /** Row 22 {@code Delay = 120}: the shared 1350 ms interval plus the row's own delay. */
  private static final long CAST_INTERVAL = 1_470;
  /** Row 22 {@code spell = 20, defSpell = 25}: {@code Round(20 / 4 * 1)} = 5, so 30 at level 0. */
  private static final int MANA_LV0 = 30;
  /** Row 22 {@code spell = 20, defSpell = 25}: {@code Round(20 / 4 * 4)} = 20, so 45 at level 3. */
  private static final int MANA_LV3 = 45;
  /** {@code TFireBurnEvent.Run}'s gate (Event.pas:241). */
  private static final long TICK_MILLIS = 3_000;
  /** Level 24 wizard: MC 2..3 (LevelAbilities), so lv0 damage is 6 and the duration 6 s. */
  private static final int WIZARD_LEVEL = 24;
  /**
   * Level 33 wizard (row 22's NeedL3): MC 3..4, so skill lv3 rolls
   * {@code GetPower(MPow)=6 + LoMC=3} with spread {@code 4-3+1=2} → FixedRandom gives 9.
   */
  private static final int WIZARD_LEVEL_LV3 = 33;
  /** lv0: {@code GetPower(MPow)=4 + LoMC=2}, spread {@code 3-2+1=2} → FixedRandom rolls 6. */
  private static final int DAMAGE_LV0 = 6;
  /** lv3 at a level-33 wizard: 9 per tick. */
  private static final int DAMAGE_LV3 = 9;
  /** lv0 duration: {@code GetPower(10)=5 + (GetRPow(MC)=2 shr 1)=1} → 6 s. */
  private static final long DURATION_LV0_MILLIS = 6_000;
  /** lv3 duration: {@code GetPower(10)=13 + 1} → 14 s. */
  private static final long DURATION_LV3_MILLIS = 14_000;
  /** {@code ET_FIRE = 5} (Grobal2.pas:102). */
  private static final int ET_FIRE = 5;
  /** {@code sDisableInSafeZoneFireCross} (Magic.pas:1140), verbatim. */
  private static final String SAFE_ZONE_MESSAGE = "安全区不允许使用...";

  private final AtomicLong now = new AtomicLong();

  // ------------------------------------------------------------------ cast shape

  @Test
  void castLaysTheCrossAndBroadcastsShowEventAfterTheCastFrame() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)),
          "empty-ground click with targetId = 0 is a legal 火墙 cast (Magic.pas:501)");

      assertEquals(mana - MANA_LV0, ability(world, wizard.id()).mp());
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target());
      assertEquals(0, fired.targetId(), "no object was clicked: the frame carries 0");
      // MagMakeFireCross's arm order (Magic.pas:1148-1164): up, left, center, right, down.
      List<WorldEvent.EventAppeared> shown = eventsOf(events, WorldEvent.EventAppeared.class);
      assertEquals(5, shown.size(), "the whole cross is reported");
      assertEquals(List.of(new Position(8, 4), new Position(7, 5), new Position(8, 5),
          new Position(9, 5), new Position(8, 6)),
          shown.stream().map(WorldEvent.EventAppeared::position).toList());
      for (WorldEvent.EventAppeared appeared : shown) {
        assertEquals(ET_FIRE, appeared.eventType());
        assertEquals(0, appeared.eventParam(), "TFireBurnEvent leaves m_nEventParam at 0");
        assertTrue(appeared.eventId() > 0);
      }
      assertTrue(indexOf(events, WorldEvent.MagicFired.class)
          < indexOf(events, WorldEvent.EventAppeared.class),
          "Delphi's SearchViewRange reports the flames after the cast frame");
      // MagMakeFireCross's Result := 1 is unconditional once the safe-zone gate passed.
      assertEquals(1, one(events, WorldEvent.SkillTrainingChanged.class).magic().skill()
          .trainingPoints(), "FixedRandom collapses TrainSkill's Random(3) + 1 to its minimum");
    }
  }

  @Test
  void secondCastOnTheSameCellsCreatesNothingButStillTrains() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      int mana = ability(world, wizard.id()).mp();
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(5, eventsOf(events, WorldEvent.EventAppeared.class).size());

      now.addAndGet(CAST_INTERVAL);
      events.clear();
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(mana - 2 * MANA_LV0, ability(world, wizard.id()).mp(),
          "the mana is spent before MagMakeFireCross runs");
      assertEquals(0, eventsOf(events, WorldEvent.EventAppeared.class).size(),
          "every arm's cell already carries an event: TEnvirnoment.GetEvent skips it");
      one(events, WorldEvent.MagicFired.class);
      one(events, WorldEvent.SkillTrainingChanged.class);
    }
  }

  @Test
  void castSnapsTheClickOntoANamedObjectWithinOneCell() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot statue = run(world, world.spawnMonster(
          statue("木桩", 100, 50), "0", new Position(8, 5), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_EARTHFIRE, new Position(9, 5), statue.id())));
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(8, 5), fired.target(), "CretInNearXY snaps the click (ObjBase.pas:16854)");
      assertEquals(statue.id(), fired.targetId());
      assertEquals(new Position(8, 5),
          eventsOf(events, WorldEvent.EventAppeared.class).stream()
              .map(WorldEvent.EventAppeared::position).filter(p -> p.equals(new Position(8, 5)))
              .findFirst().orElseThrow());
    }
  }

  // ------------------------------------------------------------------ burn cadence

  @Test
  void fireBurnsEveryThreeSecondsStrictlyGreaterAndExpiresAfterItsDuration() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    // Skill level 3 needs row 22's NeedL3 = 33.
    store.save(wizardWith(id, SKILL_EARTHFIRE, 3, WIZARD_LEVEL_LV3));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot statue = run(world, world.spawnMonster(
          statue("烧烤架", 100, 50), "0", new Position(8, 5), Direction.LEFT));
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));

      // The 3000 ms gate is strictly greater: nothing at exactly 3000 ms.
      events.clear();
      now.addAndGet(TICK_MILLIS);
      world.tickOnce();
      assertEquals(100, ability(world, statue.id()).hp(), "3000 - 0 is not > 3000: no burn yet");

      now.addAndGet(1);
      world.tickOnce();
      assertEquals(100 - DAMAGE_LV3, ability(world, statue.id()).hp());
      WorldEvent.ObjectStruck struck = one(events, WorldEvent.ObjectStruck.class);
      assertEquals(statue.id(), struck.victim().id());
      assertEquals(wizard.id(), struck.attackerId());
      assertEquals(DAMAGE_LV3, struck.damage());
      assertTrue(struck.magical(), "RM_MAGSTRUCK_MINE lands as a magical SM_STRUCK (lTag2 = 1)");

      // The next burn is due strictly past 3000 ms after the previous one: 6001 - 3001 is
      // exactly 3000, so still nothing; 6002 burns.
      events.clear();
      now.addAndGet(TICK_MILLIS);
      world.tickOnce();
      assertEquals(100 - DAMAGE_LV3, ability(world, statue.id()).hp(), "exactly 3000 ms: no burn");
      now.addAndGet(1);
      world.tickOnce();
      assertEquals(100 - 2 * DAMAGE_LV3, ability(world, statue.id()).hp());

      // The duration is strictly greater as well: at 14001 ms the burn gate (7999 ms since
      // the last one) fires before the expiry check closes the fire — Delphi's Run order.
      events.clear();
      now.addAndGet(DURATION_LV3_MILLIS - now.get() + 1);
      world.tickOnce();
      assertEquals(100 - 3 * DAMAGE_LV3, ability(world, statue.id()).hp(),
          "the burn due at 14001 ms still lands before the close");
      assertEquals(1, eventsOf(events, WorldEvent.ObjectStruck.class).size());
      assertEquals(5, eventsOf(events, WorldEvent.EventDisappeared.class).size(),
          "expiry broadcasts SM_HIDEEVENT for every arm");
      for (WorldEvent.EventDisappeared hidden : eventsOf(events, WorldEvent.EventDisappeared.class)) {
        assertTrue(hidden.eventId() > 0);
      }

      // After expiry the fire is gone: no more burns, no more events.
      events.clear();
      now.addAndGet(TICK_MILLIS + 1);
      world.tickOnce();
      assertEquals(100 - 3 * DAMAGE_LV3, ability(world, statue.id()).hp());
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance));
      assertTrue(events.stream().noneMatch(WorldEvent.EventDisappeared.class::isInstance));
    }
  }

  @Test
  void fireDoesNotBurnTheCasterStandingOnIt() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(5, 5), 0)),
          "casting on the caster's own cell is legal");
      int hp = ability(world, wizard.id()).hp();

      now.addAndGet(TICK_MILLIS + 1);
      world.tickOnce();
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance),
          "IsProperTarget excludes the caster himself (Event.pas:249)");
      assertEquals(hp, ability(world, wizard.id()).hp(), "the caster never burns himself");
    }
  }

  @Test
  void fireKillsTheMonsterAndCreditsTheCaster() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot prey = run(world, world.spawnMonster(
          statue("残血怪", 5, 50), "0", new Position(8, 5), Direction.LEFT));
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));

      now.addAndGet(TICK_MILLIS + 1);
      events.clear();
      world.tickOnce();

      assertEquals(0, ability(world, prey.id()).hp());
      WorldEvent.ObjectDied died = one(events, WorldEvent.ObjectDied.class);
      assertEquals(prey.id(), died.victim().id());
      assertEquals(wizard.id(), died.killerId(), "SetLastHiter in RM_STRUCK_MAG credits the owner");
      WorldEvent.ExperienceGained gained = one(events, WorldEvent.ExperienceGained.class);
      assertEquals(wizard.id(), gained.playerId());
      assertEquals(50, gained.gained(), "the template's experience, solo kill (no group bonus)");
    }
  }

  @Test
  void departedOwnerStopsTheFireFromHurtingButTheFlamesRemain() {
    UUID ownerId = UUID.randomUUID();
    UUID watcherId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(ownerId, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    store.save(plain(watcherId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> watcherEvents = new ArrayList<>();
      WorldObjectSnapshot owner = enter(world, ownerId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      WorldObjectSnapshot watcher = enter(world, watcherId, "看客", 6, 5, LevelAbilities.JOB_WIZARD,
          watcherEvents);
      WorldObjectSnapshot statue = run(world, world.spawnMonster(
          statue("烧烤架", 100, 50), "0", new Position(8, 5), Direction.LEFT));
      assertTrue(run(world, world.castSpell(owner.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));

      now.addAndGet(TICK_MILLIS + 1);
      world.tickOnce();
      assertEquals(100 - DAMAGE_LV0, ability(world, statue.id()).hp(), "the fire burns while the owner lives");

      // Event.pas:282: a ghosted/departed owner is cleared, so the fire stops hurting —
      // but the flames keep rendering until the duration lapses.
      run(world, world.leavePlayer(owner.id()));
      watcherEvents.clear();
      // Stay inside the 6000 ms duration: the next tick must show no burn and no expiry.
      now.addAndGet(1_000);
      world.tickOnce();
      assertEquals(100 - DAMAGE_LV0, ability(world, statue.id()).hp(),
          "no owner: GeTBaseObjects' IsProperTarget gate has nobody to answer for");
      assertTrue(watcherEvents.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance));
      assertTrue(watcherEvents.stream().noneMatch(WorldEvent.EventDisappeared.class::isInstance),
          "the flames remain until expiry");

      now.addAndGet(DURATION_LV0_MILLIS);
      world.tickOnce();
      assertEquals(5, eventsOf(watcherEvents, WorldEvent.EventDisappeared.class).size(),
          "the abandoned cross still expires on schedule");
    }
  }

  // ------------------------------------------------------------------ walk-onto damage

  @Test
  void walkingOntoAFireCellBurnsImmediately() {
    UUID ownerId = UUID.randomUUID();
    UUID walkerId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(ownerId, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    store.save(plain(walkerId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> walkerEvents = new ArrayList<>();
      WorldObjectSnapshot owner = enter(world, ownerId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      WorldObjectSnapshot walker = enter(world, walkerId, "过客", 7, 5, LevelAbilities.JOB_WIZARD,
          walkerEvents);
      assertTrue(run(world, world.castSpell(owner.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      int walkerHp = ability(world, walker.id()).hp();
      walkerEvents.clear();

      // TBaseObject.Walk's event scan (ObjBase.pas:20204-20229): the step lands first, the
      // burn follows in the same pass — no 3000 ms wait.
      MoveResult result = run(world, world.move(
          walker.id(), new Position(8, 5), Direction.RIGHT, MovementKind.WALK));
      assertTrue(result.moved());
      WorldEvent.ObjectStruck struck = one(walkerEvents, WorldEvent.ObjectStruck.class);
      assertEquals(walker.id(), struck.victim().id());
      assertEquals(owner.id(), struck.attackerId());
      assertEquals(DAMAGE_LV0, struck.damage());
      assertTrue(struck.magical());
      assertEquals(walkerHp - DAMAGE_LV0, ability(world, walker.id()).hp());
      assertTrue(indexOf(walkerEvents, WorldEvent.MoveAccepted.class)
          < indexOf(walkerEvents, WorldEvent.ObjectStruck.class),
          "the walk acknowledgement precedes the queued RM_MAGSTRUCK_MINE");
    }
  }

  // ------------------------------------------------------------------ safe zone

  @Test
  void safeZoneGateRefusesButStillBurnsManaAndFires() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    GameMap map = GameMap.empty("0", "PoC", 30, 30);
    map.addStartPoint(new StartPoint(new Position(8, 5), 5));

    try (WorldEngine world = engine(store, new FixedRandom(), map, true)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      assertTrue(map.isSafeZone(new Position(8, 5)));
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(mana - MANA_LV0, ability(world, wizard.id()).mp(), "the mana is already spent");
      WorldEvent.SystemMessage message = one(events, WorldEvent.SystemMessage.class);
      assertEquals(SAFE_ZONE_MESSAGE, message.message());
      one(events, WorldEvent.MagicFired.class);
      assertEquals(0, eventsOf(events, WorldEvent.EventAppeared.class).size(), "no fire is laid");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "MagMakeFireCross's exit leaves Result = 0: no training");
    }
  }

  @Test
  void safeZoneCastingIsAllowedByDefaultLikeDelphi() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    GameMap map = GameMap.empty("0", "PoC", 30, 30);
    map.addStartPoint(new StartPoint(new Position(8, 5), 5));

    // boDisableInSafeZoneFireCross ships False (M2Share.pas:2070).
    try (WorldEngine world = engine(store, new FixedRandom(), map, false)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5, LevelAbilities.JOB_WIZARD, events);
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(5, eventsOf(events, WorldEvent.EventAppeared.class).size(),
          "the shipped default lays the cross inside a safe zone");
      assertTrue(events.stream().noneMatch(WorldEvent.SystemMessage.class::isInstance));
    }
  }

  // ------------------------------------------------------------------ cast gates

  @Test
  void castGatesStillApply() {
    UUID taoistId = UUID.randomUUID();
    UUID youngId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(taoistId, SKILL_EARTHFIRE, 0, WIZARD_LEVEL, LevelAbilities.JOB_TAOIST));
    store.save(wizardWith(youngId, SKILL_EARTHFIRE, 0, 23));
    store.save(wizardWith(id, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> taoistEvents = new ArrayList<>();
      List<WorldEvent> youngEvents = new ArrayList<>();
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, taoistId, "道士", 5, 5, LevelAbilities.JOB_TAOIST,
          taoistEvents);
      WorldObjectSnapshot young = enter(world, youngId, "小法", 6, 5, LevelAbilities.JOB_WIZARD,
          youngEvents);
      WorldObjectSnapshot wizard = enter(world, id, "法师", 7, 5, LevelAbilities.JOB_WIZARD, events);

      assertFalse(run(world, world.castSpell(
          taoist.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(taoistEvents, WorldEvent.SpellRejected.class).reason(), "row 22 is job = 1 (法师)");

      assertFalse(run(world, world.castSpell(young.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(youngEvents, WorldEvent.SpellRejected.class).reason(), "row 22's NeedL1 = 24");

      events.clear();
      assertFalse(run(world, world.castSpell(
          wizard.id(), SKILL_EARTHFIRE, new Position(20, 20), 0)));
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE,
          one(events, WorldEvent.SpellRejected.class).reason(), "MAGIC_ATTACK_RANGE = 8");

      events.clear();
      assertTrue(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_EARTHFIRE, new Position(8, 5), 0)));
      assertEquals(WorldEvent.SpellRejection.TOO_FAST,
          one(events, WorldEvent.SpellRejected.class).reason(),
          "the shared 1350 ms interval plus row 22's Delay = 120");
    }
  }

  // ------------------------------------------------------------------ visibility

  @Test
  void enteringWithinViewSeesTheBurningCross() {
    UUID ownerId = UUID.randomUUID();
    UUID watcherId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(ownerId, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    store.save(plain(watcherId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot owner = enter(world, ownerId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      assertTrue(run(world, world.castSpell(owner.id(), SKILL_EARTHFIRE, new Position(13, 5), 0)));

      List<WorldEvent> watcherEvents = new ArrayList<>();
      enter(world, watcherId, "看客", 6, 6, LevelAbilities.JOB_WIZARD, watcherEvents);
      assertEquals(5, eventsOf(watcherEvents, WorldEvent.EventAppeared.class).size(),
          "SearchViewRange's first sweep reports the cross right after the map bootstrap");
      assertTrue(indexOf(watcherEvents, WorldEvent.MapEntered.class)
          < indexOf(watcherEvents, WorldEvent.EventAppeared.class));
    }
  }

  @Test
  void movingIntoAndOutOfViewReportsShowAndHide() {
    UUID ownerId = UUID.randomUUID();
    UUID watcherId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(ownerId, SKILL_EARTHFIRE, 0, WIZARD_LEVEL));
    store.save(plain(watcherId, LevelAbilities.JOB_WIZARD, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot owner = enter(world, ownerId, "法师", 5, 5, LevelAbilities.JOB_WIZARD,
          new ArrayList<>());
      assertTrue(run(world, world.castSpell(owner.id(), SKILL_EARTHFIRE, new Position(13, 5), 0)));

      // (5, 20) is 15 cells from the cross centre: outside the ±12 view square.
      List<WorldEvent> watcherEvents = new ArrayList<>();
      WorldObjectSnapshot watcher = enter(world, watcherId, "看客", 5, 20, LevelAbilities.JOB_WIZARD,
          watcherEvents);
      assertEquals(0, eventsOf(watcherEvents, WorldEvent.EventAppeared.class).size(),
          "nothing to report on entry");

      for (int y = 19; y >= 6; y--) {
        assertTrue(run(world, world.move(
            watcher.id(), new Position(5, y), Direction.UP, MovementKind.WALK)).moved());
      }
      assertEquals(5, eventsOf(watcherEvents, WorldEvent.EventAppeared.class).size(),
          "the sweep reports every arm as it enters the view square");

      for (int y = 7; y <= 20; y++) {
        assertTrue(run(world, world.move(
            watcher.id(), new Position(5, y), Direction.DOWN, MovementKind.WALK)).moved());
      }
      assertEquals(5, eventsOf(watcherEvents, WorldEvent.EventDisappeared.class).size(),
          "and hides every arm as it leaves");
    }
  }

  // ------------------------------------------------------------------ helpers

  /**
   * A monster that ticks the full AI path but whose walk/attack intervals are so large it
   * never acts during a test, with a flat MAC 0..0 so the fire's MAC roll absorbs nothing.
   */
  private static MonsterTemplate statue(String name, int hp, long experience) {
    return new MonsterTemplate(name, 0, new Ability(
        hp, hp, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, LevelExperience.forLevel(1)),
        12, 600_000, 600_000, experience, MonsterBehavior.AGGRESSIVE, List.of(), List.of(),
        false, 0, 0, 0);
  }

  /** One character who knows {@code magicId} at {@code skillLevel}. */
  private static PlayerState wizardWith(UUID id, int magicId, int skillLevel, int level) {
    return wizardWith(id, magicId, skillLevel, level, LevelAbilities.JOB_WIZARD);
  }

  private static PlayerState wizardWith(UUID id, int magicId, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
  }

  private static PlayerState plain(UUID id, int job, int level) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0);
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

  /** Every random draw collapses to its range minimum. */
  private WorldEngine deterministicEngine(PlayerStateStore store) {
    return engine(store, new FixedRandom(), GameMap.empty("0", "PoC", 30, 30), false);
  }

  private WorldEngine engine(PlayerStateStore store, Random random, GameMap map,
      boolean disableFireCrossInSafeZone) {
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900,
        5_000, 180_000, 200, 10 * 60 * 1_000L, 0, 1, 2, 1, 50, disableFireCrossInSafeZone);
    return new WorldEngine(config, List.of(map), now::get, random, store,
        ItemDatabase.of(StdItemsDb.all()));
  }

  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
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
