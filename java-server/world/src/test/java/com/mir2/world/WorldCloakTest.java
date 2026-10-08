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
 * W47 skill slice: 隐身术 ({@code SKILL_CLOAK} = 18, Magic.pas:476) and 集体隐身术
 * ({@code SKILL_BIGCLOAK} = 19, Magic.pas:481) — the last standalone pair of the 护身符-gated
 * {@code SKILL_FIRECHARM{13} .. SKILL_BIGCLOAK{19}} block.
 *
 * <p>Shape under test: the shared charm gate; the
 * {@code GetPower13(30) + GetRPow(SC) * 3} duration in seconds; the {@code > 0} early exit that
 * makes a recast burn the charm without refreshing; the ±9-cell aggro sweep whose adjacent
 * monsters only drop their target on a {@code Random(2) = 0}; the walk/run rewrite to a one
 * second window (ObjBase.pas:2061); the countdown that clears {@code m_boHideMode} and repaints
 * {@code m_nCharStatus}; the group cloak's 3x3 click square, {@code HAM_GROUP} friend filter,
 * idle-slot precondition and 800 ms self-addressed delivery; and the monster search gate
 * {@code if not BaseObject.m_boHideMode or m_boCoolEye}.
 */
class WorldCloakTest {
  private static final int SKILL_CLOAK = 18;
  private static final int SKILL_BIGCLOAK = 19;
  /** {@code $80000000 shr STATE_TRANSPARENT(8)}. */
  private static final int TRANSPARENT_BIT = 0x0080_0000;
  /** Magic.DB row 18 needs level 20; row 19 needs 21 (and level 26 clears both rows' L2). */
  private static final int TAOIST_LEVEL = 26;
  /** Row 18 {@code spell = 5}: {@code Round(5 / 4 * 1)}. */
  private static final int CLOAK_MANA = 1;
  /** Row 19 {@code spell = 10}: {@code Round(10 / 4 * 1)} — banker's rounding keeps 2.5 at 2. */
  private static final int BIGCLOAK_MANA = 2;
  /** {@code ClientSpellXY}'s shared 1350 ms interval plus rows 18/19's {@code Delay = 50}. */
  private static final long CAST_INTERVAL_MILLIS = 1_400;

  private final AtomicLong now = new AtomicLong();

