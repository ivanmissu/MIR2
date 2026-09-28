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
 * W33 — 战士武器技第一批: 基本剑术 ({@code SKILL_ONESWORD}), 精神力战法
 * ({@code SKILL_ILKWANG}) and 攻杀剑术 ({@code SKILL_YEDO}), together with the 准确/敏捷
 * model they feed ({@code TBaseObject.RecalcHitSpeed}, ObjBase.pas:18551) and the
 * {@code _Attack} dodge check that finally gives those points a meaning
 * (ObjBase.pas:22240).
 */
class WorldWarriorSkillTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void recalcHitSpeedReproducesTheDelphiTable() {
    // The bare pass: DEFHIT / DEFSPEED with no 加点 and no skills (ObjBase.pas:18553).
    HitSpeed naked = HitSpeed.of(LevelAbilities.JOB_WARRIOR, List.of());
    assertEquals(HitSpeed.DEF_HIT, naked.hitPoint());
    assertEquals(HitSpeed.DEF_SPEED, naked.speedPoint());
    assertEquals(0, naked.hitPlus());
    assertEquals(0, naked.attackSkillCycle());

    // `if m_btJob = 2 then Inc(m_btSpeedPoint, 3)` — only 道士 gets the agility head start.
    assertEquals(HitSpeed.DEF_SPEED + 3,
        HitSpeed.of(LevelAbilities.JOB_TAOIST, List.of()).speedPoint());
    assertEquals(HitSpeed.DEF_SPEED,
        HitSpeed.of(LevelAbilities.JOB_WIZARD, List.of()).speedPoint());

    // Round(9/3*level), Round(8/3*level), Round(3/3*level) at levels 0..3.
    assertEquals(List.of(0, 3, 6, 9), levels(HitSpeed::oneSwordHitBonus));
    assertEquals(List.of(0, 3, 5, 8), levels(HitSpeed::ilkwangHitBonus));
    assertEquals(List.of(0, 1, 2, 3), levels(HitSpeed::yedoHitBonus));

    // 攻杀剑术 also writes m_nHitPlus and the cycle length, and does so outside the
    // `btLevel > 0` guard — a freshly read book is already armed.
    for (int level = 0; level <= 3; level++) {
      HitSpeed yedo = HitSpeed.of(LevelAbilities.JOB_WARRIOR,
          List.of(new PlayerSkill(HitSpeed.SKILL_YEDO, level, 0, 0)));
      assertEquals(HitSpeed.DEF_HIT + level, yedo.hitPlus());
      assertEquals(7 - level, yedo.attackSkillCycle());
      assertEquals(HitSpeed.DEF_HIT + level, yedo.hitPoint());
    }

    // The three stack additively into one 准确 total.
    HitSpeed stacked = HitSpeed.of(LevelAbilities.JOB_WARRIOR, List.of(
        new PlayerSkill(HitSpeed.SKILL_ONESWORD, 3, 0, 0),
        new PlayerSkill(HitSpeed.SKILL_YEDO, 2, 0, 0)));
    assertEquals(HitSpeed.DEF_HIT + 9 + 2, stacked.hitPoint());
    assertEquals(HitSpeed.DEF_HIT + 2, stacked.hitPlus());

