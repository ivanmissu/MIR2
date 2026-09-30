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
 * W40 skill slice: 群体治愈术 ({@code SKILL_BIGHEALLING} = 29, Magic.pas:532 →
 * {@code TMagicManager.MagBigHealing}, Magic.pas:172) — the engine's first area spell.
 *
 * <p>Shape under test: one power roll shared by every target, the inclusive 3x3 square around
 * the (possibly snapped) click, the {@code IsProperFriend} relationship narrowed to its
 * {@code HAM_GROUP} arm (caster + live party members only), the {@code HP &lt; MaxHP} gate that
 * both skips full-health friends and decides {@code boTrain}, and the 800 ms
 * {@code RM_MAGHEALING} delay that is addressed to the object rather than to the cell.
 */
class WorldAreaHealingTest {
  private static final int SKILL_BIGHEALLING = 29;
  /** Magic.DB row 29: {@code Round(12 / 4 * 1) + 30}. */
  private static final int MANA_COST = 33;
  private static final int TAOIST_LEVEL = 31;

  private final AtomicLong now = new AtomicLong();

  @Test
  void healsTheCasterAndPartyMembersButNeverStrangersOrMonsters() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();
    store.save(hurt(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING, 40));
    store.save(hurt(memberId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));
    store.save(hurt(strangerId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot stranger = enter(world, strangerId, "路人", 4, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          stationaryDummy("木桩"), "0", new Position(5, 4), Direction.DOWN));
      party(world, caster.id(), member.id(), "队友");

      int casterHp = run(world, world.snapshot(caster.id())).ability().hp();
      int memberHp = run(world, world.snapshot(member.id())).ability().hp();
      int strangerHp = run(world, world.snapshot(stranger.id())).ability().hp();
      int dummyHp = run(world, world.snapshot(dummy.id())).ability().hp();
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      int expected = expectedHeal(run(world, world.snapshot(caster.id())).ability());
      events.clear();

      // Ground click on the caster's own cell: the 3x3 square covers all four objects.
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp());
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(caster.position(), fired.target());
      assertEquals(0, fired.targetId(), "an untargeted ground click names no object");
      assertEquals(SKILL_BIGHEALLING, fired.magic().id());

      // RM_MAGHEALING lands 800 ms later (Magic.pas:185) — nothing moves before that.
      assertEquals(casterHp, run(world, world.snapshot(caster.id())).ability().hp());
      now.addAndGet(800);
      world.tickOnce();

