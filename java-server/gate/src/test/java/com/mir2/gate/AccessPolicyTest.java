package com.mir2.gate;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
  @Test
  void limitsActiveConnectionsPerIpAcrossGates() {
    AccessPolicy policy = new AccessPolicy(new AccessPolicy.Config(1, 10, Duration.ofMinutes(1), Duration.ofSeconds(5)),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    InetSocketAddress source = new InetSocketAddress("127.0.0.1", 1000);
    try (AccessPolicy.Permit permit = policy.tryAcquire(source)) {
      assertNotNull(permit);
      assertNull(policy.tryAcquire(new InetSocketAddress("127.0.0.1", 1001)));
    }
    assertNotNull(policy.tryAcquire(source));
  }

  @Test
  void limitsConnectionAttemptsWithinSlidingWindow() {
    AccessPolicy policy = new AccessPolicy(new AccessPolicy.Config(10, 2, Duration.ofMinutes(1), Duration.ofSeconds(5)),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    InetSocketAddress source = new InetSocketAddress("127.0.0.1", 1000);
    try (AccessPolicy.Permit first = policy.tryAcquire(source);
         AccessPolicy.Permit second = policy.tryAcquire(source)) {
      assertNotNull(first);
      assertNotNull(second);
      assertNull(policy.tryAcquire(source));
    }
  }
}
