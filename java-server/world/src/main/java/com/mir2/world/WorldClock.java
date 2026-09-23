package com.mir2.world;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The world's source of time, in the three shapes the project needs.
 *
 * <p>Delphi reads {@code GetTickCount} — the OS uptime in milliseconds — from every
 * subsystem that has a cadence: {@code TMonster.Run} (walk/attack intervals),
 * {@code RegenMonsters}, {@code TBaseObject.Run} (HP/MP regeneration, PK decay, corpse
 * {@code MakeGhost}), {@code ProcessMapDoor} and {@code SaveHumanRcd}. Two server processes
 * never share that counter, which is exactly why 影子对拍 could only ever compare a
 * <em>stationary</em> dummy: anything whose <em>number</em> of AI decisions depends on the
 * wall clock diverges between processes even when {@link WorldRandom} keeps the draws in
 * step.
 *
 * <p>The three modes:
 *
 * <ul>
 *   <li>{@link #system()} — production. Reads {@code System.currentTimeMillis()}, i.e.
 *       exactly what the engine did before this class existed. Unchanged semantics, and
 *       the only mode a live server should use.</li>
 *   <li>{@link #virtual(long, long)} — <b>tick-derived time</b>. The clock is
 *       {@code epoch + tickCount × tickMillis}: it advances by exactly one tick interval per
 *       {@code tickOnce()}, never in between. Real time still drives <em>when</em> ticks
 *       happen (the engine keeps its fixed-rate scheduler), but every timestamp the world
 *       reads is a pure function of how many ticks have run. Two processes that have run the
 *       same number of ticks therefore agree on every interval check — monsters step on the
 *       same tick, respawns fire on the same tick, regeneration lands on the same tick. A
 *       host hiccup that delays a tick no longer shifts an AI decision into a different tick
 *       on one side only; it just makes both worlds run slightly slower in wall time.</li>
 *   <li>{@link #manual(long, long)} — virtual time whose ticks are advanced by the caller
 *       ({@link WorldEngine#tickOnce()} without the scheduler, or {@link #advanceTicks}).
 *       Deterministic unit tests already do this by hand with a {@code LongSupplier}; this
 *       is the same thing with an explicit name and a tick interval.</li>
 * </ul>
 *
 * <p><b>Deliberate deviation, recorded for the plan:</b> virtual mode is not Delphi. Delphi
 * reads the real {@code GetTickCount} inside its tick, so a slow tick makes the next
 * interval check see a bigger delta and, for example, lets a monster take two steps'
 * worth of "owed" time in one pass. Virtual mode quantises time to the tick grid, so the
 * owed time never accumulates. With the shipped 50 ms tick this changes nothing an operator
 * could observe on a healthy host (every interval in the engine is ≥200 ms, i.e. ≥4 ticks),
 * but it is a real divergence under load and the reason production stays on
 * {@link #system()}.
 */
public final class WorldClock {

  /** Which of the three time sources backs this clock. */
  public enum Mode {
    /** Wall clock: {@code System.currentTimeMillis()}, the production default. */
    SYSTEM,
    /** Tick-derived time, advanced automatically by the engine's tick loop. */
    VIRTUAL,
    /** Tick-derived time, advanced only by explicit caller action. */
    MANUAL
  }

  /**
   * Virtual clocks start here rather than at 0. {@code TBaseObject.Initialize} stamps its
   * decay/regeneration windows with the current tick and then compares
   * {@code GetTickCount - stamp > interval}; a zero epoch would put the first few seconds of
   * world uptime inside every cooldown at once. A large round epoch keeps the arithmetic in
   * the same magnitude as a real {@code GetTickCount} without importing wall-clock values.
   */
  public static final long DEFAULT_EPOCH_MILLIS = 1_000_000L;

  private final Mode mode;
  private final long epochMillis;
  private final long tickMillis;
  private final AtomicLong ticks = new AtomicLong();

  private WorldClock(Mode mode, long epochMillis, long tickMillis) {
    this.mode = Objects.requireNonNull(mode, "mode");
    if (tickMillis < 1) throw new IllegalArgumentException("tick interval must be positive");
    if (epochMillis < 0) throw new IllegalArgumentException("epoch must not be negative");
    this.epochMillis = epochMillis;
    this.tickMillis = tickMillis;
  }

  /** Production wall clock. */
  public static WorldClock system() {
    return new WorldClock(Mode.SYSTEM, 0, 1);
  }

  /** Tick-derived clock advanced by the engine's own tick loop. */
  public static WorldClock virtual(long tickMillis) {
    return virtual(DEFAULT_EPOCH_MILLIS, tickMillis);
  }

  /** Tick-derived clock with an explicit epoch. */
  public static WorldClock virtual(long epochMillis, long tickMillis) {
    return new WorldClock(Mode.VIRTUAL, epochMillis, tickMillis);
  }

  /** Tick-derived clock the caller advances explicitly. */
  public static WorldClock manual(long tickMillis) {
    return manual(DEFAULT_EPOCH_MILLIS, tickMillis);
  }

  /** Tick-derived clock the caller advances explicitly, with an explicit epoch. */
  public static WorldClock manual(long epochMillis, long tickMillis) {
    return new WorldClock(Mode.MANUAL, epochMillis, tickMillis);
  }

  public Mode mode() {
    return mode;
  }

  /** True when time comes from the tick counter rather than the host clock. */
  public boolean isVirtual() {
    return mode != Mode.SYSTEM;
  }

  /**
   * True when the engine's tick loop owns the advance. {@link Mode#MANUAL} clocks are moved
   * by the caller instead, so the engine must not touch them.
   */
  public boolean advancesWithEngineTick() {
    return mode == Mode.VIRTUAL;
  }

  public long tickMillis() {
    return tickMillis;
  }

  /** Ticks elapsed on a virtual clock; always 0 for {@link Mode#SYSTEM}. */
  public long ticks() {
    return ticks.get();
  }

  /** Current world time in milliseconds. */
  public long millis() {
    return mode == Mode.SYSTEM
        ? System.currentTimeMillis()
        : epochMillis + ticks.get() * tickMillis;
  }

  /** The {@link LongSupplier} the engine reads; identical to {@link #millis()}. */
  public LongSupplier asSupplier() {
    return this::millis;
  }

  /** Advances a virtual/manual clock by one tick and returns the new time. A SYSTEM clock ignores this. */
  public long advanceOneTick() {
    return advanceTicks(1);
  }

  /** Advances a virtual/manual clock by {@code count} ticks and returns the new time. */
  public long advanceTicks(long count) {
    if (count < 0) throw new IllegalArgumentException("tick count must not be negative");
    if (mode == Mode.SYSTEM) return millis();
    ticks.addAndGet(count);
    return millis();
  }

  @Override
  public String toString() {
    return mode == Mode.SYSTEM
        ? "WorldClock[system]"
        : "WorldClock[" + mode.name().toLowerCase(java.util.Locale.ROOT)
            + " tick=" + tickMillis + "ms ticks=" + ticks.get() + " now=" + millis() + "]";
  }
}
