package com.mir2.gate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small, gate-wide admission guard.  Delphi's three gates all apply an IP based
 * connection limit; keeping the state here also makes login/select/game share
 * the same limit instead of accidentally getting three independent quotas.
 */
public final class AccessPolicy {
  public record Config(int maxActivePerIp, int maxAttemptsPerWindow, Duration attemptWindow,
      Duration idleTimeout) {
    public Config {
      if (maxActivePerIp < 1 || maxAttemptsPerWindow < 1) throw new IllegalArgumentException("limits must be positive");
      Objects.requireNonNull(attemptWindow, "attemptWindow");
      Objects.requireNonNull(idleTimeout, "idleTimeout");
      if (attemptWindow.isZero() || attemptWindow.isNegative()) throw new IllegalArgumentException("attempt window must be positive");
      if (idleTimeout.isZero() || idleTimeout.isNegative()) throw new IllegalArgumentException("idle timeout must be positive");
    }

    public static Config defaults() {
      // Generous enough for the documented 50-bot rehearsal, while bounding a
      // single source before authentication has happened.
      return new Config(128, 300, Duration.ofMinutes(1), Duration.ofMinutes(15));
    }
  }

  private final Config config;
  private final Clock clock;
  private final Map<String, State> states = new HashMap<>();

  public AccessPolicy() { this(Config.defaults()); }
  public AccessPolicy(Config config) { this(config, Clock.systemUTC()); }
  AccessPolicy(Config config, Clock clock) {
    this.config = Objects.requireNonNull(config);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Returns a permit, or null when the source is over either admission limit. */
  public synchronized Permit tryAcquire(SocketAddress address) {
    String ip = ip(address);
    long now = clock.millis();
    State state = states.computeIfAbsent(ip, ignored -> new State());
    long cutoff = now - config.attemptWindow().toMillis();
    while (!state.attempts.isEmpty() && state.attempts.peekFirst() <= cutoff) state.attempts.removeFirst();
    if (state.active >= config.maxActivePerIp() || state.attempts.size() >= config.maxAttemptsPerWindow()) {
      return null;
    }
    state.active++;
    state.attempts.addLast(now);
    return new Permit(this, ip);
  }

  public Duration idleTimeout() { return config.idleTimeout(); }

  private synchronized void release(String ip) {
    State state = states.get(ip);
    if (state == null) return;
    if (state.active > 0) state.active--;
    if (state.active == 0 && state.attempts.isEmpty()) states.remove(ip);
  }

  private static String ip(SocketAddress address) {
    if (address instanceof InetSocketAddress inet) {
      InetAddress addressValue = inet.getAddress();
      return addressValue == null ? inet.getHostString() : addressValue.getHostAddress();
    }
    return String.valueOf(address);
  }

  public static final class Permit implements AutoCloseable {
    private final AccessPolicy owner;
    private final String ip;
    private boolean released;
    private Permit(AccessPolicy owner, String ip) { this.owner = owner; this.ip = ip; }
    @Override public synchronized void close() {
      if (!released) { released = true; owner.release(ip); }
    }
  }

  private static final class State {
    private int active;
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
  }
}
