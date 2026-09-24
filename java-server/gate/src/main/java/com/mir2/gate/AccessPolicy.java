package com.mir2.gate;

import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gate-wide admission guard — the Java form of Delphi's {@code IsBlockIP} + {@code IsConnLimited}
 * pair, which every gate runs on {@code ServerSocketClientConnect} before a single protocol byte
 * is read (LoginGate/Main.pas:532-560; the {@code IsConnLimited} body is at :1005-1044).
 *
 * <p>Delphi keeps one {@code TSockaddr} record per source address in {@code CurrIPaddrList} and
 * enforces <b>three</b> independent limits from it (GateShare.pas:10-17 defines the fields):
 * <ol>
 *   <li><b>Concurrency</b> — {@code nCount}, incremented on connect and decremented on
 *       disconnect (Main.pas:253-262). Denies once {@code nCount > nMaxConnOfIPaddr}
 *       ({@code 10} on LoginGate/SelGate, {@code 50} on RunGate).</li>
 *   <li><b>1-second burst</b> — {@code nIPCount1} against {@code nIPCountLimit1} (20).</li>
 *   <li><b>3-second burst</b> — {@code nIPCount2} against {@code nIPCountLimit2} (40).</li>
 * </ol>
 *
 * <p><b>The burst windows are tumbling, not sliding, and that is reproduced exactly.</b> Delphi
 * does not keep timestamps; it keeps a counter plus the tick at which the current window opened.
 * Each attempt either falls inside the window ({@code now - tick < span}) and increments the
 * counter, or falls outside it and <em>resets the window</em> — setting {@code tick := now} and,
 * critically, {@code count := 0} rather than {@code 1}. The connection that rolls a window over
 * is therefore not counted in it, so the shipped {@code nIPCountLimit1 = 20} actually admits 21
 * connections per second (the roll-over one, then 20 more before {@code count >= 20} bites).
 * That off-by-one is the original's, and is kept.
 *
 * <p><b>The concurrency comparison is {@code >}, not {@code >=}</b> (Main.pas:1033), but that
 * does <em>not</em> buy an extra connection: {@code nCount} is incremented before the test, and
 * a refused socket is closed immediately, which fires {@code ServerSocketClientDisconnect} and
 * decrements it again (Main.pas:253-262). The two cancel, so {@code nMaxConnOfIPaddr = 50}
 * admits exactly 50 concurrent connections. This class matches that by releasing the increment
 * on the refusal path — the alternative (letting denials accumulate) would permanently lock out
 * an address that once burst past its cap.
 *
 * <p><b>The first connection from an address is never checked at all.</b> Delphi's loop only
 * evaluates limits for an address already in {@code CurrIPaddrList}; a miss falls through to the
 * tail of the function, which allocates the record with {@code nCount := 1} and returns the
 * initial {@code Result := False} (Main.pas:1038-1043). Note what this means for the burst
 * windows: the new record is zero-filled, so both window ticks start at 0 rather than "now",
 * and the second connection from that address is compared against tick 0 — on a freshly booted
 * Windows box {@code GetTickCount} is small, so that comparison can land <em>inside</em> the
 * window and count. This class reproduces the zero-initialised window ticks rather than
 * pretending the window opens on first contact.
 *
 * <p>Sharing one instance across all three gates is a deliberate divergence, kept from the
 * earlier implementation: Delphi runs three separate processes, each with its own list and its
 * own quota, so an address gets 10 + 10 + 50. One shared policy is the safer reading and is what
 * the bot rehearsal is tuned against.
 */
public final class AccessPolicy {

  /** Delphi's 1-second burst window ({@code GetTickCount - dwIPCountTick1 < 1000}). */
  public static final Duration BURST_WINDOW_1 = Duration.ofSeconds(1);
  /** Delphi's 3-second burst window ({@code GetTickCount - dwIPCountTick2 < 3000}). */
  public static final Duration BURST_WINDOW_2 = Duration.ofSeconds(3);

  public record Config(
      int maxActivePerIp,
      int burstLimit1,
      int burstLimit2,
      Duration idleTimeout,
      BlockMethod blockMethod) {

    public Config {
      if (maxActivePerIp < 1) throw new IllegalArgumentException("maxActivePerIp must be positive");
      if (burstLimit1 < 1 || burstLimit2 < 1)
        throw new IllegalArgumentException("burst limits must be positive");
      Objects.requireNonNull(idleTimeout, "idleTimeout");
      Objects.requireNonNull(blockMethod, "blockMethod");
      if (idleTimeout.isZero() || idleTimeout.isNegative())
        throw new IllegalArgumentException("idle timeout must be positive");
    }

    /**
     * Shipped Delphi defaults, with the concurrency cap taken from RunGate
     * ({@code nMaxConnOfIPaddr = 50}) rather than the gates' 10 — the three gates share one
     * policy here, and 50 is the value that keeps the documented 50-bot rehearsal admissible
     * from a single loopback address.
     */
    public static Config defaults() {
      return new Config(50, 20, 40, Duration.ofMinutes(15), BlockMethod.DISCONNECT);
    }
  }

  /** Why an attempt was refused — surfaced for logging and for the block-method action. */
  public enum Denial {
    /** {@code IsBlockIP}: the address is on the permanent or temporary ban list. */
    BLOCKED,
    /** {@code nCount > nMaxConnOfIPaddr}. */
    TOO_MANY_CONNECTIONS,
    /** {@code nIPCount1 >= nIPCountLimit1} — too many connects inside one second. */
    BURST_1S,
    /** {@code nIPCount2 >= nIPCountLimit2} — too many connects inside three seconds. */
    BURST_3S
  }

