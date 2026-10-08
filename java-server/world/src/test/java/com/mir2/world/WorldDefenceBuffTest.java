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
 * W46 skill slice: 幽灵盾 ({@code SKILL_HANGMAJINBUB} = 14, Magic.pas:451) and 神圣战甲术
 * ({@code SKILL_DEJIWONHO} = 15, Magic.pas:456) — the first two rows of the 护身符-gated
 * {@code SKILL_FIRECHARM{13} .. SKILL_BIGCLOAK{19}} case block (Magic.pas:420-497) that need no
 * summon, transparency or trap subsystem.
 *
 * <p>Shape under test: one {@code GetAttackPower(GetPower13(60) + LoWord(SC) * 10,
 * HiSC - LoSC + 1)} roll that doubles as the buff's <em>duration in seconds</em>;
 * {@code MagMakeDefenceArea}'s inclusive 7x7 square around the (possibly snapped) click;
 * {@code IsProperFriend}'s {@code HAM_GROUP} arm as the only relationship filter; the
 * {@code Inc(Result)} that sits <em>outside</em> {@code DefenceUp}'s own return value, so the
 * skill trains whenever a friend was in the square; the {@code 2 + Level div 7} bonus
 * {@code RecalcAbilitys} puts on the upper AC/MAC bound only; and the one-per-second status
 * countdown that ends with a green hint plus a single {@code RM_ABILITY} refresh.
 */
class WorldDefenceBuffTest {
  private static final int SKILL_HANGMAJINBUB = 14;
  private static final int SKILL_DEJIWONHO = 15;
  /** Magic.DB rows 14/15 both carry {@code spell = 15}: {@code Round(15 / 4 * 1)} + defSpell(0). */
  private static final int MANA_COST = 4;
  /** Level 25 clears row 15's {@code NeedL1 = 25} (row 14's is 22) so both skills can train. */
  private static final int TAOIST_LEVEL = 25;
  /** {@code 2 + (m_Abil.Level div 7)} at level 25 (ObjBase.pas:3418-3421). */
  private static final int DEFENCE_BONUS = 5;
  /** A naked level-25 taoist's natural magic defence range from {@code RecalcLevelAbilitys}. */
  private static final int NAKED_MIN_MAC = 2;
  private static final int NAKED_MAX_MAC = 5;
  /**
   * The duration roll at skill level 0 with every draw collapsed to its minimum:
   * {@code GetPower13(60)} = {@code ROUND(40 / 4 * 1 + 20)} = 30, plus
   * {@code LoWord(SC) * 10} = 20 for a level-25 taoist.
   */
  private static final int LEVEL0_SECONDS = 50;
  /** The same roll at skill level 3: {@code GetPower13(60)} = 60, plus the same 20. */
  private static final int LEVEL3_SECONDS = 80;
  /** {@code ClientSpellXY}'s shared cast interval plus row 14/15's {@code Delay = 40}. */
  private static final long CAST_INTERVAL_MILLIS = 1_390;

  private final AtomicLong now = new AtomicLong();