    // MagicManager.IsWarrSkill (Magic.pas:211) — the nine ids DoSpell refuses outright.
    for (int id : new int[] {3, 4, 7, 12, 25, 26, 27, 34, 38}) {
      assertTrue(HitSpeed.isWarriorSkill(id), "IsWarrSkill must contain " + id);
    }
    for (int id : new int[] {1, 2, 5, 11, 31, 33, 39}) {
      assertFalse(HitSpeed.isWarriorSkill(id), "IsWarrSkill must not contain " + id);
    }
  }

  @Test
  void theLearnedWeaponSkillsReachTheClientThroughSmSubAbility() {
    UUID warriorId = UUID.randomUUID();
    UUID taoistId = UUID.randomUUID();
    RecordingStore store = new RecordingStore();
    store.save(new PlayerState(warriorId, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_ONESWORD, 3, 0, 0),
            new PlayerSkill(HitSpeed.SKILL_YEDO, 1, 0, 0))));
    store.save(new PlayerState(taoistId, levelAbility(LevelAbilities.JOB_TAOIST, 20),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_ILKWANG, 3, 0, 0))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> warriorEvents = new ArrayList<>();
      enter(world, warriorId, "剑士", 5, 5, LevelAbilities.JOB_WARRIOR, warriorEvents);
      WorldEvent.SubAbilityChanged warrior = one(warriorEvents, WorldEvent.SubAbilityChanged.class);
      // 5 + Round(9/3*3) + Round(3/3*1) = 5 + 9 + 1
      assertEquals(15, warrior.hitPoint());
      assertEquals(HitSpeed.DEF_SPEED, warrior.speedPoint());
      // No gear column feeds the other four accumulators yet, exactly as a naked TPlayObject.
      assertEquals(0, warrior.antiMagic());
      assertEquals(0, warrior.antiPoison());
      assertEquals(0, warrior.healthRecover());
      assertEquals(0, warrior.spellRecover());

      List<WorldEvent> taoistEvents = new ArrayList<>();
      enter(world, taoistId, "道姑", 8, 8, LevelAbilities.JOB_TAOIST, taoistEvents);
      WorldEvent.SubAbilityChanged taoist = one(taoistEvents, WorldEvent.SubAbilityChanged.class);
      assertEquals(HitSpeed.DEF_HIT + 8, taoist.hitPoint());
      assertEquals(HitSpeed.DEF_SPEED + 3, taoist.speedPoint());
    }
  }

  @Test
  void aDodgedSwingCostsThePowerAndStaysSilentWhileAZeroHitTargetIsNeverDodged() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(), Equipment.empty(), 0, 0, 0));

    // Random(bound) = bound - 1 for every stream: the dodge roll always returns the target's
    // 敏捷 - 1, which a DEFHIT = 5 character loses against any monster whose SPEED exceeds 6.
    try (WorldEngine world = engine(store, new MaxRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "背运", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      run(world, world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();

      now.addAndGet(5_000);
      AttackResult dodged = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(dodged.accepted());
      assertEquals(0, dodged.damage(), "Random(15) = 14 > DEFHIT 5, so the orc dodges");
      // ObjBase.pas:22258 — StruckDamage and RM_STRUCK are both inside `if nPower > 0`.
      assertTrue(events.stream().noneMatch(WorldEvent.ObjectStruck.class::isInstance),
          "a dodged blow must not reach the client as a zero-damage SM_STRUCK");
      assertTrue(events.stream().anyMatch(WorldEvent.AttackAccepted.class::isInstance));
    }

    // 练功师/木桩 carries HIT = 0 in Monster.DB, and `if AttackTarget.m_btHitPoint > 0`
    // skips the roll entirely — the damage bench stays deterministic.
    assertEquals(0, MonsterTemplate.trainer().hitPoint());
    assertEquals(0, MonsterTemplate.trainer().speedPoint());
    assertEquals(6, MonsterTemplate.orc().hitPoint());
    assertEquals(15, MonsterTemplate.orc().speedPoint());
    try (WorldEngine world = engine(store, new MaxRandom())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "背运", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      now.addAndGet(5_000);
      AttackResult landed = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(landed.damage() > 0, "a HIT = 0 target is never dodged");
    }
  }

  @Test
  void readingThePowerHitBookArmsTheCadenceAndTheNextPowerHitCarriesHitPlus() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    StdItem book = StdItemsDb.byName("攻杀剑术").orElseThrow();
    StdItem sword = StdItemsDb.byName("木剑").orElseThrow();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(new BackpackItem(book, 4001, 0, 0), new BackpackItem(sword, 4002, 4000, 4000)),
        Equipment.empty(), 0, 0, 0));

    // Every draw collapses to the range minimum: Random(m_btAttackSkillCount) = 0 pins the
    // armed swing to the end of the cycle, and the damage roll becomes a constant.
    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "刀客", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 4002, "木剑")));
      run(world, world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      // RM_HIT/RM_SPELL2 are suppressed towards the swinging actor itself (ObjBase.pas:18857),
      // so the broadcast ident has to be read off a bystander.
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 7), Direction.UP, observed::add));

      // ReadBook calls RecalcAbilitys (ObjBase.pas:23466), so the cycle is live immediately.
      assertTrue(run(world, world.useItem(player.id(), 4001, "攻杀剑术")));
      assertEquals(List.of(HitSpeed.SKILL_YEDO),
          run(world, world.skills(player.id())).stream().map(PlayerSkill::magicId).toList());
      events.clear();

      // m_btAttackSkillCount starts at 7 - 0 and is decremented once per swing; the armed
      // swing is the one where it reaches m_btAttackSkillPointCount (0 under FixedRandom).
      int plain = 0;
      for (int swing = 1; swing <= 7; swing++) {
        now.addAndGet(1_000);
        AttackResult result = run(world,
            world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
        plain = result.damage();
        assertTrue(plain > 0, "the dummy is never dodged");
        long armed = events.stream().filter(WorldEvent.PowerHitReady.class::isInstance).count();
        assertEquals(swing == 7 ? 1 : 0, armed, "+PWR must arrive exactly on the 7th swing");
      }

      // The armed swing spends m_boPowerHit and adds m_nHitPlus = DEFHIT + btLevel = 5.
      observed.clear();
      now.addAndGet(1_000);
      AttackResult power = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.POWER_HIT));
      assertEquals(plain + HitSpeed.DEF_HIT, power.damage());
      assertEquals(AttackKind.POWER_HIT, broadcast(observed).attack(),
          "AttackDir answers RM_SPELL2 only while the flag is armed");

      // A second CM_POWERHIT with nothing armed is an ordinary swing broadcast as RM_HIT.
      observed.clear();
      now.addAndGet(1_000);
      AttackResult unarmed = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.POWER_HIT));
      assertEquals(plain, unarmed.damage());
      assertEquals(AttackKind.HIT, broadcast(observed).attack());
    }
  }

  @Test
  void castingAWeaponSkillIsAcknowledgedWithoutManaCooldownOrTarget() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, 20),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_ONESWORD, 1, 0, 0),
            new PlayerSkill(HitSpeed.SKILL_YEDO, 0, 0, 0),
            new PlayerSkill(12, 0, 0, 0))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, id, "剑客", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      int mana = run(world, world.snapshot(player.id())).ability().mp();
      events.clear();

      // ClientSpellXY's warrior branch is a bare `Result := True`: no target, no range check.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_ONESWORD,
          player.position(), 0)));
      // IsWarrSkill also short-circuits the m_dwMagicAttackInterval gate, so the very next
      // press on the same tick is accepted too.
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_YEDO,
          player.position(), 0)));
      assertEquals(mana, run(world, world.snapshot(player.id())).ability().mp(),
          "a passive weapon skill never spends mana");
      assertEquals(2, events.stream().filter(WorldEvent.SpellAccepted.class::isInstance).count());
      assertTrue(events.stream().noneMatch(WorldEvent.MagicFired.class::isInstance),
          "DoSpell exits before RM_SPELL/RM_MAGICFIRE for IsWarrSkill ids");

      // 刺杀剑术 is in the same IsWarrSkill set but has no implementation in this batch: it
      // must fail loudly instead of silently answering +GOOD.
      events.clear();
      assertFalse(run(world, world.castSpell(player.id(), 12, player.position(), 0)));
      WorldEvent.SpellRejected rejected = one(events, WorldEvent.SpellRejected.class);
      assertEquals(WorldEvent.SpellRejection.UNSUPPORTED_SKILL, rejected.reason());
      assertEquals("该技能尚未开放", rejected.message());
    }
  }

  // ------------------------------------------------------------------ helpers

  private static List<Integer> levels(java.util.function.IntUnaryOperator bonus) {
    List<Integer> values = new ArrayList<>();
    for (int level = 0; level <= 3; level++) values.add(bonus.applyAsInt(level));
    return values;
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

  /** {@code nextInt(bound)} always answers {@code bound - 1} — the worst possible dodge roll. */
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
