package com.mir2.loadtest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launches and supervises a swarm of {@link Mir2Bot}s against one MIR2 server.
 *
 * <p>Bots ramp up gradually, act until the run duration elapses (or
 * {@link #requestStop()} is called, e.g. from a shutdown hook), and report into one shared
 * {@link BotMetrics}. The returned {@link Result} feeds {@link BotReport}.
 */
public final class BotSwarm {
  private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(10);
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(90);
  private static final Duration STRAGGLER_GRACE = Duration.ofSeconds(15);

  /** Immutable swarm configuration; the bots derive their accounts from it. */
  public record Spec(
      int bots,
      Duration duration,
      Duration rampUp,
      String host,
      int loginPort,
      int selectPortOverride,
      int gamePortOverride,
      String serverName,
      String accountPrefix,
      String accountPassword,
      Duration thinkMin,
      Duration thinkMax,
      Duration relogEvery,
      long seed) {

    public Spec {
      Objects.requireNonNull(duration, "duration");
      Objects.requireNonNull(rampUp, "rampUp");
      Objects.requireNonNull(thinkMin, "thinkMin");
      Objects.requireNonNull(thinkMax, "thinkMax");
      Objects.requireNonNull(relogEvery, "relogEvery");
      if (bots < 1 || bots > 10_000) throw new IllegalArgumentException("bots must be 1..10000");
      if (duration.isZero() || duration.isNegative())
        throw new IllegalArgumentException("duration must be positive");
      if (rampUp.isNegative()) throw new IllegalArgumentException("rampUp must not be negative");
      if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
      if (loginPort < 1 || loginPort > 65535)
        throw new IllegalArgumentException("invalid login port");
      if (selectPortOverride < 0 || selectPortOverride > 65535
          || gamePortOverride < 0 || gamePortOverride > 65535)
        throw new IllegalArgumentException("invalid port override (0 = use advertised)");
      if (serverName == null || serverName.isBlank() || serverName.indexOf('/') >= 0)
        throw new IllegalArgumentException("invalid server name");
      if (accountPrefix == null || accountPrefix.isBlank() || accountPrefix.length() > 16)
        throw new IllegalArgumentException("account prefix must be 1..16 characters");
      if (accountPassword == null || accountPassword.isEmpty())
        throw new IllegalArgumentException("account password is required");
      if (accountPassword.indexOf('/') >= 0 || accountPrefix.indexOf('/') >= 0)
        throw new IllegalArgumentException("account fields must not contain '/'");
      if (thinkMin.isZero() || thinkMin.isNegative() || thinkMax.compareTo(thinkMin) < 0)
        throw new IllegalArgumentException("thinkMin must be positive and <= thinkMax");
      if (relogEvery.isZero() || relogEvery.isNegative())
        throw new IllegalArgumentException("relogEvery must be positive");
    }

    /** Account and character name of bot {@code index} (zero-based). */
    String botAccount(int index) {
      return String.format("%s%04d", accountPrefix, index + 1);
    }

    /** Per-bot ramp spacing so the whole swarm is online after roughly {@code rampUp}. */
    Duration rampSpacing() {
      if (bots <= 1) return Duration.ZERO;
      long millis = rampUp.toMillis() / (bots - 1);
      return Duration.ofMillis(Math.max(1, millis));
    }
  }

  /** Everything the report needs after the swarm has finished. */
  public record Result(Spec spec, BotMetrics.Snapshot metrics, Instant startedAt,
      Instant endedAt, boolean stoppedEarly, List<String> notes) {

    public Result {
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(metrics, "metrics");
      Objects.requireNonNull(startedAt, "startedAt");
      Objects.requireNonNull(endedAt, "endedAt");
      notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** Wall-clock duration of the run, including ramp-up and teardown. */
    public Duration wallClock() {
      return Duration.between(startedAt, endedAt);
    }
  }

  private final Spec spec;
  private final BotMetrics metrics = new BotMetrics();
  private final List<Mir2Bot> bots = new CopyOnWriteArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private final AtomicInteger inWorld = new AtomicInteger();
  private final AtomicInteger finished = new AtomicInteger();
  private volatile boolean stopRequested;
  private volatile boolean running;

  public BotSwarm(Spec spec) {
    this.spec = Objects.requireNonNull(spec, "spec");
  }

  /** Live metrics, mainly for tests that assert while the swarm is still running. */
  public BotMetrics metrics() {
    return metrics;
  }

  /** Adds one report note, e.g. embedded-server or JVM-heap observations. */
  public void addNote(String note) {
    synchronized (notes) {
      notes.add(note);
    }
  }

  /** Current notes; call after {@link #run()} (plus any later {@link #addNote}) for reports. */
  public List<String> notes() {
    synchronized (notes) {
      return List.copyOf(notes);
    }
  }

  /** Asks every bot to wind down cleanly (no error counters change because of this). */
  public void requestStop() {
    stopRequested = true;
    for (Mir2Bot bot : bots) bot.requestStop();
  }

  /** Runs the whole swarm to completion of the configured duration. */
  public Result run() {
    if (running) throw new IllegalStateException("swarm is already running");
    running = true;
    Instant startedAt = Instant.now();
    long startNanos = System.nanoTime();

    Duration spacing = spec.rampSpacing();
    for (int index = 0; index < spec.bots(); index++) {
      if (stopRequested) break;
      Mir2Bot bot = new Mir2Bot(spec, index, metrics,
          new Random(spec.seed() + index), inWorld::addAndGet,
          ignored -> finished.incrementAndGet());
      bots.add(bot);
      metrics.count(BotMetrics.Key.BOTS_LAUNCHED);
      Thread.ofVirtual().name("bot-" + spec.botAccount(index)).start(bot);
      if (index + 1 < spec.bots()) sleepQuiet(spacing);
    }

    Thread monitor = Thread.ofVirtual().name("bot-swarm-monitor")
        .start(() -> logProgress(startedAt));

    long hardDeadline = startNanos + spec.duration().toNanos() + spec.rampUp().toNanos()
        + SHUTDOWN_GRACE.toNanos();
    while (finished.get() < spec.bots() && System.nanoTime() < hardDeadline) {
      sleepQuiet(Duration.ofMillis(200));
    }
    // The internal wind-down must not look like an external stop request.
    boolean stoppedEarly = stopRequested;
    requestStop();
    long stragglerDeadline = System.nanoTime() + STRAGGLER_GRACE.toNanos();
    while (finished.get() < spec.bots() && System.nanoTime() < stragglerDeadline) {
      sleepQuiet(Duration.ofMillis(100));
    }
    running = false;
    monitor.interrupt();

    List<String> snapshotNotes;
    synchronized (notes) {
      snapshotNotes = List.copyOf(notes);
    }
    return new Result(spec, metrics.snapshot(), startedAt, Instant.now(), stoppedEarly,
        snapshotNotes);
  }

  private void logProgress(Instant startedAt) {
    while (running) {
      try {
        Thread.sleep(PROGRESS_INTERVAL.toMillis());
      } catch (InterruptedException interrupted) {
        return;
      }
      if (!running) return;
      BotMetrics.Snapshot snapshot = metrics.snapshot();
      long sent = snapshot.actions().values().stream().mapToLong(BotMetrics.ActionStats::sent).sum();
      long received = snapshot.receivedByIdent().stream().mapToLong(Map.Entry::getValue).sum();
      double worstP95 = snapshot.actions().values().stream()
          .mapToDouble(action -> Double.isNaN(action.p90Millis()) ? 0 : action.p90Millis())
          .max().orElse(0);
      System.out.printf(Locale.ROOT,
          "[bot-swarm] t=%s inWorld=%d entries=%d relogs=%d sent=%d recv=%d ackP90<=%.1fms errors=%d%n",
          formatElapsed(Duration.between(startedAt, Instant.now())), inWorld.get(),
          snapshot.get(BotMetrics.Key.GAME_ENTRIES), snapshot.get(BotMetrics.Key.RELOGS_COMPLETED),
          sent, received, worstP95, snapshot.totalErrors());
    }
  }

  private static String formatElapsed(Duration elapsed) {
    return String.format(Locale.ROOT, "%d:%02d", elapsed.toMinutes(), elapsed.toSecondsPart());
  }

  private static void sleepQuiet(Duration duration) {
    try {
      Thread.sleep(Math.max(1, duration.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
