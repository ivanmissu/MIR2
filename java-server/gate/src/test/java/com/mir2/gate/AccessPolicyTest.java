package com.mir2.gate;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Delphi admission semantics reproduced by {@link AccessPolicy}: the three limits of
 * {@code IsConnLimited} (LoginGate/Main.pas:1005-1044), the {@code IsBlockIP} pre-check, and
 * the {@code BlockMethod} action taken on a trip.
 */
class AccessPolicyTest {

  private static final Duration IDLE = Duration.ofSeconds(5);

  /** A clock the test advances by hand, so the tumbling burst windows are deterministic. */
  private static final class TestClock extends Clock {
    private long millis = 1_000_000L;
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    @Override public long millis() { return millis; }
    void advance(long delta) { millis += delta; }
  }

  private static AccessPolicy.Config config(int maxActive, int burst1, int burst3) {
    return new AccessPolicy.Config(maxActive, burst1, burst3, IDLE, BlockMethod.DISCONNECT);
  }

  private static InetSocketAddress source(int port) {
    return new InetSocketAddress("127.0.0.1", port);
  }

  @Test
  void concurrencyLimitAdmitsExactlyTheConfiguredNumber() {
    // Delphi's test is 'nCount > nMaxConnOfIPaddr', but nCount is incremented before it and
    // the refused socket is closed straight away — which fires ServerSocketClientDisconnect
    // and decrements it again (Main.pas:253-262). The two cancel out, so a limit of 2 admits
    // exactly 2 concurrent connections, not 3.
    AccessPolicy policy = new AccessPolicy(config(2, 1000, 1000), BlockIpList.empty(), new TestClock());
    AccessPolicy.Permit first = policy.tryAcquire(source(1001));
    AccessPolicy.Permit second = policy.tryAcquire(source(1002));
    assertNotNull(first, "first connection is never checked at all");
    assertNotNull(second);
    assertEquals(2, policy.activeConnections("127.0.0.1"));

    AccessPolicy.Denial[] denial = new AccessPolicy.Denial[1];
    assertNull(policy.tryAcquire(source(1003), denial));
    assertEquals(AccessPolicy.Denial.TOO_MANY_CONNECTIONS, denial[0]);
    assertEquals(2, policy.activeConnections("127.0.0.1"),
        "a refused attempt must not leak a connection slot");

    // Releasing frees a slot again.
    second.close();
    assertNotNull(policy.tryAcquire(source(1004)));
    first.close();
  }

  @Test
  void limitsActiveConnectionsPerIpAcrossGates() {
    AccessPolicy policy = new AccessPolicy(config(1, 1000, 1000), BlockIpList.empty(), new TestClock());
    try (AccessPolicy.Permit permit = policy.tryAcquire(source(1000))) {
      assertNotNull(permit);
      // Second concurrent connection: nCount == 2 > 1, refused — and the gate kind does not
      // matter, because all three gates share this one policy instance.
      assertNull(policy.tryAcquire(source(1001)));
    }
    // Once the first permit is released the address is welcome again.
    assertNotNull(policy.tryAcquire(source(1002)));
  }

  @Test
  void burstWindowIsTumblingAndSkipsTheConnectionThatOpensIt() {
    // nIPCountLimit1 = 3 over Delphi's fixed 1s window. The record is created by the first
    // connection without evaluating anything; from then on each attempt inside the window
    // increments nIPCount1 and denies at >= 3.
    TestClock clock = new TestClock();
    AccessPolicy policy = new AccessPolicy(config(1000, 3, 1000), BlockIpList.empty(), clock);

    assertNotNull(policy.tryAcquire(source(1)), "list miss: admitted unchecked");
    // Window ticks start at 0 in the zero-filled record, and the clock is far past 1000ms,
    // so this attempt rolls the window over (count := 0) rather than counting.
    assertNotNull(policy.tryAcquire(source(2)));
    assertNotNull(policy.tryAcquire(source(3)), "count 1");
    assertNotNull(policy.tryAcquire(source(4)), "count 2");

    AccessPolicy.Denial[] denial = new AccessPolicy.Denial[1];
    assertNull(policy.tryAcquire(source(5), denial), "count 3 >= limit 3");
    assertEquals(AccessPolicy.Denial.BURST_1S, denial[0]);

    // Crossing the window boundary resets the counter to 0 — not to 1.
    clock.advance(1001);
    assertNotNull(policy.tryAcquire(source(6)), "window rolled over");
    assertNotNull(policy.tryAcquire(source(7)));
    assertNotNull(policy.tryAcquire(source(8)));
    assertNull(policy.tryAcquire(source(9)), "limit reached again inside the new window");
  }

