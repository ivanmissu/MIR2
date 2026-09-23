package com.mir2.world;

import java.util.Objects;
import java.util.Random;

/**
 * The world's source of randomness, split into independent named streams.
 *
 * <p>Delphi calls the single global {@code Random()} from every subsystem, so the whole
 * server shares one sequence: a loot roll consumes a draw that would otherwise have gone to
 * the next damage roll. That is invisible in production but fatal to 影子对拍 (shadow
 * comparison), because two server processes only stay in step while they draw in exactly the
 * same order — and monster AI, respawn timers and the HP/MP regeneration counters are all
 * driven by the wall clock, which two processes never share.
 *
 * <p>This class keeps both behaviours:
 *
 * <ul>
 *   <li>{@link #of(Random)} — the legacy adapter. Every stream draws from one shared
 *       generator, so the draw order (and therefore every existing deterministic test
 *       vector) is bit-for-bit what it was before the split.</li>
 *   <li>{@link #seeded(long)} — the comparison mode. Each stream gets its own generator
 *       derived from the world seed, so the number of loot rolls can no longer perturb the
 *       damage sequence. Two servers booted with the same {@code MIR2_WORLD_SEED} therefore
 *       produce the same <em>Nth damage roll</em>, the same <em>Nth drop decision</em> and so
 *       on, independently of how often the other subsystems happened to fire.</li>
 *   <li>{@link #unseeded()} — the production default: one shared, randomly seeded
 *       generator, i.e. exactly the Delphi arrangement.</li>
 * </ul>
 *
 * <p>Per-stream isolation is necessary but not sufficient for a fully deterministic world:
 * anything whose <em>number</em> of draws depends on wall-clock timing (a monster deciding to
 * step, a spawner refilling) still diverges between processes. The streams that the shadow
 * harness relies on — {@link Stream#DAMAGE}, {@link Stream#LOOT_DROP} — are driven by the
 * scripted player actions instead, which is what makes PvE 对拍 reproducible.
 */
public final class WorldRandom {

  /** One independent draw sequence per subsystem that consumes randomness. */
  public enum Stream {
    /** {@code rollDamage}: the attack and defence rolls of every blow. */
    DAMAGE,
    /** Weapon wear on a landed hit and the {@code StruckDamage} armour wear. */
    EQUIPMENT_WEAR,
    /** {@code DropItemDown}: the "one in N" monster loot decisions. */
    LOOT_DROP,
    /** {@code ScatterBagItems}: the 1/3 bag-drop decision taken on player death. */
    DEATH_SCATTER,
    /** {@code DropUseItems}: the 1/30 (1/15 while red) worn-gear drop decision on death. */
    DEATH_DROP_USE_ITEM,
    /** {@code RegenMonsters}: respawn cell and facing selection. */
    SPAWN
  }

  private static final Stream[] STREAMS = Stream.values();

  /**
   * Arbitrary odd 64-bit constants (SplitMix64's increment and a golden-ratio derivative)
   * used to fan one world seed out into per-stream seeds that share no low-order structure.
   */
  private static final long STREAM_GAMMA = 0x9E3779B97F4A7C15L;
  private static final long STREAM_MIX = 0xBF58476D1CE4E5B9L;

  private final Random[] generators;
  private final Long seed;

  private WorldRandom(Random[] generators, Long seed) {
    this.generators = generators;
    this.seed = seed;
  }

  /** Production default: one shared, randomly seeded generator, as in Delphi. */
  public static WorldRandom unseeded() {
    return of(new Random());
  }

  /**
   * Legacy adapter: all streams share {@code random}, preserving the exact draw order of the
   * pre-split engine. Existing deterministic tests keep their recorded values.
   */
  public static WorldRandom of(Random random) {
    Objects.requireNonNull(random, "random");
    Random[] generators = new Random[STREAMS.length];
    java.util.Arrays.fill(generators, random);
    return new WorldRandom(generators, null);
  }

  /**
   * Comparison mode: independent per-stream generators derived from one world seed. Two
   * engines built with the same seed agree draw-for-draw <em>within each stream</em>.
   */
  public static WorldRandom seeded(long seed) {
    Random[] generators = new Random[STREAMS.length];
    for (int index = 0; index < generators.length; index++) {
      generators[index] = new Random(streamSeed(seed, index));
    }
    return new WorldRandom(generators, seed);
  }

  /** The configured world seed, or empty when the engine runs unseeded. */
  public java.util.OptionalLong seed() {
    return seed == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(seed);
  }

  /** True when every stream is independent, i.e. the engine was built from a world seed. */
  public boolean isSeeded() {
    return seed != null;
  }

  /** {@code Random(bound)} on the given stream. */
  public int nextInt(Stream stream, int bound) {
    Objects.requireNonNull(stream, "stream");
    if (bound < 1) throw new IllegalArgumentException("bound must be positive: " + bound);
    return generators[stream.ordinal()].nextInt(bound);
  }

  /** Inclusive {@code min..max} draw; a degenerate range consumes no randomness, as in Delphi. */
  public int between(Stream stream, int min, int max) {
    if (min >= max) return min;
    return min + nextInt(stream, max - min + 1);
  }

  /** SplitMix64-style avalanche so adjacent stream indexes yield unrelated seeds. */
  private static long streamSeed(long seed, int streamIndex) {
    long z = seed + STREAM_GAMMA * (streamIndex + 1L);
    z = (z ^ (z >>> 30)) * STREAM_MIX;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
