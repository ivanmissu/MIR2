package com.mir2.shadowdiff;

import java.util.Objects;

/**
 * One server under comparison, addressed by its login gate. The select/game ports normally
 * come from the server's own advertised route (exactly what {@code mir2.exe} follows); the
 * overrides exist for NAT-ed test setups where the advertised host is not reachable.
 */
public record WireTarget(
    String label, String host, int loginPort, int selectPortOverride, int gamePortOverride) {

  public WireTarget {
    label = Objects.requireNonNull(label, "label");
    host = Objects.requireNonNull(host, "host");
    if (loginPort < 1 || loginPort > 0xffff)
      throw new IllegalArgumentException("login port out of range: " + loginPort);
    if (selectPortOverride < 0 || gamePortOverride < 0)
      throw new IllegalArgumentException("port overrides must be zero (off) or positive");
  }

  public static WireTarget of(String label, String host, int loginPort) {
    return new WireTarget(label, host, loginPort, 0, 0);
  }
}
