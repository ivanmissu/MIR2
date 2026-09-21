package com.mir2.loadtest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Aggregated, thread-safe counters and action latencies for one swarm run. */
public final class BotMetrics {
  private final ConcurrentHashMap<Key, LongAdder> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, LongAdder> receivedByIdent = new ConcurrentHashMap<>();
  private final Map<ActionKind, ActionCounters> actionCounters = new ConcurrentHashMap<>();
  private final Map<ActionKind, LongSamples> latencies = new ConcurrentHashMap<>();

  /** Action kinds that participate in the {@code +GOOD/+FAIL} acknowledgement exchange. */
  public enum ActionKind {
    TURN("turn"),
    WALK("walk"),
    RUN("run"),
    HIT("melee hit"),
    PICKUP("pickup");

    private final String label;

    ActionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** Every counter the swarm reports; the error keys drive the run verdict. */
  public enum Key {
    BOTS_LAUNCHED("bots launched"),
    BOTS_ENTERED("bots that entered the world at least once"),
    GAME_ENTRIES("game entries (SM_NEWMAP received)"),
    ENTER_RETRIES("game-enter attempts retried (e.g. the leave-race after a fast relog)"),
    RELOGS_COMPLETED("voluntary relogs completed"),
    SESSIONS_ENDED("game sessions ended"),
    PLAYERS_DIED("bot deaths observed (the bot relogs and is revived at 14 HP)"),
    ACK_TIMEOUTS("action acknowledgements that timed out"),
    SOCKET_ERRORS("in-world socket errors not caused by the bot itself"),
    UNEXPECTED_DISCONNECTS("game sockets closed by the server"),
    ERR_LOGIN("unrecoverable login/select-gate failures"),
    ERR_ENTER("game-entry retries exhausted"),
    UNMATCHED_ACKS("acks that arrived without a pending action"),
    BAG_QUERIES_SENT("CM_QUERYBAGITEMS sent"),
    BAG_LISTS_RECEIVED("SM_BAGITEMS received"),
    ITEMS_PICKED_UP("SM_ADDITEM received"),
    EXPERIENCE_UPDATES("SM_WINEXP received"),
    LEVEL_UPS("SM_LEVELUP received"),
    REVIVALS_SEEN("SM_ALIVE received"),
    OBSERVED_DEATHS("SM_DEATH observed for other objects"),
    GROUND_ITEMS_SEEN("SM_ITEMSHOW received"),
    POSITION_RESYNCS("sessions restarted to resynchronise position");

    private final String label;

    Key(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    boolean isError() {
      return this == ACK_TIMEOUTS || this == SOCKET_ERRORS || this == UNEXPECTED_DISCONNECTS
          || this == ERR_LOGIN || this == ERR_ENTER;
    }
  }

  /** Adds one to a counter. */
  public void count(Key key) {
    counters.computeIfAbsent(key, ignored -> new LongAdder()).increment();
  }

  /** Adds an arbitrary delta to a counter. */
  public void count(Key key, long delta) {
    counters.computeIfAbsent(key, ignored -> new LongAdder()).add(delta);
  }

  /** Records one server packet by protocol ident. */
  public void received(int ident) {
    receivedByIdent.computeIfAbsent(ident, ignored -> new LongAdder()).increment();
  }

  /** Records one sent action. */
  public void sent(ActionKind kind) {
    actionCounters(kind).sent.increment();
  }

  /** Records one acknowledged action and its send-to-ack latency. */
  public void acknowledged(ActionKind kind, boolean accepted, long latencyNanos) {
    ActionCounters counters = actionCounters(kind);
    if (accepted) counters.good.increment();
    else counters.fail.increment();
    latencies.computeIfAbsent(kind, ignored -> new LongSamples()).add(latencyNanos);
  }

  /** Records an action whose acknowledgement never arrived. */
  public void ackTimeout(ActionKind kind) {
    actionCounters(kind).timeout.increment();
  }

  private ActionCounters actionCounters(ActionKind kind) {
    return actionCounters.computeIfAbsent(kind, ignored -> new ActionCounters());
  }

  /** Immutable view of one action kind's counters and latency statistics. */
  public record ActionStats(long sent, long good, long fail, long timeout,
      int samples, double p50Millis, double p90Millis, double p99Millis, double maxMillis) {}

  /** Immutable point-in-time view used by the report writer. */
  public record Snapshot(Map<Key, Long> counters,
      Map<ActionKind, ActionStats> actions,
      List<Map.Entry<String, Long>> receivedByIdent) {

    /** Counter value, zero when never touched. */
    public long get(Key key) {
      return counters.getOrDefault(key, 0L);
    }

    /** Sum of every error counter; the run verdict fails when this is nonzero. */
    public long totalErrors() {
      long total = 0;
      for (Key key : Key.values()) if (key.isError()) total += get(key);
      return total;
    }
  }

  /** Captures the current values. */
  public Snapshot snapshot() {
    Map<Key, Long> counts = new TreeMap<>();
    for (Key key : Key.values()) {
      LongAdder adder = counters.get(key);
      if (adder != null && adder.sum() != 0) counts.put(key, adder.sum());
    }
    Map<ActionKind, ActionStats> actions = new TreeMap<>();
    for (ActionKind kind : ActionKind.values()) {
      ActionCounters counters = actionCounters.get(kind);
      LongSamples samples = latencies.get(kind);
      if (counters == null && samples == null) continue;
      long sent = counters == null ? 0 : counters.sent.sum();
      long good = counters == null ? 0 : counters.good.sum();
      long fail = counters == null ? 0 : counters.fail.sum();
      long timeout = counters == null ? 0 : counters.timeout.sum();
      LongSamples copy = samples == null ? new LongSamples() : samples;
      actions.put(kind, new ActionStats(sent, good, fail, timeout, copy.count(),
          copy.percentileMillis(50), copy.percentileMillis(90),
          copy.percentileMillis(99), copy.maxNanos() == Long.MIN_VALUE
              ? Double.NaN : copy.maxNanos() / 1_000_000.0));
    }
    List<Map.Entry<String, Long>> received = new java.util.ArrayList<>();
    for (Map.Entry<Integer, LongAdder> entry : receivedByIdent.entrySet()) {
      received.add(Map.entry(identName(entry.getKey()), entry.getValue().sum()));
    }
    received.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
    return new Snapshot(counts, actions, received);
  }

  private static final class ActionCounters {
    final LongAdder sent = new LongAdder();
    final LongAdder good = new LongAdder();
    final LongAdder fail = new LongAdder();
    final LongAdder timeout = new LongAdder();
  }

  /** Reverse lookup table for report rows, built once from the protocol constants. */
  private static final Map<Integer, String> IDENT_NAMES = buildIdentNames();

  private static Map<Integer, String> buildIdentNames() {
    Map<Integer, String> names = new TreeMap<>();
    for (Field field : com.mir2.protocol.ProtocolConstants.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers) || field.getType() != int.class) {
        continue;
      }
      if (!field.getName().startsWith("SM_")) continue;
      try {
        names.putIfAbsent(field.getInt(null), field.getName());
      } catch (IllegalAccessException ignored) {
        // A constant we cannot read simply has no pretty name in the report.
      }
    }
    return names;
  }

  private static String identName(int ident) {
    String name = IDENT_NAMES.get(ident);
    return name == null ? "SM_" + ident : name;
  }
}
