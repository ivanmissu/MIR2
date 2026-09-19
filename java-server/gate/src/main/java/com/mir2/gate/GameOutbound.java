package com.mir2.gate;

import java.util.Objects;

/** Transport-neutral outputs emitted by the GAME/world protocol adapter. */
public sealed interface GameOutbound permits GameOutbound.Status, GameOutbound.Packet {
  /** Legacy action acknowledgement encoded on the wire as {@code #+GOOD/<tick>!} or FAIL. */
  record Status(boolean accepted, long serverTick) implements GameOutbound {
    public Status {
      if (serverTick < 0) throw new IllegalArgumentException("serverTick must not be negative");
    }
  }

  record Packet(WirePacket packet) implements GameOutbound {
    public Packet {
      Objects.requireNonNull(packet, "packet");
    }
  }
}
