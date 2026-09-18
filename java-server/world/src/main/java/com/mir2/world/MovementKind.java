package com.mir2.world;

/** Number of cells traversed by the two movement actions used in the W03 PoC. */
public enum MovementKind {
  WALK(1),
  RUN(2);

  private final int steps;

  MovementKind(int steps) {
    this.steps = steps;
  }

  public int steps() {
    return steps;
  }
}
