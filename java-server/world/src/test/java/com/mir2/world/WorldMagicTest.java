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

/** W28 minimum acceptance slice: book, fireball, healing and transient magic-shield lifecycle. */
class WorldMagicTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void skillBookLearnsPersistsAndIsSentAgainOnLogin() {
    RecordingStore store = new RecordingStore();
    UUID characterId = UUID.randomUUID();
    StdItem book = StdItemsDb.byName("火球术").orElseThrow();
    Ability levelSeven = levelAbility(LevelAbilities.JOB_WIZARD, 7);
    store.save(new PlayerState(characterId, levelSeven,
        List.of(new BackpackItem(book, 7001, 0, 0)), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = enter(world, characterId, "法师", 7, 7,
          LevelAbilities.JOB_WIZARD, events);
      events.clear();

      assertTrue(run(world, world.useItem(player.id(), 7001, "火球术")));
      assertEquals(List.of(1), run(world, world.skills(player.id())).stream()
          .map(PlayerSkill::magicId).toList());
      assertTrue(run(world, world.playerState(player.id())).backpack().isEmpty());
      assertEquals(1, events.stream().filter(WorldEvent.SkillLearned.class::isInstance).count());
      run(world, world.leavePlayer(player.id()));
    }

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      enter(world, characterId, "法师", 7, 7, LevelAbilities.JOB_WIZARD, events);
      WorldEvent.SkillsSent sent = one(events, WorldEvent.SkillsSent.class);
      assertEquals(1, sent.magics().size());
      assertEquals("火球术", sent.magics().getFirst().definition().name());
    }
  }

  @Test
  void bookFailureDoesNotConsumeItemForWrongJobOrLowLevel() {
    RecordingStore store = new RecordingStore();
    UUID characterId = UUID.randomUUID();
    StdItem book = StdItemsDb.byName("火球术").orElseThrow();
    store.save(new PlayerState(characterId, Ability.defaultPlayer(),
        List.of(new BackpackItem(book, 8, 0, 0)), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot warrior = enter(world, characterId, "战士", 5, 5,
          LevelAbilities.JOB_WARRIOR, events);
      events.clear();
      assertFalse(run(world, world.useItem(warrior.id(), 8, "火球术")));
      assertEquals(1, run(world, world.playerState(warrior.id())).backpack().size());
      assertTrue(run(world, world.skills(warrior.id())).isEmpty());
      assertEquals(1, events.stream().filter(WorldEvent.UseItemRejected.class::isInstance).count());
    }
  }

  @Test
  void fireballConsumesManaHonoursDelayAndUsesTheNormalDamageChain() {
    RecordingStore store = new RecordingStore();
    UUID characterId = UUID.randomUUID();
    store.save(state(characterId, LevelAbilities.JOB_WIZARD, 7, 1));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, characterId, "法师", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      MonsterTemplate dummy = new MonsterTemplate("木桩", 0, Ability.monster(100, 0, 0, 0, 0),
          1, 1_000_000, 1_000_000, 0, List.of());
      WorldObjectSnapshot target = run(world,
          world.spawnMonster(dummy, "0", new Position(7, 5), Direction.LEFT));
      int targetHp = target.ability().hp();
      int mana = wizard.ability().mp();
      events.clear();

      assertTrue(run(world, world.castSpell(wizard.id(), 1, target.position(), target.id())));
      assertEquals(mana - 2, run(world, world.snapshot(wizard.id())).ability().mp());
      assertEquals(targetHp, run(world, world.snapshot(target.id())).ability().hp(),
          "fireball is a delayed hit, not immediate socket-side mutation");
      assertTrue(events.stream().anyMatch(WorldEvent.MagicFired.class::isInstance));

      now.addAndGet(599);
      world.tickOnce();
      assertEquals(targetHp, run(world, world.snapshot(target.id())).ability().hp());
      now.incrementAndGet();
      world.tickOnce();
      assertTrue(run(world, world.snapshot(target.id())).ability().hp() < targetHp);
      assertTrue(events.stream().anyMatch(WorldEvent.ObjectStruck.class::isInstance));
      assertTrue(events.stream().anyMatch(WorldEvent.HealthChanged.class::isInstance));
    }
  }

  @Test
  void healingTargetsAnotherPlayerAndClampsAtMaximumHealth() {
    RecordingStore store = new RecordingStore();
    UUID healerId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    store.save(state(healerId, LevelAbilities.JOB_TAOIST, 7, 2));
    Ability patientAbility = levelAbility(LevelAbilities.JOB_WARRIOR, 7);
    store.save(new PlayerState(patientId, patientAbility.withHp(patientAbility.maxHp() - 1),
        List.of(), Equipment.empty(), 0, 0, 0));

    try (WorldEngine world = engine(store)) {
      List<WorldEvent> healerEvents = new ArrayList<>();
      WorldObjectSnapshot healer = enter(world, healerId, "道士", 5, 5,
          LevelAbilities.JOB_TAOIST, healerEvents);
      WorldObjectSnapshot patient = enter(world, patientId, "伤员", 6, 5,
          LevelAbilities.JOB_WARRIOR, new ArrayList<>());

      assertTrue(run(world, world.castSpell(healer.id(), 2, patient.position(), patient.id())));
      now.addAndGet(800);
      world.tickOnce();
      assertEquals(patientAbility.maxHp(), run(world, world.snapshot(patient.id())).ability().hp());
      assertTrue(run(world, world.snapshot(healer.id())).ability().mp() < healer.ability().mp());
    }
  }

  @Test
  void failuresAreDeterministicAndDoNotSpendMana() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 7, 1));
    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "法师", 2, 2,
          LevelAbilities.JOB_WIZARD, events);
      int mana = wizard.ability().mp();
      events.clear();

      assertFalse(run(world, world.castSpell(wizard.id(), 1, new Position(15, 15), 9999)));
      WorldEvent.SpellRejected rejected = one(events, WorldEvent.SpellRejected.class);
      assertEquals(WorldEvent.SpellRejection.OUT_OF_RANGE, rejected.reason());
      assertEquals("施法距离过远", rejected.message());
      assertEquals(mana, run(world, world.snapshot(wizard.id())).ability().mp());
    }
  }

  @Test
  void magicShieldAppliesExpiresAndNeverSurvivesRelog() {
    RecordingStore store = new RecordingStore();
    UUID id = UUID.randomUUID();
    store.save(state(id, LevelAbilities.JOB_WIZARD, 31, 31));
    try (WorldEngine world = engine(store)) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot wizard = enter(world, id, "盾法", 5, 5,
          LevelAbilities.JOB_WIZARD, events);
      events.clear();
      assertTrue(run(world, world.castSpell(wizard.id(), 31, wizard.position(), wizard.id())));
      assertTrue(run(world, world.magicShieldActive(wizard.id())));
      assertFalse(run(world, world.castSpell(wizard.id(), 31, wizard.position(), wizard.id())),
          "an active shield cannot be stacked");
      assertEquals(WorldEvent.SpellRejection.BUFF_ALREADY_ACTIVE,
          one(events, WorldEvent.SpellRejected.class).reason());

      now.addAndGet(60_000);
      world.tickOnce();
      assertFalse(run(world, world.magicShieldActive(wizard.id())));
      assertTrue(events.stream().filter(WorldEvent.SystemMessage.class::isInstance)
          .map(WorldEvent.SystemMessage.class::cast)
          .anyMatch(message -> message.message().contains("已消失")));

      now.addAndGet(2_000);
      assertTrue(run(world, world.castSpell(wizard.id(), 31, wizard.position(), wizard.id())));
      assertTrue(run(world, world.magicShieldActive(wizard.id())));
      run(world, world.leavePlayer(wizard.id()));
    }

    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot wizard = enter(world, id, "盾法", 5, 5,
          LevelAbilities.JOB_WIZARD, new ArrayList<>());
      assertFalse(run(world, world.magicShieldActive(wizard.id())),
          "buff state is deliberately not part of PlayerState");
    }
  }

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
        new Random(20020522L), store, ItemDatabase.of(StdItemsDb.all()));
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
