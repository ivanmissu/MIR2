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
 * W37: {@code SKILL_FIRECHARM}(13, 灵魂火符) and {@code SKILL_AMYOUNSUL}(6, 施毒术) share one
 * 护身符/药粉 charge gate (Magic.pas:415-497). Unlike every earlier skill batch, a caster with no
 * charm equipped never gets the {@code RM_MAGICFIRE} projectile at all — {@code
 * WorldEvent.SpellFizzled} — even though mana was already spent.
 */
class WorldAmuletSkillTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void fireCharmFizzlesWithoutAnAmuletAndStillSpendsMana() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_TAOIST, 20, SKILL_FIRECHARM));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      MonsterTemplate dummy = new MonsterTemplate("木桩", 0, Ability.monster(1_000, 0, 0, 0, 0),
          1, 1_000_000, 1_000_000, 0, List.of());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT));
      int targetHp = target.ability().hp();
      int mana = taoist.ability().mp();
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_FIRECHARM,
          target.position(), target.id())), "mana is spent and +GOOD answered even on a fizzle");
      assertTrue(run(world, world.snapshot(taoist.id())).ability().mp() < mana);
      assertEquals(1, events.stream().filter(WorldEvent.SpellFizzled.class::isInstance).count());
      assertEquals(0, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());

      now.addAndGet(2_000);
      world.tickOnce();
      assertEquals(targetHp, run(world, world.snapshot(target.id())).ability().hp(),
          "no charm means no projectile, so the dummy never takes damage");
    }
  }

  @Test
  void fireCharmConsumesOneAmuletChargeAndDealsDelayedDamage() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    StdItem amuletTemplate = StdItemsDb.byName("护身符").orElseThrow();
    BackpackItem amulet = BackpackItem.of(amuletTemplate, 501);
    store.save(new PlayerState(id, levelAbility(LevelAbilities.JOB_TAOIST, 20), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT, amulet)), 0, 0, 0,
        List.of(PlayerSkill.learned(SKILL_FIRECHARM))));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, id, "道士", 5, 5, LevelAbilities.JOB_TAOIST, events);
      MonsterTemplate dummy = new MonsterTemplate("木桩", 0, Ability.monster(1_000, 0, 0, 0, 0),
          1, 1_000_000, 1_000_000, 0, List.of());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT));
      int targetHp = target.ability().hp();
      events.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_FIRECHARM,
          target.position(), target.id())));
      assertEquals(1, events.stream().filter(WorldEvent.MagicFired.class::isInstance).count());
      assertEquals(0, events.stream().filter(WorldEvent.SpellFizzled.class::isInstance).count());
      WorldEvent.ItemDurabilityChanged wear = events.stream()
          .filter(WorldEvent.ItemDurabilityChanged.class::isInstance)
          .map(WorldEvent.ItemDurabilityChanged.class::cast).findFirst().orElseThrow();
      assertEquals(amuletTemplate.duraMax() - 100, wear.dura(),
          "CheckAmulet/UseAmulet spends exactly one 100-unit charge");
      assertEquals(targetHp, run(world, world.snapshot(target.id())).ability().hp(),
          "the bolt is a delayed hit, not an immediate socket-side mutation");

      now.addAndGet(1_199);
      world.tickOnce();
      assertEquals(targetHp, run(world, world.snapshot(target.id())).ability().hp());
      now.addAndGet(1);
      world.tickOnce();
      assertTrue(run(world, world.snapshot(target.id())).ability().hp() < targetHp);
    }
  }

  @Test
  void amyounsulDecHealthPoisonsAPlayerTargetWithAMessageAndPeriodicDamage() {
    RecordingStore store = new RecordingStore();
    UUID casterId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    StdItem grayPowder = StdItemsDb.byName("灰色药粉(少量)").orElseThrow();
    store.save(new PlayerState(casterId, levelAbility(LevelAbilities.JOB_TAOIST, 30), List.of(),
        new Equipment(Map.of(EquipmentSlot.CHARM_AMULET, BackpackItem.of(grayPowder, 601))),
        0, 0, 0, List.of(PlayerSkill.learned(SKILL_AMYOUNSUL))));
    store.save(new PlayerState(targetId, levelAbility(LevelAbilities.JOB_WARRIOR, 30),
        List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> casterEvents = new ArrayList<>();
      List<WorldEvent> targetEvents = new ArrayList<>();
      WorldObjectSnapshot taoist = enter(world, casterId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, casterEvents);
      WorldObjectSnapshot warrior = enter(world, targetId, "战士", 6, 5,
          LevelAbilities.JOB_WARRIOR, targetEvents);
      int targetHp = warrior.ability().hp();
      casterEvents.clear();
      targetEvents.clear();

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_AMYOUNSUL,
          warrior.position(), warrior.id())));
      assertEquals(1, casterEvents.stream().filter(WorldEvent.MagicFired.class::isInstance).count());

      now.addAndGet(1_000);
      world.tickOnce();
      assertTrue(targetEvents.stream().filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast)
          .anyMatch(message -> message.message().contains("你中毒了")),
          "MakePosion's sYouPoisoned hint reaches the poisoned player, not the caster");
      assertFalse(casterEvents.stream().filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast)
          .anyMatch(message -> message.message().contains("你中毒了")));

      int hpBeforeTick = run(world, world.snapshot(warrior.id())).ability().hp();
      now.addAndGet(2_500);
      world.tickOnce();
      int hpAfterTick = run(world, world.snapshot(warrior.id())).ability().hp();
      assertTrue(hpAfterTick < hpBeforeTick,
          "the DECHEALTH shape ticks DamageHealth(point + 1) every 2.5 seconds");
      assertTrue(hpAfterTick < targetHp);
    }
  }

  @Test
  void amyounsulDamageArmorMultipliesEveryLandedHitBy1Point2x() {
    // SKILL_AMYOUNSUL (job=道士) and SKILL_FIREBALL (job=法师) can never be learned by the same
    // character, so the poison and the measuring blow come from two different casters here —
    // the multiplier itself lives on the victim (POISON_DAMAGEARMOR), not on either attacker.
    RecordingStore store = new RecordingStore();
    UUID taoistId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    StdItem yellowPowder = StdItemsDb.byName("黄色药粉(少量)").orElseThrow();
    store.save(new PlayerState(taoistId, levelAbility(LevelAbilities.JOB_TAOIST, 30), List.of(),
        new Equipment(Map.of(EquipmentSlot.CHARM_AMULET, BackpackItem.of(yellowPowder, 701))),
        0, 0, 0, List.of(PlayerSkill.learned(SKILL_AMYOUNSUL))));
    store.save(state(wizardId, LevelAbilities.JOB_WIZARD, 30, 1));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot taoist = enter(world, taoistId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      WorldObjectSnapshot wizard = enter(world, wizardId, "法师", 5, 6,
          LevelAbilities.JOB_WIZARD, new ArrayList<>());
      MonsterTemplate dummyTemplate = new MonsterTemplate("木桩", 0,
          Ability.monster(100_000, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      WorldObjectSnapshot poisoned = run(world,
          world.spawnMonster(dummyTemplate, "0", new Position(7, 5), Direction.LEFT));
      WorldObjectSnapshot healthy = run(world,
          world.spawnMonster(dummyTemplate, "0", new Position(3, 5), Direction.RIGHT));

      assertTrue(run(world, world.castSpell(taoist.id(), SKILL_AMYOUNSUL,
          poisoned.position(), poisoned.id())));
      now.addAndGet(1_000);
      world.tickOnce();

      int healthyHp = run(world, world.snapshot(healthy.id())).ability().hp();
      int poisonedHp = run(world, world.snapshot(poisoned.id())).ability().hp();

      assertTrue(run(world, world.castSpell(wizard.id(), 1, healthy.position(), healthy.id())));
      now.addAndGet(600);
      world.tickOnce();
      int healthyDamage = healthyHp - run(world, world.snapshot(healthy.id())).ability().hp();
      assertTrue(healthyDamage > 0);

      now.addAndGet(2_000);
      assertTrue(run(world, world.castSpell(wizard.id(), 1, poisoned.position(), poisoned.id())));
      now.addAndGet(600);
      world.tickOnce();
      int poisonedDamage = poisonedHp - run(world, world.snapshot(poisoned.id())).ability().hp();

      assertEquals(Math.rint(healthyDamage * 1.2), (double) poisonedDamage,
          "StruckDamage scales every landed hit by g_Config.nPosionDamagarmor / 10 while active");
    }
  }

  @Test
  void amyounsulReapplicationNeverShortensOrWeakensTheActivePoison() {
    RecordingStore store = new RecordingStore();
    UUID strongCasterId = UUID.randomUUID();
    UUID weakCasterId = UUID.randomUUID();
    StdItem grayPowder = StdItemsDb.byName("灰色药粉(少量)").orElseThrow();
    store.save(new PlayerState(strongCasterId, levelAbility(LevelAbilities.JOB_TAOIST, 30),
        List.of(), new Equipment(Map.of(EquipmentSlot.CHARM_AMULET,
            BackpackItem.of(grayPowder, 801))),
        0, 0, 0, List.of(new PlayerSkill(SKILL_AMYOUNSUL, 3, 0, 0))));
    store.save(new PlayerState(weakCasterId, levelAbility(LevelAbilities.JOB_TAOIST, 30),
        List.of(), new Equipment(Map.of(EquipmentSlot.CHARM_AMULET,
            BackpackItem.of(grayPowder, 802))),
        0, 0, 0, List.of(new PlayerSkill(SKILL_AMYOUNSUL, 0, 0, 0))));

    try (WorldEngine world = deterministicEngine(store)) {
      WorldObjectSnapshot strong = enter(world, strongCasterId, "强毒师", 5, 5,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      WorldObjectSnapshot weak = enter(world, weakCasterId, "弱毒师", 5, 6,
          LevelAbilities.JOB_TAOIST, new ArrayList<>());
      MonsterTemplate dummyTemplate = new MonsterTemplate("木桩", 0,
          Ability.monster(100_000, 0, 0, 0, 0), 1, 1_000_000, 1_000_000, 0, List.of());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(dummyTemplate, "0", new Position(6, 5), Direction.LEFT));

      assertTrue(run(world, world.castSpell(strong.id(), SKILL_AMYOUNSUL,
          target.position(), target.id())));
      now.addAndGet(1_000);
      world.tickOnce();

      int hpBeforeFirstTick = run(world, world.snapshot(target.id())).ability().hp();
      now.addAndGet(2_500);
      world.tickOnce();
      int hpAfterFirstTick = run(world, world.snapshot(target.id())).ability().hp();
      int firstTickDamage = hpBeforeFirstTick - hpAfterFirstTick;
      assertTrue(firstTickDamage > 0, "skill level 3 must land a real DECHEALTH poison");

      // A much weaker (level 0) reapplication lands well inside the level-3 poison's window —
      // its shorter MakePosion duration must lose the "keep the longer one" comparison and the
      // periodic damage must keep crediting the original, stronger caster.
      assertTrue(run(world, world.castSpell(weak.id(), SKILL_AMYOUNSUL,
          target.position(), target.id())));
      now.addAndGet(1_000);
      world.tickOnce();

      int hpBeforeSecondTick = run(world, world.snapshot(target.id())).ability().hp();
      now.addAndGet(2_500);
      world.tickOnce();
      int secondTickDamage = hpBeforeSecondTick - run(world, world.snapshot(target.id())).ability().hp();

      assertEquals(firstTickDamage, secondTickDamage,
          "MakePosion keeps the longer duration/point pair instead of overwriting it");
    }
  }

  private static final int SKILL_AMYOUNSUL = 6;
  private static final int SKILL_FIRECHARM = 13;

  private PlayerState state(UUID id, int job, int level, int magicId) {
    return new PlayerState(id, levelAbility(job, level), List.of(), Equipment.empty(), 0, 0, 0,
        List.of(PlayerSkill.learned(magicId)));
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
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 30, 30)), now::get,
        new Random(20260928L), store, ItemDatabase.of(StdItemsDb.all()));
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
