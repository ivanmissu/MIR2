package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The world-level contract that makes PvE 影子对拍 possible: two engines booted with the same
 * {@code MIR2_WORLD_SEED} must produce the same damage sequence against the same target, and
 * the stationary trainer dummy must stay put so nothing depends on the wall clock.
 */
class WorldSeedDeterminismTest {

  @Test
  void twoSeededWorldsProduceTheIdenticalDamageSequence() {
    List<Integer> left = damageSequenceOfSixBlows(WorldRandom.seeded(20260922));
    List<Integer> right = damageSequenceOfSixBlows(WorldRandom.seeded(20260922));

    assertEquals(left, right, "same seed must reproduce every blow");
    assertFalse(left.stream().allMatch(damage -> damage == 0),
        "the fixture must actually land damage, otherwise the comparison is vacuous");
  }

  @Test
  void differentSeedsDivergeSoTheComparisonIsNotVacuous() {
    // Guards against a green-by-construction harness: if every seed produced the same
    // numbers, the shadow comparison would pass no matter how broken the server was.
    List<Integer> left = damageSequenceOfSixBlows(WorldRandom.seeded(1));
    List<Integer> right = damageSequenceOfSixBlows(WorldRandom.seeded(999_983));
    assertNotEquals(left, right);
  }

  @Test
  void lootDrawsDoNotPerturbTheDamageStream() {
    // The reason the streams are split: on a live server the number of loot rolls varies
    // with what happens to die, and that must not shift the next damage roll.
    List<Integer> withoutLoot = damageSequenceOfSixBlows(WorldRandom.seeded(4242));

    WorldRandom noisy = WorldRandom.seeded(4242);
    // Burn a realistic amount of unrelated randomness before and during the fight.
    for (int index = 0; index < 37; index++) {
      noisy.nextInt(WorldRandom.Stream.LOOT_DROP, 13);
      noisy.nextInt(WorldRandom.Stream.SPAWN, 8);
      noisy.nextInt(WorldRandom.Stream.DEATH_SCATTER, 3);
    }
    List<Integer> withLoot = damageSequenceOfSixBlows(noisy);

    assertEquals(withoutLoot, withLoot);
  }

  @Test
  void trainerDummyNeitherMovesNorRetaliates() {
    AtomicLong now = new AtomicLong(1_000_000);
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(now, WorldRandom.seeded(7))) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(
          "战士", "0", new Position(20, 20), Direction.RIGHT, events::add));
      WorldObjectSnapshot dummy = run(world, world.spawnMonster(
          MonsterTemplate.trainer(), "0", new Position(21, 20), Direction.LEFT));
      Position spawnCell = dummy.position();
      events.clear();

      // Idle for far longer than any walk or attack interval.
      for (int tick = 0; tick < 40; tick++) {
        now.addAndGet(500);
        world.tickOnce();
      }

      assertEquals(spawnCell, run(world, world.snapshot(dummy.id())).position(),
          "a m_boNoAttackMode dummy never takes a step");
      assertEquals(Ability.defaultPlayer().hp(),
          run(world, world.snapshot(player.id())).ability().hp(),
          "a stationary dummy never attacks");
      assertFalse(events.stream().anyMatch(event -> event instanceof WorldEvent.ObjectMoved),
          "no movement may be broadcast for a stationary dummy");
    }
  }

  @Test
  void trainerDummyStillAbsorbsDamageAndDies() {
    AtomicLong now = new AtomicLong(1_000_000);
    List<WorldEvent> events = new ArrayList<>();
    // A one-HP dummy dies to the first landed blow, proving the object is a real target
    // rather than something combat skips over.
    MonsterTemplate fragile = new MonsterTemplate("木桩", MonsterTemplate.packFeature(55, 0, 0),
        Ability.monster(1, 0, 0, 0, 0), 1, 1_000, 1_000, 0,
        MonsterBehavior.STATIONARY, List.of());
    try (WorldEngine world = engine(now, WorldRandom.seeded(7))) {
      run(world, world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT, events::add));
      WorldObjectSnapshot dummy = run(world,
          world.spawnMonster(fragile, "0", new Position(21, 20), Direction.LEFT));
      events.clear();

      AttackResult result = run(world, world.attack(
          firstPlayerId(world), new Position(20, 20), Direction.RIGHT, AttackKind.HIT));

      assertTrue(result.accepted() && !result.hitNothing(),
          "the blow must land on the adjacent dummy");
      assertTrue(events.stream().anyMatch(event ->
              event instanceof WorldEvent.ObjectStruck struck
                  && struck.victim().id() == dummy.id()),
          "the dummy must broadcast SM_STRUCK");
      assertFalse(run(world, world.snapshot(dummy.id())).ability().alive());
    }
  }

  // ------------------------------------------------------------ fixture

  /**
   * Six blows from a fixed-stat attacker into a dummy with a fixed AC range, on a world
   * whose only source of variation is the injected {@link WorldRandom}.
   */
  private static List<Integer> damageSequenceOfSixBlows(WorldRandom random) {
    AtomicLong now = new AtomicLong(1_000_000);
    List<Integer> damage = new ArrayList<>();
    try (WorldEngine world = engine(now, random)) {
      run(world, world.enterPlayer("战士", "0", new Position(20, 20), Direction.RIGHT,
          event -> {}));
      int playerId = firstPlayerId(world);
      // A wide DC spread makes every roll observable; a large HP pool keeps it alive.
      run(world, world.setLevel(playerId, 40));
      MonsterTemplate dummy = new MonsterTemplate("木桩", MonsterTemplate.packFeature(55, 0, 0),
          Ability.monster(100_000, 0, 0, 0, 0), 1, 1_000, 1_000, 0,
          MonsterBehavior.STATIONARY, List.of());
      run(world, world.spawnMonster(dummy, "0", new Position(21, 20), Direction.LEFT));

      for (int blow = 0; blow < 6; blow++) {
        now.addAndGet(1_000); // clear the 900ms CM_HIT interval
        AttackResult result = run(world,
            world.attack(playerId, new Position(20, 20), Direction.RIGHT, AttackKind.HIT));
        damage.add(result.damage());
      }
    }
    return damage;
  }

  private static WorldEngine engine(AtomicLong now, WorldRandom random) {
    WorldEngine.Config config = new WorldEngine.Config(
        Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000, 200, 600_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 40, 40)),
        now::get, random, PlayerStateStore.none(), ItemDatabase.empty());
  }

  private static int firstPlayerId(WorldEngine world) {
    return run(world, world.snapshot(1)).id();
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
