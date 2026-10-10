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
    SPAWN,
    /** {@code MakeWeaponUnlock}: the 1-in-5 murder weapon-curse roll (ObjBase.pas:20950). */
    WEAPON_UNLOCK,
    /** Magic.DB power, MC/SC and target-MAC rolls; appended so old seeded streams do not move. */
    MAGIC,
    /**
     * {@code _Attack}'s {@code Random(AttackTarget.m_btSpeedPoint)} dodge roll — one draw per
     * melee blow that reaches a proper target (ObjBase.pas:22241).
     */
    ACCURACY,
    /**
     * {@code m_btAttackSkillPointCount := Random(m_btAttackSkillCount)}: which swing of the
     * 攻杀剑术 cycle arms the next power hit (ObjBase.pas:18613 and 8872).
     */
    POWER_HIT,
    /** {@code TrainSkill}: the classic {@code Random(3) + 1} weapon-skill gain. */
    SKILL_TRAIN,
    /**
     * {@code Random(TargeTBaseObject.m_btAntiPoison + 7) &lt;= 6} (Magic.pas:333/345): the 施毒术
     * resist gate. The bound now comes from the victim's RecalcAbilitys anti-poison accumulator;
     * the stream stays isolated so resistance gear does not perturb ordinary magic power rolls.
     */
    POISON_RESIST,
    /**
     * {@code Random(10) >= BaseObject.m_nAntiMagic}: the magic-resist roll used by single-target
     * hostile bolts and {@code MagPassThroughMagic}. The target's anti-magic accumulator supplies
     * the threshold; keeping it on its own stream preserves existing damage-roll determinism.
     */
    MAGIC_RESIST,
    /** {@code RM_MAGSTRUCK}: low-level animal targets pause walking for 800 + Random(1000) ms. */
    MAGIC_STAGGER,
    /**
     * {@code MagPushArround}'s {@code Random(20) < 6 + nPushLevel * 3 + levelgap} push gate
     * (Magic.pas:157). Appended after {@link #MAGIC_STAGGER} so no existing seeded stream
     * changes its ordinal.
     */
    PUSH_GATE,
    /** {@code MagPushArround}'s {@code Random(2)} extra push distance (Magic.pas:160). */
    PUSH_DISTANCE,
    /**
     * {@code MagMakePrivateTransparent}'s adjacent-monster {@code Random(2) = 0}
     * (Magic.pas:750): whether a monster standing within one cell of the cloaked player drops
     * its target. Appended after {@link #PUSH_DISTANCE} so no existing seeded stream moves.
     */
    CLOAK_AGGRO,
    /**
     * {@code if Random(100) < Cert.m_btCoolEye then Cert.m_boCoolEye := True}
     * (UsrEngn.pas:1950): the per-spawn roll that decides whether a monster can see through
     * 隐身术. Monsters whose Monster.DB {@code CoolEye} column is zero never draw here, so
     * every existing deterministic spawn vector keeps its draws.
     */
    COOL_EYE,
    /**
     * 圣言术 ({@code MagTurnUndead}, Magic.pas:912/915): the {@code Random(2) + (casterLevel - 1)
     * > targetLevel} level gate followed by the {@code Random(100) < 7 * skillLevel + 15 +
     * levelGap} instant-kill roll. Both draws live on one stream because they are issued back to
     * back inside a single skill branch; appending keeps every pre-W48 seeded stream unmoved.
     */
    TURN_UNDEAD,
    /**
     * {@code TAnimalObject.Struck}'s {@code Random(6) = 0} retarget coin (ObjBase.pas:2798): when
     * the struck monster already hunts a target that is *not* inside its 3x3 neighbourhood, it
     * only switches to the new attacker one time in six. W48 is the first caller of the shared
     * struck path (melee aggro is modelled by {@code acquireTarget} instead), so the stream has
     * no pre-existing consumers.
     */
    STRUCK_RETARGET,
    /**
     * 心灵启示's {@code Random(6) <= btLevel + 3} reveal gate (Magic.pas:524): skill levels 0..3
     * pass at 4/6, 5/6, 6/6 and 6/6. Kept apart from {@link #MAGIC} so a failed reveal cannot
     * perturb the SC-range draw that follows it on success.
     */
    SHOW_HP,
    /**
     * 瞬息移动 ({@code MagSaceMove} + {@code MapRandomMove} + {@code SpaceMove.GetRandXY},
     * Magic.pas:957 / ObjBase.pas:9826 / ObjBase.pas:4370-4381): the {@code Random(11) < btLevel
     * * 2 + 4} success gate, the two {@code Random(w - edge - 1)} start-cell draws and the
     * {@code Random(wWidth)}/{@code Random(wHeight)} wraps of the 201-step walkable search. One
     * stream for the whole branch because the draws are issued back to back inside a single
     * cast; appending keeps every pre-W50 seeded stream unmoved.
     */
    SPACE_MOVE
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
