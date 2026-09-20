package com.mir2.world;

import java.util.Objects;

/**
 * One door anchor cell of a {@link GameMap}, mirroring {@code pTDoorInfo} in
 * {@code Envir.pas}. The anchor is the cell whose {@code btDoorIndex} carries the
 * {@code $80} flag; all state lives in a {@link DoorStatus} shared by every anchor within
 * a 10-cell Chebyshev radius that carries the same door index, exactly like the
 * {@code TDoorStatus.nRefCount} links assembled by {@code TEnvirnoment.LoadMapData}.
 */
public final class DoorInfo {
  private final Position anchor;
  private final int index;
  private final DoorStatus status;

  /** A status instance is created once per index/radius group and shared by its anchors. */
  DoorInfo(Position anchor, int index, DoorStatus status) {
    Objects.requireNonNull(anchor, "anchor");
    if (index < 1 || index > 0x7f)
      throw new IllegalArgumentException("door index must be between 1 and 0x7f: " + index);
    this.anchor = anchor;
    this.index = index;
    this.status = Objects.requireNonNull(status, "status");
  }

  /**
   * Factory used by the map loader while scanning cells in column-major order: anchors
   * sharing an index with a previously created door inside the ±10 Chebyshev radius receive
   * that door's status instead of minting their own.
   */
  public static DoorInfo create(Position anchor, int index, java.util.Collection<DoorInfo> previous) {
    for (DoorInfo other : previous) {
      // TDoorStatus sharing: same index within a +/-10 Chebyshev radius of a prior anchor.
      if (other.index == index
          && Math.abs(other.anchor.x() - anchor.x()) <= 10
          && Math.abs(other.anchor.y() - anchor.y()) <= 10) {
        return new DoorInfo(anchor, index, other.status);
      }
    }
    return new DoorInfo(anchor, index, new DoorStatus());
  }

  public Position anchor() {
    return anchor;
  }

  public int index() {
    return index;
  }

  public DoorStatus status() {
    return status;
  }

  /** Mutable {@code TDoorStatus}: opened flag plus the open time used by the 5s auto-close. */
  public static final class DoorStatus {
    private boolean opened;
    private long openedAtMillis;

    public boolean opened() {
      return opened;
    }

    public long openedAtMillis() {
      return openedAtMillis;
    }

    void open(long openedAtMillis) {
      this.opened = true;
      this.openedAtMillis = openedAtMillis;
    }

    void close() {
      this.opened = false;
      this.openedAtMillis = 0;
    }
  }
}
