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
 * W35 — 战士武器技第三批: 烈火剑法 ({@code SKILL_FIRESWORD} = 26, {@code wHitMode} 7).
 *
 * <p>烈火 is the third {@code IsWarrSkill} flavour after the passives (W33) and the toggles (W34):
 * the key press <em>arms a one-shot charge</em>. {@code ClientSpellXY} (ObjBase.pas:9092) runs
 * {@code AllowFireHitSkill} (10 s re-arm gate, ObjBase.pas:9782), pays {@code GetSpellPoint} mana
 * and answers a {@code '+FIR'} tag; {@code _Attack} (ObjBase.pas:22128) spends the flag on the
 * next front swing for {@code +m_nHitDouble * 10} percent damage, and {@code TPlayObject.Run}
 * (ObjBase.pas:6427) lets it lapse after 20 s with {@code '+UFIR'}.
 */
class WorldFireSwordSkillTest {
  private final AtomicLong now = new AtomicLong();

  // A level-50 warrior: DC := MakeLong(_MAX(50/5 - 1, 1), _MAX(1, 50/5)) = [9, 10]. Under the
  // FixedRandom bench every Random(bound) collapses to 0, so GetAttackPower always returns minDc.
  private static final int WARRIOR_LEVEL = 50;
  private static final int MIN_DC = 9;

  /** Magic.DB id 26: {@code wSpell = 0}, {@code btDefSpell = 7} → GetSpellPoint is a flat 7. */
  private static final int FIRE_SWORD_MANA = 7;

