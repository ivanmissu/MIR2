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
        WorldEvent.TurnRejected,
        WorldEvent.ObjectAttacked,
        WorldEvent.ObjectStruck,
        WorldEvent.ObjectDied,
        WorldEvent.HealthChanged,
        WorldEvent.ExperienceGained,
        WorldEvent.AttackAccepted,
        WorldEvent.AttackRejected,
        WorldEvent.ItemAppeared,
        WorldEvent.ItemDisappeared,
        WorldEvent.ItemPickedUp,
        WorldEvent.PickupRejected,
        WorldEvent.DoorOpened,
        WorldEvent.DoorClosed,
        WorldEvent.PlayerMapChanged,
        WorldEvent.ChatHeard,
        WorldEvent.Whisper,
        WorldEvent.Shout,
        WorldEvent.SystemMessage,
        WorldEvent.DayChanging {

  record MapEntered(
      WorldObjectSnapshot player,
      GameMap.MapInfo map,
      List<WorldObjectSnapshot> visibleObjects,
      List<GroundItem> visibleItems,
      int dayBright) implements WorldEvent {
    public MapEntered {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(map, "map");
      visibleObjects = List.copyOf(visibleObjects);
      visibleItems = List.copyOf(visibleItems);
    }

    public MapEntered(
        WorldObjectSnapshot player, GameMap.MapInfo map, List<WorldObjectSnapshot> visibleObjects,
        List<GroundItem> visibleItems) {
      this(player, map, visibleObjects, visibleItems, 0);
    }

    public MapEntered(
        WorldObjectSnapshot player, GameMap.MapInfo map, List<WorldObjectSnapshot> visibleObjects) {
      this(player, map, visibleObjects, List.of(), 0);
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

  /** The attacker swung: observers render the animation regardless of whether anything was hit. */
  record ObjectAttacked(WorldObjectSnapshot attacker, AttackKind attack) implements WorldEvent {
    public ObjectAttacked {
      Objects.requireNonNull(attacker, "attacker");
      Objects.requireNonNull(attack, "attack");
    }
  }

  /** Damage landed on {@code victim}; {@code damage} may be zero when defence absorbed the hit. */
  record ObjectStruck(WorldObjectSnapshot victim, int attackerId, int damage) implements WorldEvent {
    public ObjectStruck {
      Objects.requireNonNull(victim, "victim");
      if (attackerId <= 0) throw new IllegalArgumentException("attacker id must be positive");
      if (damage < 0) throw new IllegalArgumentException("damage must not be negative");
    }
  }

  record ObjectDied(WorldObjectSnapshot victim, int killerId) implements WorldEvent {
    public ObjectDied {
      Objects.requireNonNull(victim, "victim");
      if (killerId < 0) throw new IllegalArgumentException("killer id must not be negative");
    }
  }

  record HealthChanged(WorldObjectSnapshot object) implements WorldEvent {
    public HealthChanged {
      Objects.requireNonNull(object, "object");
    }
  }

  record ExperienceGained(int playerId, long gained, long total) implements WorldEvent {
    public ExperienceGained {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      if (gained < 0 || total < 0) throw new IllegalArgumentException("experience must not be negative");
    }
  }

  record AttackAccepted(WorldObjectSnapshot attacker, AttackKind attack) implements WorldEvent {
    public AttackAccepted {
      Objects.requireNonNull(attacker, "attacker");
      Objects.requireNonNull(attack, "attack");
    }
  }

  record AttackRejected(int playerId, AttackRejection reason) implements WorldEvent {
    public AttackRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record ItemAppeared(GroundItem item) implements WorldEvent {
    public ItemAppeared {
      Objects.requireNonNull(item, "item");
    }
  }

  record ItemDisappeared(GroundItem item) implements WorldEvent {
    public ItemDisappeared {
      Objects.requireNonNull(item, "item");
    }
  }

  record ItemPickedUp(int playerId, GroundItem item, BackpackItem backpackItem) implements WorldEvent {
    public ItemPickedUp {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(item, "item");
      Objects.requireNonNull(backpackItem, "backpackItem");
    }
  }

  record PickupRejected(int playerId, PickupRejection reason) implements WorldEvent {
    public PickupRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * {@code UsrEngn.OpenDoor} broadcast: a door on {@code mapId} at the anchor cell
   * {@code position} opened; reaches every player inside the +/-12 client square.
   */
  record DoorOpened(String mapId, Position position) implements WorldEvent {
    public DoorOpened {
      if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
      Objects.requireNonNull(position, "position");
    }
  }

  /** {@code UsrEngn.CloseDoor} broadcast from the 500ms {@code ProcessMapDoor} sweep. */
  record DoorClosed(String mapId, Position position) implements WorldEvent {
    public DoorClosed {
      if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
      Objects.requireNonNull(position, "position");
    }
  }

  /**
   * The player stepped on a gate cell and entered the destination map, mirroring
   * {@code TBaseObject.EnterAnotherMap}: the adapter must clear the client's object scene
   * and announce the new map before the fresh visibility batches arrive.
   */
  record PlayerMapChanged(WorldObjectSnapshot player, GameMap.MapInfo map, int dayBright) implements WorldEvent {
    public PlayerMapChanged {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(map, "map");
    }

    public PlayerMapChanged(WorldObjectSnapshot player, GameMap.MapInfo map) {
      this(player, map, 0);
    }
  }

  /** Normal chat spoken by a player: reaches all observers inside the 12-cell sight square. */
  record ChatHeard(int speakerId, String speakerName, String message) implements WorldEvent {
    public ChatHeard {
      if (speakerId <= 0) throw new IllegalArgumentException("speaker id must be positive");
      Objects.requireNonNull(speakerName, "speakerName");
      Objects.requireNonNull(message, "message");
    }
  }

  /** Whisper (/target text) sent from sender to recipient; echoed to sender. */
  record Whisper(
      int senderId, String senderName, int recipientId, String recipientName, String message)
      implements WorldEvent {
    public Whisper {
      if (senderId <= 0 || recipientId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(senderName, "senderName");
      Objects.requireNonNull(recipientName, "recipientName");
      Objects.requireNonNull(message, "message");
    }
  }

  /** Shout (!text) broadcast to all players on the same map. */
  record Shout(int speakerId, String speakerName, String message) implements WorldEvent {
    public Shout {
      if (speakerId <= 0) throw new IllegalArgumentException("speaker id must be positive");
      Objects.requireNonNull(speakerName, "speakerName");
      Objects.requireNonNull(message, "message");
    }
  }

  /** System or hint message directed at a specific recipient. */
  record SystemMessage(int recipientId, String message) implements WorldEvent {
    public SystemMessage {
      if (recipientId < 0) throw new IllegalArgumentException("recipient id must not be negative");
      Objects.requireNonNull(message, "message");
    }
  }

  /** Day/night transition broadcast (SM_DAYCHANGING). */
  record DayChanging(int playerId, int gameTime, int dayBright) implements WorldEvent {
    public DayChanging {
      if (playerId < 0) throw new IllegalArgumentException("player id must not be negative");
    }
  }

  enum MoveRejection {
    INVALID_TARGET,
    OUT_OF_BOUNDS,
    BLOCKED_TERRAIN,
    OCCUPIED,
    ACTOR_DEAD,
    /** The gate cell fired but its destination was unavailable; Delphi rolls the move back. */
    GATE_TARGET_UNPASSABLE
  }

  enum TurnRejection {
    POSITION_MISMATCH,
    ACTOR_DEAD
  }

  enum AttackRejection {
    POSITION_MISMATCH,
    ACTOR_DEAD,
    TOO_FAST
  }

  enum PickupRejection {
    NO_ITEM,
    ACTOR_DEAD,
    BACKPACK_FULL
  }
}
