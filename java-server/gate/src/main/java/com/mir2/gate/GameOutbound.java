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
    /** {@code '+LNG'} — 刺杀剑术 enabled: send {@code CM_LONGHIT} (ObjBase.pas:9043). */
    public static final Signal THRUSTING_ON = new Signal("+LNG");
    /** {@code '+ULNG'} — 刺杀剑术 disabled: back to {@code CM_HIT} (ObjBase.pas:9048). */
    public static final Signal THRUSTING_OFF = new Signal("+ULNG");
    /** {@code '+WID'} — 半月弯刀 enabled: send {@code CM_WIDEHIT} (ObjBase.pas:9066). */
    public static final Signal HALF_MOON_ON = new Signal("+WID");
    /** {@code '+FIR'} — 烈火剑法 armed: send {@code CM_FIREHIT} next swing (ObjBase.pas:9103). */
    public static final Signal FIRE_SWORD_ON = new Signal("+FIR");
    /** {@code '+UFIR'} — the 20 s 烈火剑法 charge lapsed (ObjBase.pas:6431). */
    public static final Signal FIRE_SWORD_OFF = new Signal("+UFIR");
    /** {@code '+UWID'} — 半月弯刀 disabled: back to {@code CM_HIT} (ObjBase.pas:9071). */
    public static final Signal HALF_MOON_OFF = new Signal("+UWID");

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