  @Test
  void hitDoubleFollowsTheBookLevel() {
    // m_nHitDouble := 4 + btLevel * 4, written outside any btLevel > 0 guard.
    assertEquals(4, HitSpeed.fireSwordHitDouble(0));
    assertEquals(8, HitSpeed.fireSwordHitDouble(1));
    assertEquals(16, HitSpeed.fireSwordHitDouble(3));
    assertEquals(4, HitSpeed.of(LevelAbilities.JOB_WARRIOR,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 0, 0, 0))).hitDouble());
    // 烈火剑法 contributes no 准确 at all — it is not one of the three RecalcHitSpeed hit tables.
    assertEquals(HitSpeed.DEF_HIT, HitSpeed.of(LevelAbilities.JOB_WARRIOR,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 3, 0, 0))).hitPoint());
    assertTrue(HitSpeed.isWarriorSkill(HitSpeed.SKILL_FIRESWORD));
    assertTrue(HitSpeed.isFireSwordSkill(HitSpeed.SKILL_FIRESWORD));
    assertFalse(HitSpeed.isToggledWeaponSkill(HitSpeed.SKILL_FIRESWORD));
    assertFalse(HitSpeed.isImplementedWarriorSkill(HitSpeed.SKILL_FIRESWORD));
  }

  @Test
  void pressingTheKeyArmsTheChargeSpendsManaAndEmitsTheTag() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "烈火", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      // Unlike 刺杀剑术, login never re-arms 烈火 — the charge always starts cold.
      assertTrue(events.stream().noneMatch(WorldEvent.WeaponSkillToggled.class::isInstance),
          "烈火剑法 must not be armed on login");
      int manaBefore = run(world, world.snapshot(player.id())).ability().mp();
      events.clear();

      now.addAndGet(20_000);
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD,
          player.position(), 0)));
      assertEquals("召唤烈火精灵成功...", one(events, WorldEvent.SystemMessage.class).message());
      WorldEvent.WeaponSkillToggled tag = one(events, WorldEvent.WeaponSkillToggled.class);
      assertEquals(HitSpeed.SKILL_FIRESWORD, tag.magicId());
      assertTrue(tag.on(), "+FIR tells the client to send CM_FIREHIT next");
      assertTrue(events.stream().anyMatch(WorldEvent.SpellAccepted.class::isInstance),
          "IsWarrSkill still answers +GOOD");
      assertEquals(FIRE_SWORD_MANA,
          manaBefore - run(world, world.snapshot(player.id())).ability().mp(),
          "GetSpellPoint(26) = wSpell 0 / 4 * (level+1) + btDefSpell 7");
      events.clear();

      // AllowFireHitSkill's gate is strict: a second press inside 10 s only gets the red hint,
      // no tag and no mana — but the call still succeeds (Result := True).
      now.addAndGet(10_000);
      int manaArmed = run(world, world.snapshot(player.id())).ability().mp();
      assertTrue(run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD,
          player.position(), 0)));
      assertEquals("召唤烈火精灵失败...", one(events, WorldEvent.SystemMessage.class).message());
      assertTrue(events.stream().noneMatch(WorldEvent.WeaponSkillToggled.class::isInstance),
          "a press during the re-arm window sends no tag");
      assertEquals(manaArmed, run(world, world.snapshot(player.id())).ability().mp(),
          "a refused arming costs nothing");
    }
  }

  @Test
  void anArmedSwingBurnsTheChargeForTheHitDoublePercentageAndTrains() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "烈火", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      // 练功师 (HIT = 0, AC = 0): never dodges, never absorbs — a deterministic damage bench.
      WorldObjectSnapshot front = run(world,
          world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 7), Direction.UP, observed::add));

      now.addAndGet(20_000);
      run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD, player.position(), 0));
      events.clear();
      observed.clear();

      now.addAndGet(5_000);
      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT));
      assertTrue(result.accepted());

      // nPower + Round(nPower / 100 * (m_nHitDouble * 10)) = 9 + Round(9/100*40) = 9 + 4 = 13.
      int expected = MIN_DC + (int) Math.rint(MIN_DC / 100.0 * (4 * 10));
      assertEquals(13, expected, "the W35 vector pins the level-0 烈火 blow to 13");
      assertEquals(expected, struck(events, front.id()).damage());
      assertEquals(AttackKind.FIRE_HIT, broadcast(observed).attack(),
          "an armed wHitMode 7 answers RM_FIREHIT");
      // TrainSkill(m_MagicFireSwordSkill, 1) on a penetrating swing (ObjBase.pas:22354).
      WorldEvent.SkillTrainingChanged trained = one(events, WorldEvent.SkillTrainingChanged.class);
      assertEquals(HitSpeed.SKILL_FIRESWORD, trained.magic().skill().magicId());
      assertEquals(1, trained.magic().skill().trainingPoints());
      events.clear();
      observed.clear();

      // The charge is one-shot: the very next CM_FIREHIT is an ordinary blow broadcast as RM_HIT.
      now.addAndGet(5_000);
      run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT));
      assertEquals(MIN_DC, struck(events, front.id()).damage(), "the burst is spent");
      assertEquals(AttackKind.HIT, broadcast(observed).attack(),
          "an unarmed CM_FIREHIT degrades to RM_HIT");
      // It still trains: ObjBase.pas:22354 guards on wHitMode, not on the armed flag.
      assertEquals(2, one(events, WorldEvent.SkillTrainingChanged.class)
          .magic().skill().trainingPoints());
    }
  }

  @Test
  void swingingAtAirStillBurnsTheCharge() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "空刀", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      now.addAndGet(20_000);
      run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD, player.position(), 0));

      // Jacky's 防止砍空刀刀烈火 guard (ObjBase.pas:22152): the empty-front branch clears the
      // flag too, so the charge cannot be parked on a whiff and cashed in later.
      now.addAndGet(5_000);
      assertTrue(run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT))
          .hitNothing());

      WorldObjectSnapshot front = run(world,
          world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      events.clear();
      now.addAndGet(5_000);
      run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT));
      assertEquals(MIN_DC, struck(events, front.id()).damage(),
          "the charge was already burned by the swing at air");
    }
  }

  @Test
  void theChargeLapsesAfterTwentySeconds() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0,
        List.of(new PlayerSkill(HitSpeed.SKILL_FIRESWORD, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "过期", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      WorldObjectSnapshot front = run(world,
          world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      now.addAndGet(20_000);
      run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD, player.position(), 0));
      events.clear();

      // 20 s exactly is still armed (`> 20 * 1000` is strict); one more tick past it lapses.
      now.addAndGet(20_000);
      world.tickOnce();
      assertTrue(events.stream().noneMatch(WorldEvent.WeaponSkillToggled.class::isInstance));
      now.addAndGet(1);
      world.tickOnce();
      WorldEvent.WeaponSkillToggled off = one(events, WorldEvent.WeaponSkillToggled.class);
      assertEquals(HitSpeed.SKILL_FIRESWORD, off.magicId());
      assertFalse(off.on(), "+UFIR tells the client to stop sending CM_FIREHIT");
      assertEquals("召唤烈火精灵结束...", one(events, WorldEvent.SystemMessage.class).message());
      events.clear();

      // And the lapsed charge really is gone from the damage path.
      run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT));
      assertEquals(MIN_DC, struck(events, front.id()).damage());
    }
  }

  @Test
  void anUnlearnedFireHitIsAnOrdinarySwing() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_WARRIOR, WARRIOR_LEVEL),
        List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = deterministicEngine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player =
          enter(world, id, "无技", 5, 5, LevelAbilities.JOB_WARRIOR, events);
      WorldObjectSnapshot front = run(world,
          world.spawnMonster(MonsterTemplate.trainer(), "0", new Position(6, 5), Direction.LEFT));
      List<WorldEvent> observed = new ArrayList<>();
      run(world, world.enterPlayer("围观", "0", new Position(5, 7), Direction.UP, observed::add));
      events.clear();

      // Without the book ClientSpellXY never reaches AllowFireHitSkill: the cast is refused.
      now.addAndGet(20_000);
      assertFalse(run(world, world.castSpell(player.id(), HitSpeed.SKILL_FIRESWORD,
          player.position(), 0)));
      assertEquals(WorldEvent.SpellRejection.UNKNOWN_SKILL,
          one(events, WorldEvent.SpellRejected.class).reason());
      events.clear();
      observed.clear();

      now.addAndGet(5_000);
      run(world, world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.FIRE_HIT));
      assertEquals(MIN_DC, struck(events, front.id()).damage());
      assertEquals(AttackKind.HIT, broadcast(observed).attack());
      assertTrue(events.stream().noneMatch(WorldEvent.SkillTrainingChanged.class::isInstance),
          "no training frame for a skill the character never learned");
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

  private WorldEngine deterministicEngine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new FixedRandom(), store, ItemDatabase.of(StdItemsDb.all()));
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
