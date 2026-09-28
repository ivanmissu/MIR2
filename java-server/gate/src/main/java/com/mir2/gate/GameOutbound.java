package com.mir2.gate;

import java.util.Objects;

/** Transport-neutral outputs emitted by the GAME/world protocol adapter. */
public sealed interface GameOutbound
    permits GameOutbound.Status, GameOutbound.Signal, GameOutbound.Packet {
  /** Legacy action acknowledgement encoded on the wire as {@code #+GOOD/<tick>!} or FAIL. */
  record Status(boolean accepted, long serverTick) implements GameOutbound {
    public Status {
      if (serverTick < 0) throw new IllegalArgumentException("serverTick must not be negative");
    }
  }

  /**
   * A bare {@code SendSocket(nil, sTag)} frame: the same raw channel {@code +GOOD}/{@code +FAIL}
   * travel on, but without the trailing {@code /tick}. The 1.76 client routes any packet whose
   * first character is {@code '+'} through {@code DecodeMessagePacket}'s tag branch
   * (ClMain.pas:3620), where {@code PWR} arms {@code g_boNextTimePowerHit} and the
   * {@code LNG}/{@code WID}/{@code CRS}/… tags arm the other special-attack flags.
   */
  record Signal(String tag) implements GameOutbound {
    /** {@code '+PWR'} — 攻杀剑术 armed (ObjBase.pas:8867). */
    public static final Signal POWER_HIT = new Signal("+PWR");

    public Signal {
      Objects.requireNonNull(tag, "tag");
      if (!tag.startsWith("+")) throw new IllegalArgumentException("tag frames start with '+'");
    }
  }

  record Packet(WirePacket packet) implements GameOutbound {
    public Packet {
      Objects.requireNonNull(packet, "packet");
    }
  }
}
