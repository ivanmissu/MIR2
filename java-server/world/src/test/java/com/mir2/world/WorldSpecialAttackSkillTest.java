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
 * W34 — 战士武器技第二批: the two toggled special-attack shapes 刺杀剑术
 * ({@code SKILL_ERGUM}, wHitMode 4) and 半月弯刀 ({@code SKILL_BANWOL}, wHitMode 5).
 *
 * <p>Unlike the passive 准确 skills of W33, these two are switches: {@code ClientSpellXY}
 * flips {@code m_boUseThrusting}/{@code m_boUseHalfMoon} and answers the client with a
 * {@code +LNG}/{@code +WID} tag, {@code ReadBook} turns the freshly learned book on with a
 * green hint, and login silently re-arms 刺杀 only (ObjBase.pas:9037-9073 / 16602 / 17377).
 * The swings themselves are {@code SwordLongAttack}/{@code SwordWideAttack}: a shared
 * {@code GetAttackPower} roll drives both the ordinary front target and a no-AC secondary
 * fan whose power is {@code nSecPwr} (ObjBase.pas:22169-22200).
 */
class WorldSpecialAttackSkillTest {
  private final AtomicLong now = new AtomicLong();

  // A level-50 warrior: DC := MakeLong(_MAX(50/5 - 1, 1), _MAX(1, 50/5)) = [9, 10]. Under the
  // FixedRandom bench every Random(bound) collapses to 0, so GetAttackPower always returns minDc.
  private static final int WARRIOR_LEVEL = 50;
  private static final int MIN_DC = 9;

