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
}
