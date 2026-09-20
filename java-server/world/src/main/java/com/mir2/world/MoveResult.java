package com.mir2.world;

import java.util.Objects;
import java.util.Optional;

/** Completion result returned to command producers after a movement command has run on a tick. */
public record MoveResult(
    boolean moved,
    WorldObjectSnapshot player,
    WorldEvent.MoveRejection rejection) {

  public MoveResult {
    Objects.requireNonNull(player, "player");
    if (moved == (rejection != null))
      throw new IllegalArgumentException("a move must have either success or a rejection");
  }

  public Optional<WorldEvent.MoveRejection> rejectionReason() {
    return Optional.ofNullable(rejection);
  }

  static MoveResult accepted(WorldObjectSnapshot player) {
    return new MoveResult(true, player, null);
  }

  static MoveResult rejected(WorldObjectSnapshot player, WorldEvent.MoveRejection reason) {
    return new MoveResult(false, player, reason);
  }
}
