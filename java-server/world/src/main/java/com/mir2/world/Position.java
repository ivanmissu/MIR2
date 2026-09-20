package com.mir2.world;

/** Integer map coordinates. Bounds are owned by {@link GameMap}, not this value type. */
public record Position(int x, int y) {
  public Position translate(Direction direction, int steps) {
    if (steps < 0) throw new IllegalArgumentException("steps must not be negative");
    return new Position(x + direction.dx() * steps, y + direction.dy() * steps);
  }

  /** MIR2 visibility uses a square around an object, i.e. Chebyshev distance. */
  public int distanceTo(Position other) {
    return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
  }
}
