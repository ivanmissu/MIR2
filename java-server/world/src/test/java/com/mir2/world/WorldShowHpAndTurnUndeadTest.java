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
 * W48 skill slice: 心灵启示 {@code SKILL_SHOWHP}(28, Magic.pas:522) and 圣言术
 * {@code SKILL_KILLUNDEAD}(32, Magic.pas:572 → {@code MagTurnUndead}, Magic.pas:901).
 *
 * <p>Shape under test for 心灵启示: the {@code TargeTBaseObject <> nil and not m_boShowHP}
 * gate with no {@code IsProperTarget}; the {@code Random(6) <= btLevel + 3} reveal roll; the
 * {@code GetPower13(GetRPow(SC) * 2 + 30) * 1000} interval stamped on the target at cast time;
 * the self-addressed 1500 ms {@code RM_DOOPENHEALTH} delivery that runs {@code MakeOpenHealth}
 * (ObjBase.pas:3606); the strictly-greater expiry walk (ObjBase.pas:4029) that runs
 * {@code BreakOpenHealth}; the latent {@code STATE_OPENHEATH ($2)} bit that only rides along on
 * the next status repaint; and the mana/{@code RM_MAGICFIRE}/training semantics shared by every
 * outcome.
 *
 * <p>Shape under test for 圣言术: the {@code LA_UNDEAD}-only gate that refuses players and living
 * monsters before {@code Struck} runs; {@code TAnimalObject.Struck} (ObjBase.pas:2794) —
 * retarget to the caster plus the {@code m_dwHitTick} flinch; the 10 s {@code m_boRunAwayMode}
 * freeze when the monster cannot take the caster as its target (safe-zone caster); the
 * {@code Random(2) + (casterLevel - 1) > targetLevel} gate; the {@code nMagTurnUndeadLevel}
 * ceiling; the {@code Random(100) < 7 * skillLevel + 15 + levelGap} instant-kill roll that ends
 * in {@code HP := 0} through the ordinary death chain; and training only on a successful kill.
 */
class WorldShowHpAndTurnUndeadTest {
  private static final int SKILL_SHOWHP = 28;
  private static final int SKILL_KILLUNDEAD = 32;
  private static final int SKILL_CLOAK = 18;
  /** Row 28 {@code Delay = 40}: the shared 1350 ms interval plus the row's own delay. */
  private static final long CAST_INTERVAL_SHOWHP = 1_390;
  /** Row 32 {@code Delay = 120}: the shared 1350 ms interval plus the row's own delay. */
  private static final long CAST_INTERVAL_KILLUNDEAD = 1_470;
  /** {@code SendDelayMsg(..., RM_DOOPENHEALTH, ..., 1500)} (Magic.pas:527). */
  private static final long SHOW_HP_DELAY_MILLIS = 1_500;
  /** {@code m_dwRunAwayTime := 10 * 1000} (Magic.pas:909). */
  private static final long RUN_AWAY_MILLIS = 10_000;
  /** Row 28 {@code spell = 16, defSpell = 0}: {@code Round(16 / 4 * 1)} = 4 at skill level 0. */
  private static final int SHOWHP_MANA = 4;
  /**
   * Row 32 {@code spell = 50, defSpell = 40}: banker's {@code Round(12.5)} = 12, so 52 at
   * skill level 0.
   */
  private static final int KILLUNDEAD_MANA = 52;
  /** {@code STATE_OPENHEATH = $00000002} (Common/Grobal2.pas:96). */
  private static final int OPEN_HEATH_BIT = 0x2;
  /** Row 28 needs level 23; row 32 needs level 32. */
  private static final int TAOIST_LEVEL = 23;
  private static final int WIZARD_LEVEL = 40;

  private final AtomicLong now = new AtomicLong();

  // ------------------------------------------------------------------ 心灵启示

