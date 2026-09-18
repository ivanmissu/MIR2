package com.mir2.world;

import java.util.List;
import java.util.Objects;

/** Immutable outputs produced by the single world thread and consumed by a protocol adapter. */
public sealed interface WorldEvent
    permits WorldEvent.MapEntered,
        WorldEvent.MapLeft,
        WorldEvent.ObjectAppeared,
        WorldEvent.ObjectMoved,
        WorldEvent.ObjectTurned,
        WorldEvent.ObjectDisappeared,
        WorldEvent.MoveAccepted,
        WorldEvent.MoveRejected,
        WorldEvent.TurnAccepted,
        WorldEvent.TurnRejected {

  record MapEntered(
      WorldObjectSnapshot player,
      GameMap.MapInfo map,
      List<WorldObjectSnapshot> visibleObjects) implements WorldEvent {
    public MapEntered {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(map, "map");
      visibleObjects = List.copyOf(visibleObjects);
    }
  }

  record MapLeft(int playerId) implements WorldEvent {
    public MapLeft {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
    }
  }

  record ObjectAppeared(WorldObjectSnapshot object) implements WorldEvent {
    public ObjectAppeared {
      Objects.requireNonNull(object, "object");
    }
  }

  record ObjectMoved(
      WorldObjectSnapshot object,
      Position from,
      MovementKind movement) implements WorldEvent {
    public ObjectMoved {
      Objects.requireNonNull(object, "object");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(movement, "movement");
    }
  }

  record ObjectTurned(WorldObjectSnapshot object) implements WorldEvent {
    public ObjectTurned {
      Objects.requireNonNull(object, "object");
    }
  }

  record ObjectDisappeared(int objectId) implements WorldEvent {
    public ObjectDisappeared {
      if (objectId <= 0) throw new IllegalArgumentException("object id must be positive");
    }
  }

  record MoveAccepted(WorldObjectSnapshot player, Position from, MovementKind movement)
      implements WorldEvent {
    public MoveAccepted {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(movement, "movement");
    }
  }

  record MoveRejected(int playerId, Position attemptedTarget, MoveRejection reason)
      implements WorldEvent {
    public MoveRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(attemptedTarget, "attemptedTarget");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record TurnAccepted(WorldObjectSnapshot player) implements WorldEvent {
    public TurnAccepted {
      Objects.requireNonNull(player, "player");
    }
  }

  record TurnRejected(int playerId, TurnRejection reason) implements WorldEvent {
    public TurnRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(reason, "reason");
    }
  }

  enum MoveRejection {
    INVALID_TARGET,
    OUT_OF_BOUNDS,
    BLOCKED_TERRAIN,
    OCCUPIED
  }

  enum TurnRejection {
    POSITION_MISMATCH
  }
}
