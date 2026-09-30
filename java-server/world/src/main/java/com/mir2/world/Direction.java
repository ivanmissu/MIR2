package com.mir2.world;

import java.util.Arrays;

/** The eight direction values from {@code Common/Grobal2.pas} (DR_UP..DR_UPLEFT). */
public enum Direction {
  UP(0, 0, -1),
  UP_RIGHT(1, 1, -1),
  RIGHT(2, 1, 0),
  DOWN_RIGHT(3, 1, 1),
  DOWN(4, 0, 1),
  DOWN_LEFT(5, -1, 1),
  LEFT(6, -1, 0),
  UP_LEFT(7, -1, -1);

  private final int code;
  private final int dx;
  private final int dy;

  Direction(int code, int dx, int dy) {
    this.code = code;
    this.dx = dx;
    this.dy = dy;
  }

  public int code() {
    return code;
  }

  public int dx() {
    return dx;
  }

  public int dy() {
    return dy;
  }

  public static Direction fromCode(int code) {
    return Arrays.stream(values())
        .filter(direction -> direction.code == code)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid MIR2 direction: " + code));
  }

  public static Direction toward(Position source, Position target) {
    int dx = Integer.compare(target.x(), source.x());
    int dy = Integer.compare(target.y(), source.y());
    if (dx == 0 && dy == 0) throw new IllegalArgumentException("source and target are equal");
    return Arrays.stream(values())
        .filter(direction -> direction.dx == dx && direction.dy == dy)
        .findFirst()
        .orElseThrow();
  }

  /**
   * {@code GetNextDirection} (M2Share.pas:3488) — the direction Delphi actually walks from one
   * cell toward another. Mostly the per-axis sign like {@link #toward}, with two quirks kept
   * verbatim: when one axis is more than two cells away and the other axis is within one cell,
   * the result snaps to the cardinal; the Y snap window is asymmetric on purpose
   * ({@code (sY > dy - 1) and (sY <= dy + 1)} covers {@code sY == dy} and {@code sY == dy + 1}
   * but <em>not</em> {@code sY == dy - 1}). Identical endpoints answer {@link #DOWN} — the case
   * body's standing default.
   */
  public static Direction getNextDirection(Position source, Position target) {
    int flagX = Integer.compare(target.x(), source.x());
    if (Math.abs(source.y() - target.y()) > 2
        && source.x() >= target.x() - 1 && source.x() <= target.x() + 1) flagX = 0;
    int flagY = Integer.compare(target.y(), source.y());
    if (Math.abs(source.x() - target.x()) > 2
        && source.y() > target.y() - 1 && source.y() <= target.y() + 1) flagY = 0;
    if (flagX == 0 && flagY == -1) return UP;
    if (flagX == 1 && flagY == -1) return UP_RIGHT;
    if (flagX == 1 && flagY == 0) return RIGHT;
    if (flagX == 1 && flagY == 1) return DOWN_RIGHT;
    if (flagX == 0 && flagY == 1) return DOWN;
    if (flagX == -1 && flagY == 1) return DOWN_LEFT;
    if (flagX == -1 && flagY == 0) return LEFT;
    if (flagX == -1 && flagY == -1) return UP_LEFT;
    return DOWN;
  }

  /** {@code GetBackDir} (ObjBase.pas:2490) — the exact opposite facing for backstep/push. */
  public Direction opposite() {
    return fromCode((code + 4) % 8);
  }
}