  @Test
  void showHpRevealsTheTargetFifteenHundredMillisAfterTheCast() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(id, SKILL_SHOWHP, 0, TAOIST_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("显血怪"), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          taoist.id(), SKILL_SHOWHP, target.position(), target.id())));

      assertEquals(mana - SHOWHP_MANA, ability(world, taoist.id()).mp());
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(target.id(), fired.targetId(), "boSpellFire stays on: the frame names the target");
      assertTrue(events.stream().noneMatch(WorldEvent.HealthRevealed.class::isInstance),
          "the reveal lands on the delayed RM_DOOPENHEALTH, not at cast time");
      assertEquals(1, one(events, WorldEvent.SkillTrainingChanged.class).magic().skill()
          .trainingPoints(), "FixedRandom collapses TrainSkill's Random(3) + 1 to its minimum");

      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      WorldEvent.HealthRevealed revealed = one(events, WorldEvent.HealthRevealed.class);
      assertEquals(target.id(), revealed.object().id());
      assertEquals(100, revealed.object().ability().hp(),
          "RM_OPENHEALTH carries the target's current HP (ObjBase.pas:3611)");
      assertEquals(100, revealed.object().ability().maxHp());
      assertTrue(events.stream().noneMatch(WorldEvent.HealthConcealed.class::isInstance));
    }
  }

  @Test
  void showHpGateFailureStillBurnsManaAndFiresWithoutTraining() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(id, SKILL_SHOWHP, 0, TAOIST_LEVEL));

    // MaxRandom rolls Random(6) = 5, above the skill-level-0 threshold of 3 (Magic.pas:524).
    try (WorldEngine world = engine(store, new MaxRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("显血怪"), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          taoist.id(), SKILL_SHOWHP, target.position(), target.id())));

      assertEquals(mana - SHOWHP_MANA, ability(world, taoist.id()).mp(),
          "the mana left in TPlayObject.DoSpell before the case body ran");
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());
      assertTrue(events.stream().noneMatch(WorldEvent.HealthRevealed.class::isInstance));
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "boTrain stays unset when Random(6) > btLevel + 3");

      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();
      assertTrue(events.stream().noneMatch(WorldEvent.HealthRevealed.class::isInstance),
          "a failed reveal gate queues no RM_DOOPENHEALTH at all");
    }
  }

  @Test
  void showHpRecastWhileRevealedNeitherRefreshesNorTrains() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(id, SKILL_SHOWHP, 0, TAOIST_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("显血怪"), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, taoist.id()).mp();

      assertTrue(run(world, world.castSpell(
          taoist.id(), SKILL_SHOWHP, target.position(), target.id())));
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();
      assertEquals(1, events.stream().filter(WorldEvent.HealthRevealed.class::isInstance).count());
      events.clear();

      // Past the 1390 ms cast interval but well inside the reveal window.
      now.addAndGet(CAST_INTERVAL_SHOWHP);
      assertTrue(run(world, world.castSpell(
          taoist.id(), SKILL_SHOWHP, target.position(), target.id())));

      assertEquals(mana - 2 * SHOWHP_MANA, ability(world, taoist.id()).mp());
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count(),
          "RM_MAGICFIRE still fires: the branch never touches boSpellFire");
      assertTrue(events.stream().noneMatch(WorldEvent.HealthRevealed.class::isInstance),
          "the not m_boShowHP gate (Magic.pas:523) refuses a second delivery");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "boTrain stays unset, so a recast never trains");
    }
  }

  @Test
  void showHpExpiresAfterTheIntervalAndBroadcastsTheClose() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(id, SKILL_SHOWHP, 0, TAOIST_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("显血怪"), "0", new Position(7, 5), Direction.LEFT));
      // Magic.pas:526 with FixedRandom: GetRPow collapses to minSc, so the interval is the
      // GetPower13(minSc * 2 + 30) at skill level 0.
      int n = ability(world, taoist.id()).minSc() * 2 + 30;
      long intervalMillis = (long) (Math.rint((n - n / 3.0) / 4.0 + n / 3.0)) * 1_000L;

      assertTrue(run(world, world.castSpell(
          taoist.id(), SKILL_SHOWHP, target.position(), target.id())));
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();
      assertEquals(1, events.stream().filter(WorldEvent.HealthRevealed.class::isInstance).count());
      events.clear();

      // The expiry walk is strictly greater (ObjBase.pas:4029) and measures from the cast.
      now.addAndGet(intervalMillis + 1 - SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      WorldEvent.HealthConcealed concealed = one(events, WorldEvent.HealthConcealed.class);
      assertEquals(target.id(), concealed.objectId());
      assertTrue(events.stream().noneMatch(WorldEvent.HealthRevealed.class::isInstance));
    }
  }

  @Test
  void aRevealedPlayersNextStatusRepaintCarriesTheOpenHeathBit() {
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    // The target cloaks herself first (隐身术, charm-gated), so her own expiry repaint is the
    // "unrelated status change" that finally carries the latent STATE_OPENHEATH bit.
    store.save(charmed(targetId, SKILL_CLOAK, 0, 26));
    store.save(taoistWith(casterId, SKILL_SHOWHP, 0, TAOIST_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> targetEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot target = enter(world, targetId, "道友", 6, 5,
          LevelAbilities.JOB_TAOIST, targetEvents);

      assertTrue(run(world, world.castSpell(
          target.id(), SKILL_CLOAK, target.position(), 0)));
      // 隐身术 duration at skill level 0: GetPower13(30) = 15 s plus GetRPow(SC) * 3, which
      // FixedRandom collapses to the target's minSc.
      long cloakMillis = (15 + ability(world, target.id()).minSc() * 3) * 1_000L;
      targetEvents.clear();

      assertTrue(run(world, world.castSpell(
          caster.id(), SKILL_SHOWHP, target.position(), target.id())));
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();
      assertEquals(1, targetEvents.stream()
          .filter(WorldEvent.HealthRevealed.class::isInstance).count());
      targetEvents.clear();

      // Let the cloak lapse: expireTransparent rebuilds the word, and the revealed bit rides it.
      now.addAndGet(cloakMillis + 1);
      world.tickOnce();

      WorldEvent.CharacterStatusChanged repaint = one(targetEvents,
          WorldEvent.CharacterStatusChanged.class);
      assertEquals(target.id(), repaint.objectId());
      assertEquals(OPEN_HEATH_BIT, repaint.charStatus(),
          "GetCharStatus folds m_nCharStatusEx's low 20 bits in (ObjBase.pas:20087); the cloak "
              + "bit is gone and only the latent 心灵启示 bit remains");
    }
  }

  @Test
  void showHpReachesTheTargetPlayerAndEveryObserver() {
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID observerId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(casterId, SKILL_SHOWHP, 0, TAOIST_LEVEL));
    store.save(plain(targetId, LevelAbilities.JOB_WARRIOR, 20));
    store.save(plain(observerId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> targetEvents = new ArrayList<>();
      List<WorldEvent> observerEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot target = enter(world, targetId, "战士", 6, 5,
          LevelAbilities.JOB_WARRIOR, targetEvents);
      enter(world, observerId, "路人", 8, 5, LevelAbilities.JOB_WARRIOR, observerEvents);
      casterEvents.clear();
      targetEvents.clear();
      observerEvents.clear();

      assertTrue(run(world, world.castSpell(
          caster.id(), SKILL_SHOWHP, target.position(), target.id())));
      now.addAndGet(SHOW_HP_DELAY_MILLIS);
      world.tickOnce();

      // SendRefMsg(RM_OPENHEALTH) reaches every player in range — the target included, because
      // Delphi's broadcast square covers the object's own cell.
      assertEquals(1, casterEvents.stream()
          .filter(WorldEvent.HealthRevealed.class::isInstance).count());
      WorldEvent.HealthRevealed toTarget = one(targetEvents, WorldEvent.HealthRevealed.class);
      assertEquals(target.id(), toTarget.object().id());
      assertEquals(1, observerEvents.stream()
          .filter(WorldEvent.HealthRevealed.class::isInstance).count());
    }
  }

  @Test
  void showHpRejectsSelfNpcAndCorpseTargets() {
    UUID casterId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(casterId, SKILL_SHOWHP, 0, TAOIST_LEVEL));
    store.save(wizardWith(wizardId, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> wizardEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 5, 7,
          LevelAbilities.JOB_WIZARD, wizardEvents);
      WorldObjectSnapshot npc = run(world, world.spawnNpc(
          "老兵", "0", new Position(6, 5), 0, Direction.DOWN));
      WorldObjectSnapshot corpse = run(world, world.spawnMonster(
          undead("靶子"), "0", new Position(7, 7), Direction.LEFT));
      casterEvents.clear();
      wizardEvents.clear();

      // Delphi's gate is only TargeTBaseObject <> nil, but the engine's shared target rule
      // rejects self outright — the original client cannot click itself for this skill.
      assertFalse(run(world, world.castSpell(
          caster.id(), SKILL_SHOWHP, caster.position(), caster.id())));
      assertEquals(WorldEvent.SpellRejection.INVALID_TARGET,
          one(casterEvents, WorldEvent.SpellRejected.class).reason());

      // An NPC is a static stand-in with no meaningful HP; the engine's rule excludes it too.
      assertFalse(run(world, world.castSpell(
          caster.id(), SKILL_SHOWHP, npc.position(), npc.id())));
      assertEquals(2, casterEvents.stream()
          .filter(WorldEvent.SpellRejected.class::isInstance).count());

      // A corpse fails the shared alive check: kill it with 圣言术 first, then try to reveal.
      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, corpse.position(), corpse.id())));
      assertEquals(0, ability(world, corpse.id()).hp());
      casterEvents.clear();
      assertFalse(run(world, world.castSpell(
          caster.id(), SKILL_SHOWHP, corpse.position(), corpse.id())));
      assertEquals(WorldEvent.SpellRejection.INVALID_TARGET,
          one(casterEvents, WorldEvent.SpellRejected.class).reason());
    }
  }

  @Test
  void showHpJobAndLevelGates() {
    UUID warriorId = UUID.randomUUID();
    UUID youngId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(taoistWith(warriorId, SKILL_SHOWHP, 0, 30, LevelAbilities.JOB_WARRIOR));
    store.save(taoistWith(youngId, SKILL_SHOWHP, 0, 22));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> warriorEvents = new ArrayList<>();
      List<WorldEvent> youngEvents = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, warriorId, "战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, warriorEvents);
      WorldObjectSnapshot young = enter(world, youngId, "小道", 6, 5,
          LevelAbilities.JOB_TAOIST, youngEvents);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("显血怪"), "0", new Position(7, 5), Direction.LEFT));

      assertFalse(run(world, world.castSpell(
          warrior.id(), SKILL_SHOWHP, target.position(), target.id())));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(warriorEvents, WorldEvent.SpellRejected.class).reason(), "row 28 is job = 2 (道士)");
      assertFalse(run(world, world.castSpell(
          young.id(), SKILL_SHOWHP, target.position(), target.id())));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(youngEvents, WorldEvent.SpellRejected.class).reason(), "row 28's NeedL1 = 23");
    }
  }

  // ------------------------------------------------------------------ 圣言术

  @Test
  void turnUndeadKillsAnUndeadMonsterInstantly() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          undead("骷髅兵"), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, target.position(), target.id())));

      assertEquals(mana - KILLUNDEAD_MANA, ability(world, wizard.id()).mp());
      assertEquals(0, ability(world, target.id()).hp(), "m_WAbil.HP := 0 (Magic.pas:917)");
      // FixedRandom: Random(2) = 0 passes the level gate (39 > 1), Random(100) = 0 is below the
      // 15 + 39 = 54% kill chance, so the instant kill lands.
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(target.id(), fired.targetId());
      WorldEvent.ObjectDied died = one(events, WorldEvent.ObjectDied.class);
      assertEquals(target.id(), died.victim().id());
      assertEquals(wizard.id(), died.killerId(), "SetLastHiter credits the caster");
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance),
          "TAnimalObject.Struck emits no wire frame — no SM_STRUCK for 圣言术");
      assertTrue(indexOf(events, WorldEvent.MagicFired.class)
          < indexOf(events, WorldEvent.ObjectDied.class),
          "RM_MAGICFIRE closes DoSpell before the monster's Die is processed");
      WorldEvent.ExperienceGained exp = one(events, WorldEvent.ExperienceGained.class);
      assertEquals(50, exp.gained(), "the kill runs the ordinary death chain: drops + experience");
      assertEquals(1, one(events, WorldEvent.SkillTrainingChanged.class).magic().skill()
          .trainingPoints(), "boTrain only on a successful instant kill");
    }
  }

  @Test
  void turnUndeadOnALivingMonsterOnlyBurnsMana() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          statue("活物"), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, target.position(), target.id())));

      assertEquals(mana - KILLUNDEAD_MANA, ability(world, wizard.id()).mp());
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectDied.class::isInstance),
          "the LA_UNDEAD gate (Magic.pas:903) exits before Struck and before any roll");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
      assertEquals(100, ability(world, target.id()).hp());
    }
  }

  @Test
  void turnUndeadOnAPlayerTargetDoesNothingButBurnMana() {
    UUID casterId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(casterId, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));
    store.save(plain(victimId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, casterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot victim = enter(world, victimId, "战士", 7, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, victim.position(), victim.id())));

      assertEquals(mana - KILLUNDEAD_MANA, ability(world, wizard.id()).mp());
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectDied.class::isInstance),
          "players default to m_btLifeAttrib = 0 (ObjBase.pas:1244), never LA_UNDEAD");
      assertEquals(ability(world, victim.id()).maxHp(), ability(world, victim.id()).hp());
    }
  }

  @Test
  void turnUndeadFreezesAMonsterThatCannotRetarget() {
    UUID casterId = UUID.randomUUID();
    UUID bystanderId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(casterId, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));
    store.save(plain(bystanderId, LevelAbilities.JOB_WARRIOR, 20));

    // Safe zone of radius 2 around (30,30): the caster stands inside it. The monster spawns
    // before any acquirable player is around, so it has no target when the cast lands.
    GameMap town = GameMap.empty("0", "比奇省", 60, 60);
    town.addStartPoint(new StartPoint(new Position(30, 30), 2));
    // MaxRandom: the kill roll (Random(100) = 99 vs the 54% chance) fails, so the monster lives
    // and the fear freeze stays observable.
    try (WorldEngine world = engine(store, new MaxRandom(), town)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "法师", 30, 30,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          chaser("骷髅兵", true), "0", new Position(33, 30), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(
          caster.id(), SKILL_KILLUNDEAD, target.position(), target.id())));

      assertTrue(events.stream().noneMatch(WorldEvent.ObjectDied.class::isInstance),
          "the kill roll failed");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
      assertEquals(100, ability(world, target.id()).hp());

      // The bystander arrives only after the freeze is set. Struck refused the retarget
      // (IsProperTarget from the monster's side fails inside the safe zone), so
      // m_boRunAwayMode froze the monster for 10 s — it must not chase the bystander even
      // though the bystander is a perfectly good target.
      enter(world, bystanderId, "战士", 36, 30, LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      for (int tick = 0; tick < 9; tick++) {
        now.addAndGet(1_000);
        world.tickOnce();
        assertTrue(events.stream().noneMatch(WorldEvent.ObjectMoved.class::isInstance),
            "m_boRunAwayMode skips the whole chase/attack block (ObjMon.pas:447)");
        assertTrue(events.stream().noneMatch(WorldEvent.ObjectAttacked.class::isInstance));
      }

      // Past m_dwRunAwayTime the freeze clears and the ordinary AI resumes.
      now.addAndGet(1_500);
      world.tickOnce();
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectMoved.class::isInstance),
          "the freeze expired and the monster resumes hunting the bystander");
    }
  }

  @Test
  void turnUndeadLevelGateBlocksMonstersAtOrAboveTheCaster() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_KILLUNDEAD, 3, WIZARD_LEVEL));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      // A level-40 undead monster: Random(2) = 0 gives 39, and 39 > 40 is false (Magic.pas:912).
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          undeadAt("骷髅王", 40), "0", new Position(7, 5), Direction.LEFT));
      int mana = ability(world, wizard.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, target.position(), target.id())));

      assertEquals(mana - 90, ability(world, wizard.id()).mp(),
          "skill level 3: Round(50 / 4 * 4) + 40 = 90");
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectDied.class::isInstance));
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
      assertEquals(100, ability(world, target.id()).hp());
    }
  }

  @Test
  void turnUndeadConfigCeilingBlocksMonstersAtTheBoundary() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(id, SKILL_KILLUNDEAD, 0, WIZARD_LEVEL));

    // magTurnUndeadLevel = 1: `targetLevel < 1` is false for every spawnable monster.
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900,
        5_000, 180_000, 200, 600_000, 0, 1, 2, 1, 1);
    try (WorldEngine world = new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)),
        now::get, new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()))) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          undead("骷髅兵"), "0", new Position(7, 5), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, target.position(), target.id())));

      assertTrue(events.stream().noneMatch(WorldEvent.ObjectDied.class::isInstance),
          "g_Config.nMagTurnUndeadLevel = 1 refuses the level-1 monster (strictly below)");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
      assertEquals(100, ability(world, target.id()).hp());
    }
  }

  @Test
  void turnUndeadDefaultCeilingLetsA49ThroughButNotA50() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    // A level-60 caster passes the Random(2) + (level - 1) > targetLevel gate against both
    // monsters, so the level-50 target is refused by the nMagTurnUndeadLevel ceiling alone.
    store.save(wizardWith(id, SKILL_KILLUNDEAD, 3, 60));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      WorldObjectSnapshot low = run(world, world.spawnMonster(
          undeadAt("骷髅兵", 49), "0", new Position(7, 5), Direction.LEFT));
      WorldObjectSnapshot high = run(world, world.spawnMonster(
          undeadAt("骷髅王", 50), "0", new Position(7, 7), Direction.LEFT));
      events.clear();

      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, low.position(), low.id())));
      assertEquals(0, ability(world, low.id()).hp(),
          "49 < nMagTurnUndeadLevel(50): the instant kill lands");

      now.addAndGet(CAST_INTERVAL_KILLUNDEAD);
      assertTrue(run(world, world.castSpell(
          wizard.id(), SKILL_KILLUNDEAD, high.position(), high.id())));
      assertEquals(100, ability(world, high.id()).hp(),
          "50 is not strictly below 50: the shipped default ceiling refuses it");
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "the second cast never trained");
    }
  }

  @Test
  void turnUndeadJobAndLevelGates() {
    UUID taoistId = UUID.randomUUID();
    UUID youngId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(wizardWith(taoistId, SKILL_KILLUNDEAD, 0, 40, LevelAbilities.JOB_TAOIST));
    store.save(wizardWith(youngId, SKILL_KILLUNDEAD, 0, 31));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> taoistEvents = new ArrayList<>();
      List<WorldEvent> youngEvents = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, taoistId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, taoistEvents);
      WorldObjectSnapshot young = enter(world, youngId, "小法", 6, 5,
          LevelAbilities.JOB_WIZARD, youngEvents);
      WorldObjectSnapshot target = run(world, world.spawnMonster(
          undead("骷髅兵"), "0", new Position(7, 5), Direction.LEFT));

      assertFalse(run(world, world.castSpell(
          taoist.id(), SKILL_KILLUNDEAD, target.position(), target.id())));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(taoistEvents, WorldEvent.SpellRejected.class).reason(), "row 32 is job = 1 (法师)");
      assertFalse(run(world, world.castSpell(
          young.id(), SKILL_KILLUNDEAD, target.position(), target.id())));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(youngEvents, WorldEvent.SpellRejected.class).reason(), "row 32's NeedL1 = 32");
    }
  }

  // ------------------------------------------------------------------ helpers

  /**
   * A monster that ticks the full AI path (so 心灵启示's expiry walk reaches it) but whose
   * walk/attack intervals are so large it never actually acts during a test.
   */
  private static MonsterTemplate statue(String name) {
    return new MonsterTemplate(name, 0, Ability.monster(100, 1, 1, 0, 0),
        12, 600_000, 600_000, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), false, 0, 0, 0);
  }

  /** An undead monster with the standard 600 ms cadence. */
  private static MonsterTemplate undead(String name) {
    return new MonsterTemplate(name, 0, Ability.monster(100, 1, 1, 0, 0),
        12, 600, 600, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), true, 0, 0, 0);
  }

  /** An undead monster at an explicit level ({@code Ability.monster} always builds level 1). */
  private static MonsterTemplate undeadAt(String name, int level) {
    return new MonsterTemplate(name, 0,
        new Ability(100, 100, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, level, 0,
            LevelExperience.forLevel(level)),
        12, 600, 600, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), true, 0, 0, 0);
  }

  /** An undead chaser for the fear-freeze test. */
  private static MonsterTemplate chaser(String name, boolean undead) {
    return new MonsterTemplate(name, 0, Ability.monster(100, 1, 1, 0, 0),
        12, 100, 100, 50, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), undead, 0, 0, 0);
  }

  /** One character who knows {@code magicId} at {@code skillLevel}. */
  private static PlayerState taoistWith(UUID id, int magicId, int skillLevel, int level) {
    return taoistWith(id, magicId, skillLevel, level, LevelAbilities.JOB_TAOIST);
  }

  private static PlayerState taoistWith(
      UUID id, int magicId, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
  }

  private static PlayerState wizardWith(UUID id, int magicId, int skillLevel, int level) {
    return wizardWith(id, magicId, skillLevel, level, LevelAbilities.JOB_WIZARD);
  }

  private static PlayerState wizardWith(
      UUID id, int magicId, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
  }

  private static PlayerState plain(UUID id, int job, int level) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0);
  }

  /** A 护身符-equipped character who knows {@code magicId} at {@code skillLevel}. */
  private static PlayerState charmed(UUID id, int magicId, int skillLevel, int level) {
    return new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, level), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT,
            BackpackItem.of(StdItemsDb.byName("护身符").orElseThrow(), 501))),
        0, 0, 0, List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
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
    return engine(store, new FixedRandom(), GameMap.empty("0", "PoC", 30, 30));
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    return engine(store, random, GameMap.empty("0", "PoC", 30, 30));
  }

  private WorldEngine engine(PlayerStateStore store, Random random, GameMap map) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, random, store,
        ItemDatabase.of(StdItemsDb.all()));
  }

  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  /** Every random draw collapses to its range maximum. */
  private static final class MaxRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return bound - 1;
    }
  }

  private static <T extends WorldEvent> T one(List<WorldEvent> events, Class<T> type) {
    List<T> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matches.size(), "expected exactly one " + type.getSimpleName() + ": " + events);
    return matches.getFirst();
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