  @Test
  void holyArmourLiftsTheUpperAcBoundOnly() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_DEJIWONHO, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      // An empty-ground click on the caster's own cell: DoSpell reads only nTargetX/nTargetY.
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));

      Ability after = ability(world, taoist.id());
      assertEquals(mana - MANA_COST, after.mp());
      assertEquals(DEFENCE_BONUS, after.maxAc(),
          "STATE_DEFENCEUP adds 2 + Level div 7 to the upper AC bound (ObjBase.pas:3418)");
      assertEquals(0, after.minAc(), "the lower AC bound is never touched");
      assertEquals(NAKED_MAX_MAC, after.maxMac(), "神圣战甲术 leaves the magic defence alone");

      WorldEvent.MagicFired fired = one(events, WorldEvent.MagicFired.class);
      assertEquals(taoist.position(), fired.target());
      assertEquals(0, fired.targetId(), "an untargeted ground click names no object");
      assertEquals(SKILL_DEJIWONHO, fired.magic().id());
      assertEquals(String.format("防御力增加%d秒", LEVEL0_SECONDS),
          one(events, WorldEvent.SystemMessage.class).message());

      LearnedMagic trained = one(events, WorldEvent.SkillTrainingChanged.class).magic();
      assertEquals(0, trained.skill().level());
      assertEquals(1, trained.skill().trainingPoints(),
          "FixedRandom collapses Random(3) + 1 to its minimum");

      WorldEvent.ItemDurabilityChanged wear = one(events, WorldEvent.ItemDurabilityChanged.class);
      assertEquals(EquipmentSlot.ARM_RING_LEFT, wear.slot());
      assertEquals(StdItemsDb.byName("护身符").orElseThrow().duraMax() - 100, wear.dura(),
          "CheckAmulet/UseAmulet spends exactly one 100-unit 护身符 charge");
    }
  }

  @Test
  void ghostShieldLiftsTheUpperMacBoundOnly() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_HANGMAJINBUB, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_HANGMAJINBUB, taoist.position(), 0)));

      Ability after = ability(world, taoist.id());
      assertEquals(NAKED_MAX_MAC + DEFENCE_BONUS, after.maxMac(),
          "STATE_MAGDEFENCEUP adds 2 + Level div 7 to the upper MAC bound (ObjBase.pas:3420)");
      assertEquals(NAKED_MIN_MAC, after.minMac(), "the lower MAC bound is never touched");
      assertEquals(0, after.maxAc(), "幽灵盾 leaves the physical defence alone");
      assertEquals(String.format("魔法防御力增加%d秒", LEVEL0_SECONDS),
          one(events, WorldEvent.SystemMessage.class).message());
      assertEquals(SKILL_HANGMAJINBUB, one(events, WorldEvent.MagicFired.class).magic().id());
    }
  }

  @Test
  void theSquareIsInclusiveAtThreeCellsAndEmptyAtFour() {
    UUID casterId = UUID.randomUUID();
    UUID nearId = UUID.randomUUID();
    UUID farId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(casterId, SKILL_DEJIWONHO, 0));
    store.save(plain(nearId, LevelAbilities.JOB_WARRIOR, 20));
    store.save(plain(farId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      // The diagonal corner is still inside `for i := nStartX to nEndX` — Chebyshev, not Euclid.
      WorldObjectSnapshot near = enter(world, nearId, "边界", 8, 8,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot far = enter(world, farId, "界外", 9, 9,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      party(world, caster.id(), near.id(), "边界");
      party(world, caster.id(), far.id(), "界外");
      int nearAc = ability(world, near.id()).maxAc();
      int farAc = ability(world, far.id()).maxAc();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_DEJIWONHO, new Position(5, 5), 0)));

      // The bonus is the *recipient's* 2 + Level div 7 — a level-20 warrior gets 4, not the
      // caster's 5 (ObjBase.pas:3418 reads m_Abil.Level of the object being recalculated).
      assertEquals(nearAc + 4, ability(world, near.id()).maxAc(),
          "Chebyshev distance 3 is still inside MagMakeDefenceArea's ±3 square");
      assertEquals(farAc, ability(world, far.id()).maxAc(),
          "Chebyshev distance 4 is already outside it");
    }
  }

  @Test
  void strangersAndMonstersAreNeverBuffed() {
    UUID casterId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(casterId, SKILL_DEJIWONHO, 0));
    store.save(plain(strangerId, LevelAbilities.JOB_WARRIOR, 20));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot caster = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      WorldObjectSnapshot stranger = enter(world, strangerId, "路人", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(stationaryDummy("木桩"), "0",
          new Position(4, 5), Direction.DOWN));
      int strangerAc = ability(world, stranger.id()).maxAc();
      int dummyMaxAc = ability(world, dummy.id()).maxAc();

      assertTrue(run(world, world.castSpell(caster.id(), SKILL_DEJIWONHO, new Position(5, 5), 0)));

      assertEquals(strangerAc, ability(world, stranger.id()).maxAc(),
          "IsFriend's HAM_GROUP arm only answers True for Self or a party member");
      assertEquals(dummyMaxAc, ability(world, dummy.id()).maxAc(),
          "IsFriend requires RC_PLAYOBJECT, so a monster on the square is skipped");
    }
  }

  @Test
  void aShorterRecastKeepsTheLongerRunningWindow() {
    // A level-3 神圣战甲术 needs Magic.DB row 15's NeedL3 = 29 (MagicDefinition.requiredLevel(3)),
    // so both taoists here are 29: minSc = 3 makes the two rolls 90 s (level 3) and 60 s
    // (level 0), and the AC bonus is 2 + 29 div 7 = 6.
    int strongSeconds = 90;
    int weakSeconds = 60;
    int level29Bonus = 6;
    UUID strongId = UUID.randomUUID();
    UUID weakId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(strongId, SKILL_DEJIWONHO, 3, 29));
    store.save(charmed(weakId, SKILL_DEJIWONHO, 0, 29));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot strong = enter(world, strongId, "强盾", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      WorldObjectSnapshot weak = enter(world, weakId, "弱盾", 6, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      party(world, strong.id(), weak.id(), "弱盾");

      assertTrue(run(world, world.castSpell(strong.id(), SKILL_DEJIWONHO, new Position(5, 5), 0)));
      assertEquals(level29Bonus, ability(world, weak.id()).maxAc());

      // The level-0 recast lands 10 s into the level-3 window and rolls only 60 s.
      now.set(10_000);
      assertTrue(run(world, world.castSpell(weak.id(), SKILL_DEJIWONHO, new Position(5, 5), 0)));

      // DefenceUp keeps the longer of the two windows, so the deadline stays the level-3 one
      // measured from the first cast (t = 90 s) — not the t = 70 s a plain overwrite would give.
      now.set(10_000 + weakSeconds * 1_000L + 1);
      world.tickOnce();
      assertEquals(level29Bonus, ability(world, weak.id()).maxAc(),
          "the shorter roll must not shorten the running window");

      now.set(strongSeconds * 1_000L - 1);
      world.tickOnce();
      assertEquals(level29Bonus, ability(world, weak.id()).maxAc(),
          "…and it must not extend it either: the deadline is still the first cast's");

      now.set(strongSeconds * 1_000L);
      world.tickOnce();
      assertEquals(0, ability(world, weak.id()).maxAc(),
          "90 s after the first cast the status is gone");
    }
  }

  @Test
  void theBuffNeverLeaksIntoThePersistedCharacterRecord() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_DEJIWONHO, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc());

      // Player.setAbility rebases the naked m_Abil from the working ability (which already
      // carries STATE_DEFENCEUP), and PlayerState persists that naked copy — the timed bonus
      // has to come back off, or a relog would keep it and the next cast would stack onto it.
      assertEquals(0, run(world, world.playerState(taoist.id())).ability().maxAc(),
          "the saved character record must not carry a transient status bonus");

      now.addAndGet(CAST_INTERVAL_MILLIS);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc(),
          "a second cast refreshes the window instead of doubling the bonus");
      assertEquals(0, run(world, world.playerState(taoist.id())).ability().maxAc());
    }
  }

  @Test
  void theWindowExpiresWithItsHintAndOneAbilityRefresh() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_DEJIWONHO, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc());
      events.clear();

      now.addAndGet(LEVEL0_SECONDS * 1_000L - 1);
      world.tickOnce();
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc(),
          "the status counts down one per second and is still live one tick early");
      assertEquals(0, events.stream().filter(WorldEvent.SystemMessage.class::isInstance).count());

      now.addAndGet(1);
      world.tickOnce();
      assertEquals(0, ability(world, taoist.id()).maxAc(), "RecalcAbilitys runs on expiry");
      assertEquals("防御力恢复正常", one(events, WorldEvent.SystemMessage.class).message());
      assertEquals(1, events.stream().filter(WorldEvent.AbilityChanged.class::isInstance).count(),
          "boNeedRecalc collapses the whole status walk into a single RM_ABILITY");
    }
  }

  @Test
  void bothWindowsExpireOnTheirOwnTickButShareOneAbilityRefresh() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), amuletOnly(), 0, 0, 0,
        List.of(new PlayerSkill(SKILL_DEJIWONHO, 0, 0, 0),
            new PlayerSkill(SKILL_HANGMAJINBUB, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));
      now.addAndGet(CAST_INTERVAL_MILLIS);
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_HANGMAJINBUB, taoist.position(), 0)));
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc());
      assertEquals(NAKED_MAX_MAC + DEFENCE_BONUS, ability(world, taoist.id()).maxMac());
      events.clear();

      // The second cast landed 1.39 s later, so the AC window closes first and on its own tick.
      now.addAndGet(LEVEL0_SECONDS * 1_000L - CAST_INTERVAL_MILLIS + 1);
      world.tickOnce();
      assertEquals(0, ability(world, taoist.id()).maxAc());
      assertEquals(NAKED_MAX_MAC + DEFENCE_BONUS, ability(world, taoist.id()).maxMac());
      List<String> hints = events.stream().filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast).map(WorldEvent.SystemMessage::message).toList();
      assertEquals(List.of("防御力恢复正常"), hints,
          "STATE_DEFENCEUP (9) is walked before STATE_MAGDEFENCEUP (10), which is still live");
      assertEquals(1, events.stream().filter(WorldEvent.AbilityChanged.class::isInstance).count());

      now.addAndGet(CAST_INTERVAL_MILLIS);
      world.tickOnce();
      assertEquals(NAKED_MAX_MAC, ability(world, taoist.id()).maxMac());
      assertEquals(List.of("防御力恢复正常", "魔法防御力恢复正常"),
          events.stream().filter(WorldEvent.SystemMessage.class::isInstance)
              .map(WorldEvent.SystemMessage.class::cast)
              .map(WorldEvent.SystemMessage::message).toList());
    }
  }

  @Test
  void noCharmMeansNoBuffButTheManaIsAlreadySpent() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, TAOIST_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0, List.of(PlayerSkill.learned(SKILL_DEJIWONHO))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      int mana = ability(world, taoist.id()).mp();
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)),
          "mana is spent and +GOOD answered before CheckAmulet ever runs");
      assertEquals(mana - MANA_COST, ability(world, taoist.id()).mp());
      assertEquals(0, ability(world, taoist.id()).maxAc());
      assertEquals(1, events.stream().filter(WorldEvent.SpellFizzled.class::isInstance).count());
      assertEquals(0, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count(),
          "boSpellFail stays True, so DoSpell exits before the RM_MAGICFIRE broadcast");
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count());
    }
  }

  @Test
  void thePerRowNeedLevelAndJobGatesStillApply() {
    UUID youngId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(youngId, SKILL_DEJIWONHO, 0, TAOIST_LEVEL - 1));
    store.save(charmed(wizardId, SKILL_HANGMAJINBUB, 0, 30, LevelAbilities.JOB_WIZARD));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> youngEvents = new ArrayList<>();
      List<WorldEvent> wizardEvents = new ArrayList<>();
      WorldObjectSnapshot young = enter(world, youngId, "小道", 5, 5,
          LevelAbilities.JOB_TAOIST, youngEvents);
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 6, 5,
          LevelAbilities.JOB_WIZARD, wizardEvents);

      assertFalse(run(world, world.castSpell(young.id(), SKILL_DEJIWONHO, young.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.LEVEL_TOO_LOW,
          one(youngEvents, WorldEvent.SpellRejected.class).reason(),
          "row 15 needs level 25 while row 14 needs only 22");
      assertEquals(0, ability(world, young.id()).maxAc());

      assertFalse(run(world, world.castSpell(wizard.id(), SKILL_HANGMAJINBUB, wizard.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.WRONG_JOB,
          one(wizardEvents, WorldEvent.SpellRejected.class).reason(),
          "both rows are job = 2 (道士) in Magic.DB");
    }
  }

  @Test
  void anEmptySquareStillBurnsTheCharmWithoutTraining() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_DEJIWONHO, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, events);
      events.clear();

      // Chebyshev 8 is exactly MAGIC_ATTACK_RANGE, and the ±3 square around (13,5) is empty.
      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, new Position(13, 5), 0)));

      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count(),
          "the cast frame goes out even with nobody to buff");
      assertEquals(new Position(13, 5), one(events, WorldEvent.MagicFired.class).target());
      assertEquals(0, events.stream().filter(WorldEvent.SkillTrainingChanged.class::isInstance).count(),
          "MagMakeDefenceArea returned 0, so boTrain stays unset");
      assertEquals(0, events.stream().filter(WorldEvent.SystemMessage.class::isInstance).count(),
          "nobody was buffed, so nobody is told how long the buff lasts");
      assertEquals(0, ability(world, taoist.id()).maxAc());
      assertEquals(1, events.stream().filter(WorldEvent.ItemDurabilityChanged.class::isInstance).count(),
          "UseAmulet already ran before the square was walked");
    }
  }

  @Test
  void theRaisedAcAbsorbsMeleeDamage() {
    UUID id = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(charmed(id, SKILL_DEJIWONHO, 0));

    try (WorldEngine world = maxRollEngine(store)) {
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      // 准确 99 so the 敏捷 dodge check can never swallow the blow, DC 10..10 so every landed
      // hit is exactly ten before the defender's AC roll.
      MonsterTemplate bruiser = new MonsterTemplate("练功师", 0,
          Ability.monster(100_000, 10, 10, 0, 0), 12, 600, 600, 0, MonsterBehavior.AGGRESSIVE,
          List.of(), List.of(), false, 0, 99);
      run(world, world.spawnMonster(bruiser, "0", new Position(6, 5), Direction.LEFT));

      int before = ability(world, taoist.id()).hp();
      now.addAndGet(600);
      world.tickOnce();
      assertEquals(10, before - ability(world, taoist.id()).hp(),
          "a naked taoist has AC 0..0, so the full DC lands");

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_DEJIWONHO, taoist.position(), 0)));
      assertEquals(DEFENCE_BONUS, ability(world, taoist.id()).maxAc());

      int mid = ability(world, taoist.id()).hp();
      now.addAndGet(600);
      world.tickOnce();
      assertEquals(10 - DEFENCE_BONUS, mid - ability(world, taoist.id()).hp(),
          "the same blow now meets the raised upper AC bound that _Attack rolls against");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static PlayerState charmed(UUID id, int magicId, int skillLevel) {
    return charmed(id, magicId, skillLevel, TAOIST_LEVEL, LevelAbilities.JOB_TAOIST);
  }

  private static PlayerState charmed(UUID id, int magicId, int skillLevel, int level) {
    return charmed(id, magicId, skillLevel, level, LevelAbilities.JOB_TAOIST);
  }

  /** One 护身符-equipped character who knows {@code magicId} at {@code skillLevel}. */
  private static PlayerState charmed(UUID id, int magicId, int skillLevel, int level, int job) {
    return new PlayerState(id, levelAbility(job, level), List.of(), amuletOnly(), 0, 0, 0,
        List.of(new PlayerSkill(magicId, skillLevel, 0, 0)));
  }

  private static Equipment amuletOnly() {
    return new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT,
        BackpackItem.of(StdItemsDb.byName("护身符").orElseThrow(), 501)));
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

  /** The 木桩 shape shared with WorldAreaHealingTest: huge HP, no AI cadence to speak of. */
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

  private static Ability ability(WorldEngine world, int objectId) {
    return run(world, world.snapshot(objectId)).ability();
  }

  /** Every random draw collapses to its range minimum, so the duration roll is exact. */
  private WorldEngine deterministicEngine(PlayerStateStore store) {
    return engine(store, new FixedRandom());
  }

  /** Every random draw collapses to its range maximum, so the AC roll always takes the bonus. */
  private WorldEngine maxRollEngine(PlayerStateStore store) {
    return engine(store, new MaxRandom());
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