  @Test
  void cloakHidesTheCasterAndSpendsOneCharmCharge() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      // 隐身术 casts on the caster's own cell and never reads a target object.
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));

      assertEquals(mana - CLOAK_MANA, ability(world, taoist.id()).mp());
      assertEquals(TRANSPARENT_BIT, status(world, taoist.id()),
          "STATE_TRANSPARENT(8) is the only slot the cloak raises");
      WorldEvent.CharacterStatusChanged changed = one(events, WorldEvent.CharacterStatusChanged.class);
      assertEquals(taoist.id(), changed.objectId());
      assertEquals(TRANSPARENT_BIT, changed.charStatus(),
          "the wire word carries only LoWord/HiWord halves of this value; hitSpeed stays 0");
      assertEquals(0, changed.hitSpeed());
      assertEquals(taoist.position(), one(events, WorldEvent.MagicFired.class).target());
      assertEquals(1, one(events, WorldEvent.SkillTrainingChanged.class).magic().skill().trainingPoints(),
          "FixedRandom collapses TrainSkill's Random(3) + 1 to its minimum");
      WorldEvent.ItemDurabilityChanged wear = one(events, WorldEvent.ItemDurabilityChanged.class);
      assertEquals(EquipmentSlot.ARM_RING_LEFT, wear.slot());
      assertEquals(StdItemsDb.byName("护身符").orElseThrow().duraMax() - 100, wear.dura(),
          "CheckAmulet/UseAmulet spends exactly one 100-unit charge before the case body runs");
    }
  }

  @Test
  void recastingWhileCloakedBurnsTheCharmWithoutRefreshingOrTraining() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));
      events.clear();

      // Past the 1400 ms cast interval but well inside the 15 s minimum duration.
      now.addAndGet(CAST_INTERVAL_MILLIS + 100);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));

      assertEquals(TRANSPARENT_BIT, status(world, taoist.id()), "the window is not refreshed");
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count(),
          "RM_MAGICFIRE still fires: boSpellFail was cleared by the successful amulet check");
      assertEquals(0, events.stream().filter(WorldEvent.CharacterStatusChanged.class::isInstance).count(),
          "MagMakePrivateTransparent exits before StatusChanged on an already hidden caster");
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count(),
          "Result = False, so boTrain stays unset");
      assertEquals(1, events.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count(),
          "the second charm charge is spent anyway");
    }
  }

  @Test
  void aMovedStepPullsTheCloakDownToOneSecond() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));
      events.clear();

      // ObjBase.pas:2061: a successful walk rewrites the slot to 1 — the cloak survives until the
      // next whole-second countdown tick, not merely until the step lands.
      assertTrue(run(world, world.move(taoist.id(), new Position(6, 5), Direction.RIGHT,
          MovementKind.WALK)).moved());
      assertEquals(TRANSPARENT_BIT, status(world, taoist.id()),
          "still cloaked inside the 1 s window the step wrote");

      now.addAndGet(1_100);
      world.tickOnce();

      assertEquals(0, status(world, taoist.id()),
          "the countdown reaching zero clears m_boHideMode (ObjBase.pas:4170)");
      assertTrue(events.stream().anyMatch(event ->
              event instanceof WorldEvent.CharacterStatusChanged changed && changed.charStatus() == 0),
          "boChg repaints the status word once the slot lapses");
    }
  }

  @Test
  void cloakClearsTheTargetOfEveryDistantMonster() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      // View range 12 so the monster locks on from three cells away, and a 600 ms cadence.
      run(world, world.spawnMonster(chaser("追兵", 0), "0", new Position(8, 5), Direction.LEFT));

      now.addAndGet(600);
      world.tickOnce();
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectMoved.class::isInstance),
          "the monster first acquires the player and takes a step");
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));
      events.clear();

      // MagMakePrivateTransparent's ±9 sweep (Magic.pas:744-753) always clears a monster that
      // stands further than one cell away; the search gate then keeps it from re-acquiring.
      now.addAndGet(600);
      world.tickOnce();
      assertEquals(0, events.stream().filter(WorldEvent.ObjectMoved.class::isInstance).count(),
          "the cloak cleared the target and the hide gate blocks re-acquisition");
      assertEquals(0, events.stream().filter(WorldEvent.ObjectAttacked.class::isInstance).count());
    }
  }

  @Test
  void coolEyeMonsterKeepsHuntingThroughTheCloak() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      // CoolEye = 100 (Monster.DB column): UsrEngn.pas:1950's Random(100) < 100 always succeeds
      // under FixedRandom, so this instance sees through 隐身术 like 半兽勇士's 1% roll would.
      run(world, world.spawnMonster(chaser("天眼", 100), "0", new Position(8, 5), Direction.LEFT));
      now.addAndGet(600);
      world.tickOnce();
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_CLOAK, taoist.position(), 0)));
      events.clear();

      now.addAndGet(600);
      world.tickOnce();
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectMoved.class::isInstance),
          "the ±9 sweep cleared the target, but `not m_boHideMode or m_boCoolEye` lets it re-acquire");
    }
  }

  @Test
  void groupCloakDeliversEachFriendAfterEightHundredMillis() {
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(casterId, SKILL_BIGCLOAK, 0));
    store.save(plain(memberId, LevelAbilities.JOB_WARRIOR, 20));
    store.save(plain(strangerId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> memberEvents = new ArrayList<>();
      List<WorldEvent> strangerEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5, LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 6, 5, LevelAbilities.JOB_WARRIOR, memberEvents);
      WorldObjectSnapshot stranger = enter(world, strangerId, "路人", 7, 5, LevelAbilities.JOB_WARRIOR, strangerEvents);
      party(world, caster.id(), member.id(), "队友");
      int mana = ability(world, caster.id()).mp();
      casterEvents.clear();
      memberEvents.clear();
      strangerEvents.clear();

      // MagMakeGroupTransparent walks the inclusive 3x3 square around the click — (5,5) covers
      // the caster and the member, not the stranger two cells away.
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGCLOAK, caster.position(), 0)));

      assertEquals(mana - BIGCLOAK_MANA, ability(world, caster.id()).mp());
      assertEquals(0, status(world, member.id()), "nothing lands before the 800 ms delay");
      assertTrue(statusEvents(memberEvents, member.id()).isEmpty(),
          "the delayed RM_TRANSPARENT has not been delivered yet");
      assertTrue(statusEvents(casterEvents, caster.id()).isEmpty(),
          "the group branch never cloaks the caster at cast time");
      assertEquals(new Position(5, 5), one(casterEvents, WorldEvent.MagicFired.class).target());
      assertEquals(1, casterEvents.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count());

      now.addAndGet(800);
      world.tickOnce();

      assertEquals(TRANSPARENT_BIT, status(world, member.id()));
      assertEquals(TRANSPARENT_BIT, status(world, caster.id()), "the caster is a friend of themself");
      assertEquals(0, status(world, stranger.id()), "a stranger is never IsProperFriend(HAM_GROUP)");
      assertEquals(TRANSPARENT_BIT, statusEvents(memberEvents, member.id()).getFirst().charStatus());
      assertEquals(0, statusEvents(strangerEvents, stranger.id()).size());
      assertEquals(1, casterEvents.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count(),
          "affected > 0 trains the skill once");
    }
  }

  @Test
  void groupCloakSkipsFriendsWhoseSlotIsStillRunning() {
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(casterId, SKILL_BIGCLOAK, 0));
    // The member also knows 隐身术 and carries a charm, so it can arrive already cloaked.
    store.save(charmed(memberId, SKILL_CLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> memberEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5, LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot member = enter(world, memberId, "道友", 6, 5, LevelAbilities.JOB_TAOIST, memberEvents);
      party(world, caster.id(), member.id(), "道友");

      assertTrue(run(world, world.castSpell(member.id(), SKILL_CLOAK, member.position(), 0)));
      memberEvents.clear();
      casterEvents.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGCLOAK, caster.position(), 0)));
      now.addAndGet(800);
      world.tickOnce();

      assertEquals(TRANSPARENT_BIT, status(world, member.id()));
      assertEquals(0, statusEvents(memberEvents, member.id()).size(),
          "m_wStatusTimeArr[STATE_TRANSPARENT] = 0 is the precondition, so the running slot is skipped");
      assertEquals(TRANSPARENT_BIT, status(world, caster.id()), "the caster still got its own window");
    }
  }

  @Test
  void aFriendKeepsTheQueuedCloakAfterTheCasterLeaves() {
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(casterId, SKILL_BIGCLOAK, 0));
    store.save(plain(memberId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> memberEvents = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5, LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 6, 5, LevelAbilities.JOB_WARRIOR, memberEvents);
      party(world, caster.id(), member.id(), "队友");
      casterEvents.clear();
      memberEvents.clear();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGCLOAK, caster.position(), 0)));
      // SendDelayMsg queues on the receiver (ObjBase.pas:19374), so a logout in the 800 ms window
      // does not cancel the delivery the friend is already holding.
      run(world, world.leavePlayer(caster.id()));
      now.addAndGet(800);
      world.tickOnce();

      assertEquals(TRANSPARENT_BIT, status(world, member.id()));
      assertEquals(TRANSPARENT_BIT, statusEvents(memberEvents, member.id()).getFirst().charStatus());
    }
  }

  @Test
  void anEmptySquareStillBurnsTheCharmWithoutTraining() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_BIGCLOAK, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      // Chebyshev 8 is exactly MAGIC_ATTACK_RANGE and the ±1 square around (13,5) is empty.
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_BIGCLOAK, new Position(13, 5), 0)));

      assertEquals(mana - BIGCLOAK_MANA, ability(world, taoist.id()).mp());
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());
      assertEquals(0, status(world, taoist.id()), "the click square did not cover the caster");
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count(),
          "Result = 0, so boTrain stays unset even though the charm is gone");
      assertEquals(1, events.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count());
    }
  }

  @Test
  void jobAndLevelGatesStayInFrontOfTheCharmCheck() {
    UUID youngId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(youngId, SKILL_CLOAK, 0, 19, LevelAbilities.JOB_TAOIST));
    store.save(charmed(wizardId, SKILL_CLOAK, 0, 40, LevelAbilities.JOB_WIZARD));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> youngEvents = new ArrayList<>();
      List<WorldEvent> wizardEvents = new ArrayList<>();
      WorldObjectSnapshot young = enter(world, youngId, "小道", 5, 5, LevelAbilities.JOB_TAOIST, youngEvents);
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 6, 5, LevelAbilities.JOB_WIZARD, wizardEvents);

      assertFalse(run(world, world.castSpell(young.id(), SKILL_CLOAK, young.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(youngEvents, WorldEvent.SpellRejected.class).reason(),
          "row 18's NeedL1 = 20");
      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_CLOAK, wizard.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(wizardEvents, WorldEvent.SpellRejected.class).reason(),
          "row 18 is job = 2 (道士)");
      assertEquals(0, status(world, young.id()));
      assertEquals(0, status(world, wizard.id()));
      // Both rejections answer +FAIL, so no charm moved.
      assertEquals(0, youngEvents.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count());
      assertEquals(0, wizardEvents.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count());
    }
  }

  // ------------------------------------------------------------------ helpers

  /** A monster that will lock on from three cells away; {@code coolEye} feeds the spawn roll. */
  private static MonsterTemplate chaser(String name, int coolEye) {
    return new MonsterTemplate(name, 0, Ability.monster(100_000, 1, 1, 0, 0),
        12, 600, 600, 0, MonsterBehavior.AGGRESSIVE, List.of(), List.of(), false, 0, 0, coolEye);
  }

  /** One 护身符-equipped character who knows {@code magicId} at {@code skillLevel}. */
  private static PlayerState charmed(UUID id, int magicId, int skillLevel) {
    return charmed(id, magicId, skillLevel, TAOIST_LEVEL, LevelAbilities.JOB_TAOIST);
  }

  private static PlayerState charmed(UUID id, int magicId, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT,
            BackpackItem.of(StdItemsDb.byName("护身符").orElseThrow(), 501))),
        0, 0, 0, List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
  }

  private static PlayerState plain(UUID id, int job, int level) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0);
  }

  /** Invites {@code memberId} into {@code leaderId}'s party (CM_CREATEGROUP/CM_ADDGROUPMEMBER). */
  private void party(WorldEngine world, int leaderId, int memberId, String name) {
    assertTrue(run(world, world.setAllowGroup(memberId, true)));
    boolean created = run(world, world.groupMembers(leaderId)).isEmpty()
        ? run(world, world.createGroup(leaderId, name))
        : run(world, world.addGroupMember(leaderId, name));
    assertTrue(created, "party setup failed for " + name);
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

  private static int status(WorldEngine world, int objectId) {
    return run(world, world.snapshot(objectId)).status();
  }

  /** Every random draw collapses to its range minimum. */
  private WorldEngine deterministicEngine(PlayerStateStore store) {
    return engine(store, new FixedRandom());
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
  }

  private static final class FixedRandom extends Random {
    @Override
    public int nextInt(int bound) {
      return 0;
    }
  }

  /** Status repaints addressed to {@code objectId} inside a sink that also sees other actors. */
  private static List<WorldEvent.CharacterStatusChanged> statusEvents(
      List<WorldEvent> events, int objectId) {
    return events.stream()
        .filter(WorldEvent.CharacterStatusChanged.class::isInstance)
        .map(WorldEvent.CharacterStatusChanged.class::cast)
        .filter(changed -> changed.objectId() == objectId)
        .toList();
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
    public Optional<PlayerState> load(UUID id) {
      return Optional.ofNullable(states.get(id));
    }

    @Override
    public void save(PlayerState state) {
      states.put(state.characterId(), state);
    }
  }
}