  @Test
  void castingAToggleFlipsTheShapeWithoutSpendingManaOrACooldown() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    // 半月弯刀 (id 25) is chosen because — unlike 刺杀 — it is NOT auto-enabled on login, so the
    // switch starts cleanly OFF and the first press turns it ON.
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_BANWOL, 0, 0, 0))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "弯刀客", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      int mana = run(world, world.snapshot(player.id())).ability().mp();
      // Login never re-arms 半月弯刀, so entering emitted no toggle for it.
      assertTrue(events.stream().noneMatch(WorldEvent.WeaponSkillToggled.class::isInstance),
          "半月弯刀 must not be re-enabled on login");
      events.clear();

      // First press: OFF -> ON with the green hint and a +WID tag; IsWarrSkill suppresses the
      // mana/cooldown gates so it costs nothing and answers +GOOD.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_BANWOL,
          player.position(), 0)));
      WorldEvent.WeaponSkillToggled on = one(events, WorldEvent.WeaponSkillToggled.class);
      assertEquals(HitSpeed.SKILL_BANWOL, on.magicId());
      assertTrue(on.on(), "the first press enables the shape");
      assertEquals("开启半月弯刀", one(events, WorldEvent.SystemMessage.class).message());
      assertTrue(events.stream().anyMatch(WorldEvent.SpellAccepted.class::isInstance));
      events.clear();

      // Second press on the same tick: ON -> OFF. No m_dwMagicAttackInterval gate, no mana.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_BANWOL,
          player.position(), 0)));
      WorldEvent.WeaponSkillToggled off = one(events, WorldEvent.WeaponSkillToggled.class);
      assertFalse(off.on(), "the second press disables the shape");
      assertEquals("关闭半月弯刀", one(events, WorldEvent.SystemMessage.class).message());

      assertEquals(mana, run(world, world.snapshot(player.id())).ability().mp(),
          "toggling a weapon skill never spends mana");
    }
  }

  @Test
  void readingTheBookLearnsTheSkillAndAutoEnablesItsShape() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    StdItem thrustBook = StdItemsDb.byName("刺杀剑术").orElseThrow();
    StdItem halfMoonBook = StdItemsDb.byName("半月弯刀").orElseThrow();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(new BackpackItem(thrustBook, 6001, 0, 0),
            new BackpackItem(halfMoonBook, 6002, 0, 0)),
        Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "习刀", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      events.clear();

      // ReadBook (ObjBase.pas:17377): learn the row, then ThrustingOnOff(True) — the client is
      // told to start sending CM_LONGHIT via a green hint plus the +LNG tag.
      assertTrue(run(world, world.useItem(player.id(), 6001, "刺杀剑术")));
      assertEquals(HitSpeed.SKILL_ERGUM,
          one(events, WorldEvent.SkillLearned.class).magic().skill().magicId());
      WorldEvent.WeaponSkillToggled thrustOn = one(events, WorldEvent.WeaponSkillToggled.class);
      assertEquals(HitSpeed.SKILL_ERGUM, thrustOn.magicId());
      assertTrue(thrustOn.on());
      assertEquals("启用刺杀剑法", one(events, WorldEvent.SystemMessage.class).message());
      events.clear();

      // 半月弯刀's book behaves the same way through HalfMoonOnOff(True).
      assertTrue(run(world, world.useItem(player.id(), 6002, "半月弯刀")));
      WorldEvent.WeaponSkillToggled halfMoonOn = one(events, WorldEvent.WeaponSkillToggled.class);
      assertEquals(HitSpeed.SKILL_BANWOL, halfMoonOn.magicId());
      assertTrue(halfMoonOn.on());
      assertEquals("开启半月弯刀", one(events, WorldEvent.SystemMessage.class).message());
    }
  }

  @Test
  void loginReEnablesThrustingSilentlyButLeavesHalfMoonOff() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_ERGUM, 0, 0, 0),
            new PlayerSkill(HitSpeed.SKILL_BANWOL, 0, 0, 0))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "老兵", 5, 5, LevelAbilities.JOB_WARRIOR, events);

      // Login re-arms 刺杀 only, and does so without the green hint (ObjBase.pas:16602).
      List<WorldEvent.WeaponSkillToggled> toggles = events.stream()
          .filter(WorldEvent.WeaponSkillToggled.class::isInstance)
          .map(WorldEvent.WeaponSkillToggled.class::cast)
          .toList();
      assertEquals(1, toggles.size(), "only 刺杀 is re-enabled on login: " + events);
      assertEquals(HitSpeed.SKILL_ERGUM, toggles.getFirst().magicId());
      assertTrue(toggles.getFirst().on());
      assertTrue(events.stream().noneMatch(WorldEvent.SystemMessage.class::isInstance),
          "login re-enable is silent — no green hint");
      events.clear();

      // The flag really is armed: pressing the key now toggles it OFF rather than ON.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_ERGUM,
          player.position(), 0)));
      assertFalse(one(events, WorldEvent.WeaponSkillToggled.class).on(),
          "a login-armed 刺杀 toggles off on the first key press");
    }
  }

  @Test
  void thrustingStrikesTheFrontTargetAndTheCellBeyondItAndTrains() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_ERGUM, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "刺客", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      // Facing RIGHT: the thrust runs down the +x axis, hitting (6,5) then (7,5).
      WorldObjectSnapshot front =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      WorldObjectSnapshot beyond =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(7, 5), Direction.LEFT));
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 7), Direction.UP, observed::add));
      events.clear();

      now.addAndGet(5_000);
      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.LONG_HIT));
      assertTrue(result.accepted());

      // The front target is an ordinary AC roll on the shared nPower; the trainer's AC is 0.
      assertEquals(MIN_DC, struck(events, front.id()).damage(), "front target takes the full nPower");
      // The cell beyond takes nSecPwr = Round(nPower / (btTrainLv + 2) * (btLevel + 2)) whole,
      // with no armour roll: Round(9 / 5 * 2) = 4.
      int expectedSecondary = (int) Math.rint((double) MIN_DC / (MagicDefinition.HARDCODED_TRAIN_LEVEL + 2) * 2);
      assertEquals(4, expectedSecondary, "the W34 vector pins nSecPwr to 4");
      assertEquals(expectedSecondary, struck(events, beyond.id()).damage(),
          "the second cell takes the un-mitigated nSecPwr");

      // AttackDir answers RM_LONGHIT (only) because m_MagicErgumSkill <> nil.
      assertEquals(AttackKind.LONG_HIT, broadcast(observed).attack());

      // A penetrating primary hit trains the active shape a flat +1 (TrainSkill(skill, 1)).
      WorldEvent.SkillTrainingChanged trained = one(events, WorldEvent.SkillTrainingChanged.class);
      assertEquals(HitSpeed.SKILL_ERGUM, trained.magic().skill().magicId());
      assertEquals(1, trained.magic().skill().trainingPoints());
    }
  }

  @Test
  void halfMoonSweepsTheThreeCellFanAndSpendsItsMana() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_BANWOL, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "半月", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      // Facing RIGHT (code 2): primary at (6,5); the WideAttack fan is dir-1/dir+1/dir+2 =
      // UP_RIGHT (6,4), DOWN_RIGHT (6,6) and DOWN (5,6).
      WorldObjectSnapshot primary =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      WorldObjectSnapshot fanUp =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 4), Direction.LEFT));
      WorldObjectSnapshot fanDown =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 6), Direction.LEFT));
      WorldObjectSnapshot fanSide =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(5, 6), Direction.LEFT));
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 8), Direction.UP, observed::add));

      now.addAndGet(5_000);
      int manaBefore = run(world, world.snapshot(player.id())).ability().mp();
      events.clear();
      observed.clear();

      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.WIDE_HIT));
      assertTrue(result.accepted());

      assertEquals(MIN_DC, struck(events, primary.id()).damage(), "the front target takes the full nPower");
      // nSecPwr = Round(9 / (3 + 10) * 2) = Round(18/13) = 1, applied to each fan cell.
      int expectedSecondary = (int) Math.rint((double) MIN_DC / (MagicDefinition.HARDCODED_TRAIN_LEVEL + 10) * 2);
      assertEquals(1, expectedSecondary, "the W34 vector pins the 半月 nSecPwr to 1");
      assertEquals(expectedSecondary, struck(events, fanUp.id()).damage());
      assertEquals(expectedSecondary, struck(events, fanDown.id()).damage());
      assertEquals(expectedSecondary, struck(events, fanSide.id()).damage());

      assertEquals(AttackKind.WIDE_HIT, broadcast(observed).attack());

      // AttackDir spends DamageSpell(btDefSpell + GetMagicSpell) = 3 for id 25 before the swing.
      int manaAfter = run(world, world.snapshot(player.id())).ability().mp();
      assertEquals(3, manaBefore - manaAfter, "半月弯刀 spends its fixed 3 MP per swing");

      WorldEvent.SkillTrainingChanged trained = one(events, WorldEvent.SkillTrainingChanged.class);
      assertEquals(HitSpeed.SKILL_BANWOL, trained.magic().skill().magicId());
      assertEquals(1, trained.magic().skill().trainingPoints());
    }
  }

  @Test
  void anUnlearnedShapeIdentDegradesToAnOrdinaryFrontHit() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    // No 刺杀/半月 learned: AttackDir cannot answer RM_LONGHIT/RM_WIDEHIT, so the swing collapses
    // to a plain front-cell hit and never touches the cell beyond or the fan.
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "无技", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      WorldObjectSnapshot front =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      WorldObjectSnapshot beyond =
          run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(7, 5), Direction.LEFT));
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 7), Direction.UP, observed::add));
      events.clear();

      now.addAndGet(5_000);
      run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.LONG_HIT));

      // The front cell is struck, but the thrust's second cell never is.
      assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.ObjectStruck s
          && s.victim().id() == front.id()), "the front target is still hit");
      assertTrue(events.stream().noneMatch(e -> e instanceof WorldEvent.ObjectStruck s
          && s.victim().id() == beyond.id()), "an unlearned thrust must not reach the second cell");
      assertEquals(AttackKind.HIT, broadcast(observed).attack(),
          "an unlearned CM_LONGHIT is broadcast as a plain RM_HIT");
      // It also spends no mana and never emits a training frame for a skill it lacks.
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance));
    }
  }

  // ------------------------------------------------------------------ helpers

  private static WorldEvent.ObjectStruck struck(List<WorldEvent> events, int victimId) {
    return events.stream()
        .filter(WorldEvent.ObjectStruck.class::isInstance)
        .map(WorldEvent.ObjectStruck.class::cast)
        .filter(s -> s.victim().id() == victimId)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no ObjectStruck for " + victimId + " in " + events));
  }

  private static WorldEvent.ObjectAttacked broadcast(List<WorldEvent> events) {
    return events.stream()
        .filter(WorldEvent.ObjectAttacked.class::isInstance)
        .map(WorldEvent.ObjectAttacked.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no ObjectAttacked in " + events));
  }

  private static Ability levelAbility(int job, int level) {
    return LevelAbilities.forLevel(job, level, Ability.defaultPlayer()).restored();
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID id, String name, int x, int y,
      int job, List<WorldEvent> events) {
    return run(world, world.enterPlayer(id, name, "0", new Position(x, y), Direction.RIGHT,
        0, 0, job, events::add));
  }

  private WorldEngine engine(PlayerStateStore store) {
    return engine(store, new Random(20020522L));
  }

  private WorldEngine engine(PlayerStateStore store, Random random) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        random, store, ItemDatabase.of(StdItemsDb.all()));
  }

  private WorldEngine deterministicEngine(PlayerStateStore store) {
    return engine(store, new FixedRandom());
  }

  /** {@code nextInt(bound)} always answers 0 — every draw collapses to its range minimum. */
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

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
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