  private final Config config;
  private final Clock clock;
  private final BlockIpList blockList;
  private final Map<String, State> states = new HashMap<>();

  public AccessPolicy() { this(Config.defaults()); }

  public AccessPolicy(Config config) { this(config, BlockIpList.empty()); }

  public AccessPolicy(Config config, BlockIpList blockList) {
    this(config, blockList, Clock.systemUTC());
  }

  AccessPolicy(Config config, Clock clock) { this(config, BlockIpList.empty(), clock); }

  AccessPolicy(Config config, BlockIpList blockList, Clock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.blockList = Objects.requireNonNull(blockList, "blockList");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The ban list backing {@code IsBlockIP}; shared with the gate so it can add entries. */
  public BlockIpList blockList() { return blockList; }

  public BlockMethod blockMethod() { return config.blockMethod(); }

  public Duration idleTimeout() { return config.idleTimeout(); }

  /** Returns a permit, or {@code null} when the source is banned or over a limit. */
  public Permit tryAcquire(SocketAddress address) {
    return tryAcquire(address, null);
  }

  /**
   * {@code IsBlockIP} then {@code IsConnLimited}, in Delphi's order. Returns {@code null} when
   * the connection must be refused; {@code denial}, when non-null, receives the reason.
   */
  public synchronized Permit tryAcquire(SocketAddress address, Denial[] denial) {
    String ip = BlockIpList.ip(address);
    if (blockList.isBlocked(ip)) {
      report(denial, Denial.BLOCKED);
      return null;
    }

    long now = clock.millis();
    State state = states.get(ip);
    if (state == null) {
      // Delphi's list-miss tail: allocate a zero-filled record with nCount := 1 and admit
      // without evaluating any limit. Both window ticks stay 0 on purpose (see class note).
      state = new State();
      state.active = 1;
      states.put(ip, state);
      return new Permit(this, ip);
    }

    // Inc(IPaddr.nCount) happens before — and independently of — every test below.
    state.active++;

    boolean deny = false;
    Denial reason = null;

    if (now - state.burstTick1 < BURST_WINDOW_1.toMillis()) {
      state.burstCount1++;
      if (state.burstCount1 >= config.burstLimit1()) { deny = true; reason = Denial.BURST_1S; }
    } else {
      // Window rolls over: the opening connection is NOT counted (count := 0, not 1).
      state.burstTick1 = now;
      state.burstCount1 = 0;
    }

    if (now - state.burstTick2 < BURST_WINDOW_2.toMillis()) {
      state.burstCount2++;
      if (state.burstCount2 >= config.burstLimit2()) { deny = true; reason = Denial.BURST_3S; }
    } else {
      state.burstTick2 = now;
      state.burstCount2 = 0;
    }

    // '>' not '>=': nMaxConnOfIPaddr = 50 admits a 51st concurrent connection.
    if (state.active > config.maxActivePerIp()) {
      deny = true;
      // Delphi evaluates this last, so it wins the reason when several limits trip at once.
      reason = Denial.TOO_MANY_CONNECTIONS;
    }

    if (deny) {
      // The refused attempt still counted towards nCount; releasing keeps the bookkeeping
      // honest for a socket that is about to be closed without ever being served.
      state.active--;
      pruneIfIdle(ip, state);
      report(denial, reason);
      return null;
    }
    return new Permit(this, ip);
  }

  /**
   * Applies {@code BlockMethod} to an address that tripped a limit: {@code mBlock} and
   * {@code mBlockList} add a ban entry, {@code mDisconnect} does nothing beyond closing the
   * socket the caller already owns. Returns {@code true} when the address was banned, which is
   * Delphi's signal to also run {@code CloseConnect} and drop that address's other sockets.
   */
  public boolean applyBlockMethod(SocketAddress address) {
    return applyBlockMethod(BlockIpList.ip(address));
  }

  /** {@link #applyBlockMethod(SocketAddress)} for an already-extracted address. */
  public boolean applyBlockMethod(String ip) {
    switch (config.blockMethod()) {
      case DISCONNECT -> { return false; }
      case BLOCK -> { blockList.blockTemporarily(ip); return true; }
      case BLOCK_LIST -> { blockList.blockPermanently(ip); return true; }
    }
    return false;
  }

  /** Active connection count for an address — {@code nCount}, for tests and diagnostics. */
  public synchronized int activeConnections(String ip) {
    State state = states.get(ip);
    return state == null ? 0 : state.active;
  }

  private static void report(Denial[] sink, Denial reason) {
    if (sink != null && sink.length > 0) sink[0] = reason;
  }

  private synchronized void release(String ip) {
    State state = states.get(ip);
    if (state == null) return;
    if (state.active > 0) state.active--;
    pruneIfIdle(ip, state);
  }

  /**
   * Delphi's disconnect handler disposes the record as soon as {@code nCount <= 0}
   * (Main.pas:256-260) — which also throws away that address's burst counters. Reproduced:
   * an address that fully disconnects starts its next burst window from scratch.
   */
  private void pruneIfIdle(String ip, State state) {
    if (state.active <= 0) states.remove(ip);
  }

  public static final class Permit implements AutoCloseable {
    private final AccessPolicy owner;
    private final String ip;
    private boolean released;

    private Permit(AccessPolicy owner, String ip) { this.owner = owner; this.ip = ip; }

    /** The source address this permit accounts for. */
    public String ip() { return ip; }

    @Override public synchronized void close() {
      if (!released) { released = true; owner.release(ip); }
    }
  }

  private static final class State {
    private int active;
    // Zero-initialised exactly like Delphi's FillChar'd TSockaddr record.
    private long burstTick1;
    private int burstCount1;
    private long burstTick2;
    private int burstCount2;
  }
}
