package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The split-stream randomness policy behind {@code MIR2_WORLD_SEED}. */
class WorldRandomTest {

  @Test
  void legacyAdapterPreservesTheSharedDrawOrder() {
    // The pre-split engine drew every value from one generator regardless of subsystem.
    // The adapter must reproduce that sequence exactly, otherwise every recorded
    // deterministic test vector in the suite would shift.
    Random expected = new Random(20020522L);
    List<Integer> reference = new ArrayList<>();
    for (int index = 0; index < 8; index++) reference.add(expected.nextInt(100));

    WorldRandom random = WorldRandom.of(new Random(20020522L));
    List<Integer> actual = List.of(
        random.nextInt(WorldRandom.Stream.DAMAGE, 100),
        random.nextInt(WorldRandom.Stream.LOOT_DROP, 100),
        random.nextInt(WorldRandom.Stream.DAMAGE, 100),
        random.nextInt(WorldRandom.Stream.SPAWN, 100),
        random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 100),
        random.nextInt(WorldRandom.Stream.DEATH_SCATTER, 100),
        random.nextInt(WorldRandom.Stream.DAMAGE, 100),
        random.nextInt(WorldRandom.Stream.LOOT_DROP, 100));

    assertEquals(reference, actual, "the legacy adapter must keep one shared sequence");
    assertFalse(random.isSeeded());
    assertTrue(random.seed().isEmpty());
  }

  @Test
  void seededStreamsAreIndependentOfEachOther() {
    // The point of the split: draws on one stream must not shift another stream's sequence.
    // Two worlds whose loot rolled a different number of times still agree on damage.
    WorldRandom quiet = WorldRandom.seeded(42);
    WorldRandom busy = WorldRandom.seeded(42);

    List<Integer> quietDamage = new ArrayList<>();
    List<Integer> busyDamage = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      quietDamage.add(quiet.nextInt(WorldRandom.Stream.DAMAGE, 1000));
      // The busy world also rolls loot and spawns between the very same blows.
      busy.nextInt(WorldRandom.Stream.LOOT_DROP, 7);
      busy.nextInt(WorldRandom.Stream.SPAWN, 8);
      busyDamage.add(busy.nextInt(WorldRandom.Stream.DAMAGE, 1000));
    }

    assertEquals(quietDamage, busyDamage,
        "unrelated subsystem draws must not perturb the damage stream");
  }

  @Test
  void sameSeedReproducesEveryStreamAndDifferentSeedsDiverge() {
    WorldRandom left = WorldRandom.seeded(20260922);
    WorldRandom right = WorldRandom.seeded(20260922);
    WorldRandom other = WorldRandom.seeded(20260923);

    for (WorldRandom.Stream stream : WorldRandom.Stream.values()) {
      assertEquals(left.nextInt(stream, 10_000), right.nextInt(stream, 10_000),
          "same seed must reproduce stream " + stream);
    }
    assertEquals(20260922L, left.seed().orElseThrow());
    assertTrue(left.isSeeded());

    // Different seeds must not collapse onto the same sequence.
    List<Integer> a = new ArrayList<>();
    List<Integer> b = new ArrayList<>();
    WorldRandom fresh = WorldRandom.seeded(20260922);
    for (int index = 0; index < 12; index++) {
      a.add(fresh.nextInt(WorldRandom.Stream.DAMAGE, 1_000_000));
      b.add(other.nextInt(WorldRandom.Stream.DAMAGE, 1_000_000));
    }
    assertNotEquals(a, b);
  }

  @Test
  void streamsOfOneSeedDoNotShareASequence() {
    // A naive implementation (seed + ordinal) leaves adjacent streams visibly correlated.
    WorldRandom random = WorldRandom.seeded(1);
    List<Integer> damage = new ArrayList<>();
    List<Integer> loot = new ArrayList<>();
    for (int index = 0; index < 16; index++) {
      damage.add(random.nextInt(WorldRandom.Stream.DAMAGE, 1_000_000));
      loot.add(random.nextInt(WorldRandom.Stream.LOOT_DROP, 1_000_000));
    }
    assertNotEquals(damage, loot);
  }

  @Test
  void betweenIsInclusiveAndConsumesNothingOnADegenerateRange() {
    WorldRandom random = WorldRandom.seeded(7);
    // Delphi's damage roll skips Random() entirely when min >= max; a consumed draw here
    // would desynchronise two servers whose gear differs only in a fixed stat.
    assertEquals(5, random.between(WorldRandom.Stream.DAMAGE, 5, 5));
    assertEquals(9, random.between(WorldRandom.Stream.DAMAGE, 9, 3));

    WorldRandom reference = WorldRandom.seeded(7);
    assertEquals(reference.nextInt(WorldRandom.Stream.DAMAGE, 4),
        random.between(WorldRandom.Stream.DAMAGE, 0, 3),
        "a degenerate range must not have consumed a draw");

    for (int index = 0; index < 200; index++) {
      int value = random.between(WorldRandom.Stream.DAMAGE, 3, 6);
      assertTrue(value >= 3 && value <= 6, "out of range: " + value);
    }
  }

  @Test
  void invalidBoundsAreRejected() {
    WorldRandom random = WorldRandom.seeded(1);
    assertThrows(IllegalArgumentException.class,
        () -> random.nextInt(WorldRandom.Stream.DAMAGE, 0));
    assertThrows(NullPointerException.class, () -> random.nextInt(null, 4));
    assertThrows(NullPointerException.class, () -> WorldRandom.of(null));
  }
}