      assertEquals(casterHp + expected, run(world, world.snapshot(caster.id())).ability().hp(),
          "IsProperFriend's HAM_GROUP arm starts with cret = Self");
      assertEquals(memberHp + expected, run(world, world.snapshot(member.id())).ability().hp(),
          "every party member on the square shares the single power roll");
      assertEquals(strangerHp, run(world, world.snapshot(stranger.id())).ability().hp(),
          "an unaffiliated player is not a friend of a grouped caster");
      assertEquals(dummyHp, run(world, world.snapshot(dummy.id())).ability().hp(),
          "IsFriend only ever answers True for RC_PLAYOBJECT");
    }
  }

  @Test
  void theSquareIsInclusiveAtOneCellAndEmptyAtTwo() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID nearId = UUID.randomUUID();
    UUID farId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING));
    store.save(hurt(nearId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));
    store.save(hurt(farId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      // The diagonal corner is still inside `for x := nX-1 to nX+1` — Chebyshev, not Euclid.
      WorldObjectSnapshot near = enter(world, nearId, "边界", 11, 11,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot far = enter(world, farId, "界外", 12, 12,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), near.id(), "边界");
      party(world, caster.id(), far.id(), "界外");

      int nearHp = run(world, world.snapshot(near.id())).ability().hp();
      int farHp = run(world, world.snapshot(far.id())).ability().hp();
      int expected = expectedHeal(run(world, world.snapshot(caster.id())).ability());

      assertTrue(run(world,
          world.castSpell(caster.id(), SKILL_BIGHEALLING, new Position(10, 10), 0)));
      now.addAndGet(800);
      world.tickOnce();

      assertEquals(nearHp + expected, run(world, world.snapshot(near.id())).ability().hp(),
          "(11,11) is the inclusive corner of the square centred on (10,10)");
      assertEquals(farHp, run(world, world.snapshot(far.id())).ability().hp(),
          "(12,12) is two cells away — outside GetMapBaseObjects' nRage = 1");
    }
  }

  @Test
  void fullHealthFriendsAreSkippedAndAnAllFullCastNeverTrains() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING));
    // One single missing hit point is enough to make the member a legal target; the heal then
    // clamps at MaxHP so the follow-up cast finds an all-full square.
    store.save(hurt(memberId, LevelAbilities.JOB_WARRIOR, 20, 0, 1));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), member.id(), "队友");

      int casterHp = run(world, world.snapshot(caster.id())).ability().hp();
      int memberMaxHp = run(world, world.snapshot(member.id())).ability().maxHp();
      events.clear();

      // The caster is at full HP, the party member is not: only the member is queued, and the
      // cast is still boTrain because *someone* needed the heal (Magic.pas:186).
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      now.addAndGet(800);
      world.tickOnce();
      assertEquals(casterHp, run(world, world.snapshot(caster.id())).ability().hp(),
          "a full-health friend produces no HP delta at all");
      assertEquals(memberMaxHp, run(world, world.snapshot(member.id())).ability().hp(),
          "the shared power roll is clamped at MaxHP");
      assertTrue(events.stream().anyMatch(WorldEvent.SkillTrainingChanged.class::isInstance));

      // Second cast: everybody is topped up again, so BaseObjectList yields no heal and the
      // skill must not train.
      now.addAndGet(2_000);
      events.clear();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      now.addAndGet(800);
      world.tickOnce();
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "no friend below MaxHP means boTrain stays False");
      assertTrue(events.stream().anyMatch(WorldEvent.MagicFired.class::isInstance),
          "RM_MAGICFIRE still goes out — boSpellFire is untouched by the empty heal list");
    }
  }

  @Test
  void aTargetThatLeavesThePartyInsideTheDelayIsDroppedButTheOthersStillHeal() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID stayerId = UUID.randomUUID();
    UUID quitterId = UUID.randomUUID();
    store.save(hurt(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING, 40));
    store.save(hurt(stayerId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));
    store.save(hurt(quitterId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      WorldObjectSnapshot stayer = enter(world, stayerId, "留队", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot quitter = enter(world, quitterId, "退队", 4, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), stayer.id(), "留队");
      party(world, caster.id(), quitter.id(), "退队");

      int stayerHp = run(world, world.snapshot(stayer.id())).ability().hp();
      int quitterHp = run(world, world.snapshot(quitter.id())).ability().hp();
      int expected = expectedHeal(run(world, world.snapshot(caster.id())).ability());

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      // The relationship is re-confirmed when the delayed heal lands (see castAreaHealing).
      assertTrue(run(world, world.setAllowGroup(quitter.id(), false)));
      // A friend who merely walks out of the square still gets healed: RM_MAGHEALING is
      // addressed to the object, not to the cell it stood on.
      assertTrue(run(world,
          world.move(stayer.id(), new Position(8, 5), Direction.RIGHT, MovementKind.RUN)).moved());

      now.addAndGet(800);
      world.tickOnce();
      assertEquals(stayerHp + expected, run(world, world.snapshot(stayer.id())).ability().hp());
      assertEquals(quitterHp, run(world, world.snapshot(quitter.id())).ability().hp(),
          "a player who left the party before the heal arrived is no longer a friend");
    }
  }

  @Test
  void aSnappedClickRecentresTheSquareOnTheNamedObject() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    store.save(state(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING));
    store.save(hurt(memberId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 9, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), member.id(), "队友");
      int memberHp = run(world, world.snapshot(member.id())).ability().hp();
      int expected = expectedHeal(run(world, world.snapshot(caster.id())).ability());
      events.clear();

      // CretInNearXY (ObjBase.pas:16854) snaps a click within one cell onto the object, so the
      // square centres on (9,5) and the member — two cells from the raw click — is inside it.
      assertTrue(run(world, world.castSpell(
          caster.id(), SKILL_BIGHEALLING, new Position(8, 5), member.id())));
      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(new Position(9, 5), fired.target(), "the snapped cell rides RM_MAGICFIRE");
      assertEquals(member.id(), fired.targetId());

      now.addAndGet(800);
      world.tickOnce();
      assertEquals(memberHp + expected, run(world, world.snapshot(member.id())).ability().hp());
    }
  }

  @Test
  void castGatesRejectWrongJobLowLevelAndCooldownWithoutTouchingAnyTarget() {
    RecordingStore store = new RecordingStore();
    UUID warriorId = UUID.randomUUID();
    UUID juniorId = UUID.randomUUID();
    UUID casterId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    store.save(state(warriorId, LevelAbilities.JOB_WARRIOR, 40, SKILL_BIGHEALLING));
    store.save(state(juniorId, LevelAbilities.JOB_TAOIST, 20, SKILL_BIGHEALLING));
    store.save(state(casterId, LevelAbilities.JOB_TAOIST, TAOIST_LEVEL, SKILL_BIGHEALLING));
    store.save(hurt(memberId, LevelAbilities.JOB_WARRIOR, 20, 0, 40));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> warriorEvents = new ArrayList<>();
      List<WorldEvent> juniorEvents = new ArrayList<>();
      List<WorldEvent> casterEvents = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, warriorId, "战士", 20, 20,
          LevelAbilities.JOB_WARRIOR, warriorEvents);
      WorldObjectSnapshot junior = enter(world, juniorId, "小道", 22, 20,
          LevelAbilities.JOB_TAOIST, juniorEvents);
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot member = enter(world, memberId, "队友", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), member.id(), "队友");
      int memberHp = run(world, world.snapshot(member.id())).ability().hp();
      warriorEvents.clear();
      juniorEvents.clear();
      casterEvents.clear();

      // Magic.DB row 29 is job = 2 (道士).
      assertFalse(run(world,
          world.castSpell(warrior.id(), SKILL_BIGHEALLING, warrior.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(warriorEvents, WorldEvent.SpellRejected.class).reason());

      // NeedL1 = 31: a level-20 taoist may own the row but cannot fire it.
      assertFalse(run(world,
          world.castSpell(junior.id(), SKILL_BIGHEALLING, junior.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(juniorEvents, WorldEvent.SpellRejected.class).reason());

      // A refused cast spends no mana and leaves every would-be target untouched.
      int mana = run(world, world.snapshot(caster.id())).ability().mp();
      assertFalse(run(world,
          world.castSpell(caster.id(), SKILL_BIGHEALLING, new Position(20, 20), 0)));
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE,
          one(casterEvents, WorldEvent.SpellRejected.class).reason());
      assertEquals(mana, run(world, world.snapshot(caster.id())).ability().mp());

      casterEvents.clear();
      assertTrue(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      assertFalse(run(world, world.castSpell(caster.id(), SKILL_BIGHEALLING, caster.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.TOO_FAST,
          one(casterEvents, WorldEvent.SpellRejected.class).reason());
      assertEquals(mana - MANA_COST, run(world, world.snapshot(caster.id())).ability().mp(),
          "the rejected second cast must not double-charge the mana");

      now.addAndGet(800);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(member.id())).ability().hp() > memberHp,
          "the single accepted cast is the only one that healed");
    }
  }

  // ------------------------------------------------------------------ helpers

  /** {@code GetPower(MPow) + LoWord(SC) * 2} with every FixedRandom draw collapsed to zero. */
  private static int expectedHeal(Ability caster) {
    // Magic.DB row 29: wPower/wMaxPower = 10/10, btDefPower/btDefMaxPower = 4/4.
    return (int) Math.rint(10 / 4.0) + 4 + caster.minSc() * 2;
  }

  private PlayerState state(UUID id, int job, int level, int magicId) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(magicId)));
  }

  /** The same character, entered {@code missing} HP below its maximum. */
  private PlayerState hurt(UUID id, int job, int level, int magicId, int missing) {
    Ability ability = levelAbility(job, level);
    Ability wounded = ability.withHp(ability.maxHp() - missing);
    List<PlayerSkill> skills = magicId == 0 ? List.of() : List.of(PlayerSkill.learned(magicId));
    return new PlayerState(id, wounded, List.of(), Equipment.empty(), 0, 0, 0, skills);
  }

  /** Invites {@code name} into {@code leaderId}'s party (CM_CREATEGROUP/CM_ADDGROUPMEMBER). */
  private void party(WorldEngine world, int leaderId, int memberId, String name) {
    assertTrue(run(world, world.setAllowGroup(memberId, true)));
    boolean created = run(world, world.groupMembers(leaderId)).isEmpty()
        ? run(world, world.createGroup(leaderId, name))
        : run(world, world.addGroupMember(leaderId, name));
    assertTrue(created, "party setup failed for " + name);
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

  /** Every random draw collapses to its range minimum, so the shared power roll is exact. */
  private WorldEngine deterministicEngine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
  }

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
