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
        WorldEvent.DayChanging,
        WorldEvent.ItemEquipped,
        WorldEvent.EquipRejected,
        WorldEvent.ItemUnequipped,
        WorldEvent.UnequipRejected,
        WorldEvent.ItemUsed,
        WorldEvent.UseItemRejected,
        WorldEvent.ItemDropped,
        WorldEvent.DropItemRejected,
        WorldEvent.WeightChanged,
        WorldEvent.AbilityChanged,
        WorldEvent.EquipmentSent {

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

  /**
   * A bag item moved into an equipment slot. {@code feature}/{@code featureEx} carry the
   * recomputed appearance that {@code SM_TAKEON_OK} echoes back as recog/param.
   */
  record ItemEquipped(
      int playerId, EquipmentSlot slot, BackpackItem item, int feature, int featureEx)
      implements WorldEvent {
    public ItemEquipped {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(item, "item");
    }
  }

  /** {@code SM_TAKEON_FAIL}; {@code reason} is the Delphi n18 code carried in recog. */
  record EquipRejected(int playerId, int reason, EquipRejection detail) implements WorldEvent {
    public EquipRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /** An equipped item returned to the bag. */
  record ItemUnequipped(
      int playerId, EquipmentSlot slot, BackpackItem item, int feature, int featureEx)
      implements WorldEvent {
    public ItemUnequipped {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(item, "item");
    }
  }

  /** {@code SM_TAKEOFF_FAIL}; {@code reason} is the Delphi n10 code carried in recog. */
  record UnequipRejected(int playerId, int reason, UnequipRejection detail) implements WorldEvent {
    public UnequipRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /** A consumable was eaten and removed from the bag ({@code SM_EAT_OK}). */
  record ItemUsed(int playerId, BackpackItem item, int restoredHp, int restoredMp)
      implements WorldEvent {
    public ItemUsed {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(item, "item");
    }
  }

  /** {@code SM_EAT_FAIL}: the client puts the item back into its bag slot. */
  record UseItemRejected(int playerId, UseItemRejection reason) implements WorldEvent {
    public UseItemRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** A bag item was thrown on the ground ({@code SM_DROPITEM_SUCCESS}). */
  record ItemDropped(int playerId, BackpackItem item, GroundItem ground) implements WorldEvent {
    public ItemDropped {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(item, "item");
      Objects.requireNonNull(ground, "ground");
    }
  }

  /** {@code SM_DROPITEM_FAIL}: recog carries the MakeIndex, the body the item name. */
  record DropItemRejected(int playerId, String itemName, int makeIndex, DropRejection reason)
      implements WorldEvent {
    public DropItemRejected {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(itemName, "itemName");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** {@code SM_WEIGHTCHANGED}: recog=Weight, param=WearWeight, tag=HandWeight. */
  record WeightChanged(int playerId, int weight, int wearWeight, int handWeight)
      implements WorldEvent {
    public WeightChanged {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
    }
  }

  /** {@code RM_ABILITY}: the recalculated ability after equipment changed. */
  record AbilityChanged(int playerId, Ability ability) implements WorldEvent {
    public AbilityChanged {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(ability, "ability");
    }
  }

  /** {@code SM_SENDUSEITEMS}: the whole worn set, sent after login and on reconnect. */
  record EquipmentSent(int playerId, Equipment equipment) implements WorldEvent {
    public EquipmentSent {
      if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
      Objects.requireNonNull(equipment, "equipment");
    }
  }

  /** Why a take-on was refused, beyond the numeric code the client receives. */
  enum EquipRejection {
    /** No bag item with that MakeIndex and name. */
    NO_SUCH_ITEM,
    /** The StdMode may not live in the requested slot ({@code CheckUserItems}). */
    SLOT_MISMATCH,
    /** {@code CheckTakeOnItems} refused: gender, weight or Need requirement. */
    REQUIREMENT_NOT_MET,
    /** The item already in the slot is locked and cannot be removed. */
    CANNOT_TAKE_OFF_EXISTING,
    /** Slot index outside 0..12. */
    INVALID_SLOT,
    ACTOR_DEAD
  }

  /** Why a take-off was refused. */
  enum UnequipRejection {
    /** Trading, or a slot index outside 0..12 (Delphi n10 = -1). */
    BUSY_OR_INVALID_SLOT,
    /** The slot is empty, or the MakeIndex/name did not match (n10 = -2). */
    SLOT_EMPTY,
    /** The bag has no free space (n10 = -3). */
    BACKPACK_FULL,
    /** The item is bound or locked (n10 = -4). */
    CANNOT_TAKE_OFF
  }

  enum UseItemRejection {
    NO_SUCH_ITEM,
    ACTOR_DEAD,
    /** The map forbids potions ({@code Flag.boNODRUG}). */
    MAP_FORBIDS_DRUGS,
    /** The StdMode is not something {@code ClientUseItems} knows how to consume. */
    NOT_CONSUMABLE
  }

  enum DropRejection {
    NO_SUCH_ITEM,
    /** {@code boInSafeDisableDrop} and the player stands in a safe zone. */
    SAFE_ZONE,
    /** The map sets {@code Flag.boNOTHROWITEM}. */
    MAP_FORBIDS_DROP,
    /** No free cell near the player to hold the item. */
    NO_SPACE,
    ACTOR_DEAD
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