  @Test
  void threeSecondWindowLimitsSeparately() {
    TestClock clock = new TestClock();
    // Generous 1s limit, tight 3s limit: only the 3s window can trip.
    AccessPolicy policy = new AccessPolicy(config(1000, 1000, 3), BlockIpList.empty(), clock);
    assertNotNull(policy.tryAcquire(source(1)));
    assertNotNull(policy.tryAcquire(source(2)));
    for (int i = 0; i < 2; i++) {
      // Stay inside the 3s window while stepping past each 1s window.
      clock.advance(900);
      assertNotNull(policy.tryAcquire(source(10 + i)));
    }
    clock.advance(900);
    AccessPolicy.Denial[] denial = new AccessPolicy.Denial[1];
    assertNull(policy.tryAcquire(source(20), denial));
    assertEquals(AccessPolicy.Denial.BURST_3S, denial[0]);
  }

  @Test
  void blockedAddressIsRefusedBeforeAnyLimitIsConsulted() {
    BlockIpList blocked = BlockIpList.ofPermanent(Set.of("127.0.0.1"));
    AccessPolicy policy = new AccessPolicy(config(1000, 1000, 1000), blocked, new TestClock());
    AccessPolicy.Denial[] denial = new AccessPolicy.Denial[1];
    assertNull(policy.tryAcquire(source(1), denial));
    assertEquals(AccessPolicy.Denial.BLOCKED, denial[0]);
    // A refused-by-ban address never even gets a connection record.
    assertEquals(0, policy.activeConnections("127.0.0.1"));
  }

  @Test
  void disconnectMethodDoesNotBanWhileBlockMethodsDo() {
    AccessPolicy disconnect = new AccessPolicy(
        new AccessPolicy.Config(1, 10, 10, IDLE, BlockMethod.DISCONNECT), BlockIpList.empty(),
        new TestClock());
    assertFalse(disconnect.applyBlockMethod("10.0.0.1"), "mDisconnect bans nothing");
    assertTrue(disconnect.blockList().isEmpty());

    AccessPolicy temporary = new AccessPolicy(
        new AccessPolicy.Config(1, 10, 10, IDLE, BlockMethod.BLOCK), BlockIpList.empty(),
        new TestClock());
    assertTrue(temporary.applyBlockMethod("10.0.0.2"));
    assertEquals(java.util.List.of("10.0.0.2"), temporary.blockList().temporaryEntries());
    assertTrue(temporary.blockList().permanentEntries().isEmpty(), "temp ban is not persisted");

    AccessPolicy permanent = new AccessPolicy(
        new AccessPolicy.Config(1, 10, 10, IDLE, BlockMethod.BLOCK_LIST), BlockIpList.empty(),
        new TestClock());
    assertTrue(permanent.applyBlockMethod("10.0.0.3"));
    assertEquals(java.util.List.of("10.0.0.3"), permanent.blockList().permanentEntries());
  }

  @Test
  void fullyDisconnectingAnAddressForgetsItsBurstCounters() {
    // Delphi disposes the TSockaddr record when nCount drops to 0 (Main.pas:256-260), which
    // throws away the burst state with it.
    TestClock clock = new TestClock();
    AccessPolicy policy = new AccessPolicy(config(1000, 2, 1000), BlockIpList.empty(), clock);
    AccessPolicy.Permit first = policy.tryAcquire(source(1));
    AccessPolicy.Permit second = policy.tryAcquire(source(2));
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(2, policy.activeConnections("127.0.0.1"));
    first.close();
    second.close();
    assertEquals(0, policy.activeConnections("127.0.0.1"));
    // A fresh record: admitted unchecked again, exactly like the very first connection.
    assertNotNull(policy.tryAcquire(source(3)));
  }

  @Test
  void defaultsMatchTheShippedDelphiValues() {
    AccessPolicy.Config defaults = AccessPolicy.Config.defaults();
    assertEquals(50, defaults.maxActivePerIp(), "nMaxConnOfIPaddr (RunGate)");
    assertEquals(20, defaults.burstLimit1(), "nIPCountLimit1");
    assertEquals(40, defaults.burstLimit2(), "nIPCountLimit2");
    assertEquals(BlockMethod.DISCONNECT, defaults.blockMethod());
    assertEquals(Duration.ofSeconds(1), AccessPolicy.BURST_WINDOW_1);
    assertEquals(Duration.ofSeconds(3), AccessPolicy.BURST_WINDOW_2);
  }
}
