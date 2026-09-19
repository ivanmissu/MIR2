package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.AttackKind;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.GroundItem;
import com.mir2.world.MovementKind;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Converts the W03 movement, melee combat and map-entry subset between legacy packets and the world. */
public final class GameProtocolAdapter implements WorldEventSink {
  private final WorldEngine world;
  private final Consumer<GameOutbound> output;
  private final LongSupplier serverTick;
  private volatile int playerId;

  /** Creates an unbound adapter; its player id is bound by the first MapEntered callback. */
  public GameProtocolAdapter(WorldEngine world, Consumer<GameOutbound> output) {
    this(world, 0, output, () -> System.nanoTime() / 1_000_000L);
  }

  public GameProtocolAdapter(WorldEngine world, int playerId, Consumer<GameOutbound> output) {
    this(world, playerId, output, () -> System.nanoTime() / 1_000_000L);
  }

  GameProtocolAdapter(WorldEngine world, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this(world, 0, output, serverTick);
  }

  GameProtocolAdapter(
      WorldEngine world, int playerId, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this.world = Objects.requireNonNull(world, "world");
    if (playerId < 0) throw new IllegalArgumentException("playerId must not be negative");
    this.playerId = playerId;
    this.output = Objects.requireNonNull(output, "output");
    this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
  }

  /** Returns false for messages outside the movement, melee and pickup subset. */
  public boolean handle(WirePacket packet) {
    Objects.requireNonNull(packet, "packet");
    DefaultMessage message = packet.message();
    if (!isSupported(message.ident())) return false;
    try {
      int boundPlayer = requirePlayerId();
      switch (message.ident()) {
        case ProtocolConstants.CM_TURN -> reportExceptionalFailure(
            world.turn(boundPlayer, unpackPosition(message.recog()), Direction.fromCode(message.tag())));
        case ProtocolConstants.CM_WALK -> reportExceptionalFailure(world.move(boundPlayer,
            unpackPosition(message.recog()), Direction.fromCode(message.tag()), MovementKind.WALK));
        case ProtocolConstants.CM_RUN -> reportExceptionalFailure(world.move(boundPlayer,
            unpackPosition(message.recog()), Direction.fromCode(message.tag()), MovementKind.RUN));
        case ProtocolConstants.CM_HIT, ProtocolConstants.CM_HEAVYHIT, ProtocolConstants.CM_BIGHIT ->
            reportExceptionalFailure(world.attack(boundPlayer, unpackPosition(message.recog()),
                Direction.fromCode(message.tag()), attackKind(message.ident())));
        // The client sends its own cell in param/tag, not a packed Recog (ClMain.pas:CM_PICKUP).
        case ProtocolConstants.CM_PICKUP -> reportExceptionalFailure(
            world.pickUp(boundPlayer, new Position(message.param(), message.tag())));
        // Sent by the client right after SM_LOGON (ClMain.pas:3860) and whenever the bag
        // window needs a refresh; no +GOOD/+FAIL ack is part of the Delphi exchange.
        case ProtocolConstants.CM_QUERYBAGITEMS -> reportExceptionalFailure(
            world.playerState(boundPlayer)
                .thenAccept(state -> sendBagItems(state.backpack())));
        default -> throw new AssertionError("supported ident set changed after validation");
      }
      return true;
    } catch (IllegalArgumentException | IllegalStateException error) {
      sendStatus(false);
      return true;
    }
  }

  @Override
  public void send(WorldEvent event) {
    Objects.requireNonNull(event, "event");
    switch (event) {
      case WorldEvent.MapEntered entered -> sendMapEntered(entered);
      case WorldEvent.MoveAccepted accepted -> {
        if (accepted.player().id() == playerId) sendStatus(true);
      }
      case WorldEvent.MoveRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.TurnAccepted accepted -> {
        if (accepted.player().id() == playerId) sendStatus(true);
      }
      case WorldEvent.TurnRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.AttackAccepted accepted -> {
        if (accepted.attacker().id() == playerId) sendStatus(true);
      }
      case WorldEvent.AttackRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.PickupRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.ObjectAppeared appeared -> sendObjectAction(
          ProtocolConstants.SM_TURN, appeared.object());
      case WorldEvent.ObjectMoved moved -> sendMovement(moved.object(), moved.movement());
      case WorldEvent.ObjectTurned turned -> sendObjectAction(ProtocolConstants.SM_TURN, turned.object());
      case WorldEvent.ObjectAttacked attacked -> sendAttack(attacked);
      case WorldEvent.ObjectStruck struck -> sendStruck(struck);
      case WorldEvent.ObjectDied died -> sendDeath(died);
      case WorldEvent.HealthChanged changed -> sendHealth(changed.object());
      case WorldEvent.ExperienceGained gained -> sendExperience(gained);
      case WorldEvent.ItemAppeared appeared -> sendItemShow(appeared.item());
      case WorldEvent.ItemDisappeared disappeared -> sendItemHide(disappeared.item());
      case WorldEvent.ItemPickedUp pickedUp -> sendItemPickedUp(pickedUp);
      case WorldEvent.ObjectDisappeared disappeared -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_DISAPPEAR, disappeared.objectId(), 0, 0, 0, "")));
      default -> {
        // MapLeft has no client packet; socket closure already ends the local session.
      }
    }
  }

  static Position unpackPosition(int packed) {
    return new Position(packed & 0xffff, (packed >>> 16) & 0xffff);
  }

  private static boolean isSupported(int ident) {
    return ident == ProtocolConstants.CM_TURN
        || ident == ProtocolConstants.CM_WALK
        || ident == ProtocolConstants.CM_RUN
        || ident == ProtocolConstants.CM_HIT
        || ident == ProtocolConstants.CM_HEAVYHIT
        || ident == ProtocolConstants.CM_BIGHIT
        || ident == ProtocolConstants.CM_PICKUP
        || ident == ProtocolConstants.CM_QUERYBAGITEMS;
  }

  private static AttackKind attackKind(int ident) {
    return switch (ident) {
      case ProtocolConstants.CM_HEAVYHIT -> AttackKind.HEAVY_HIT;
      case ProtocolConstants.CM_BIGHIT -> AttackKind.BIG_HIT;
      default -> AttackKind.HIT;
    };
  }

  private static int attackIdent(AttackKind attack) {
    return switch (attack) {
      case HEAVY_HIT -> ProtocolConstants.SM_HEAVYHIT;
      case BIG_HIT -> ProtocolConstants.SM_BIGHIT;
      case HIT -> ProtocolConstants.SM_HIT;
    };
  }

  private void sendMapEntered(WorldEvent.MapEntered entered) {
    WorldObjectSnapshot player = entered.player();
    if (playerId == 0) playerId = player.id();
    if (playerId != player.id()) throw new IllegalStateException("adapter received another player's MapEntered");
    Position position = player.position();

    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_NEWMAP, player.id(),
        position.x(), position.y(), 0, WireMessageCodec.encodeBody(entered.map().id()))));
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_LOGON, player.id(),
        position.x(), position.y(), player.direction().code(), logonBody(player))));
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_MAPDESCRIPTION, -1,
        0, 0, 0, WireMessageCodec.encodeBody(entered.map().title()))));
    for (WorldObjectSnapshot visible : entered.visibleObjects()) {
      sendObjectAction(ProtocolConstants.SM_TURN, visible);
    }
    for (GroundItem item : entered.visibleItems()) {
      sendItemShow(item);
    }
  }

  private void sendMovement(WorldObjectSnapshot object, MovementKind movement) {
    int ident = movement == MovementKind.RUN ? ProtocolConstants.SM_RUN : ProtocolConstants.SM_WALK;
    sendObjectAction(ident, object);
  }

  private void sendObjectAction(int ident, WorldObjectSnapshot object) {
    Position position = object.position();
    String body = new CharacterDescription(object.feature(), object.status()).encode();
    output.accept(new GameOutbound.Packet(packet(
        ident, object.id(), position.x(), position.y(), object.direction().code(), body)));
  }

  /** {@code RM_HIT} carries only the attacker id, cell and direction; the body stays empty. */
  private void sendAttack(WorldEvent.ObjectAttacked attacked) {
    WorldObjectSnapshot attacker = attacked.attacker();
    Position position = attacker.position();
    output.accept(new GameOutbound.Packet(packet(attackIdent(attacked.attack()), attacker.id(),
        position.x(), position.y(), attacker.direction().code(), "")));
  }

  /** {@code SM_STRUCK}: recog=victim, param=HP, tag=MaxHP, series=damage, body=TMessageBodyWL. */
  private void sendStruck(WorldEvent.ObjectStruck struck) {
    WorldObjectSnapshot victim = struck.victim();
    String body = messageBodyWl(victim.feature(), victim.status(), struck.attackerId(), 0);
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_STRUCK, victim.id(),
        victim.ability().hp(), victim.ability().maxHp(), struck.damage(), body)));
  }

  /** {@code SM_DEATH}: recog=victim, param/tag=cell, series=damage, body=TCharDesc. */
  private void sendDeath(WorldEvent.ObjectDied died) {
    WorldObjectSnapshot victim = died.victim();
    Position position = victim.position();
    String body = new CharacterDescription(victim.feature(), victim.status()).encode();
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DEATH, victim.id(),
        position.x(), position.y(), victim.direction().code(), body)));
  }

  /** {@code SM_HEALTHSPELLCHANGED}: recog=object, param=HP, tag=MP, series=MaxHP, empty body. */
  private void sendHealth(WorldObjectSnapshot object) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_HEALTHSPELLCHANGED, object.id(),
        object.ability().hp(), object.ability().mp(), object.ability().maxHp(), "")));
  }

  /** {@code SM_WINEXP}: recog=total experience, param/tag=low/high word of the gained amount. */
  private void sendExperience(WorldEvent.ExperienceGained gained) {
    if (gained.playerId() != playerId) return;
    int total = (int) Math.min(gained.total(), Integer.MAX_VALUE);
    int amount = (int) Math.min(gained.gained(), Integer.MAX_VALUE);
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_WINEXP, total,
        amount & 0xffff, (amount >>> 16) & 0xffff, 0, "")));
  }

  /** {@code SM_ITEMSHOW}: recog=item, param/tag=cell, series=looks, body=item name. */
  private void sendItemShow(GroundItem item) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ITEMSHOW, item.id(),
        item.position().x(), item.position().y(), item.looks(),
        WireMessageCodec.encodeBody(item.name()))));
  }

  private void sendItemHide(GroundItem item) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ITEMHIDE, item.id(),
        item.position().x(), item.position().y(), 0, "")));
  }

  private void sendItemPickedUp(WorldEvent.ItemPickedUp pickedUp) {
    if (pickedUp.playerId() != playerId) return;
    sendStatus(true);
    // Full 76-byte TClientItem body, as SendAddItem does (ObjBase.pas:2145).
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ADDITEM, pickedUp.playerId(),
        0, 0, 1, ClientItemCodec.encode(pickedUp.backpackItem()))));
    sendItemHide(pickedUp.item());
  }

  /**
   * {@code SM_BAGITEMS}: recog=player, series=item count, body = '/'-terminated encoded
   * TClientItem blocks. ObjBase.pas:15952 stays silent for an empty bag, so we do too.
   */
  private void sendBagItems(List<BackpackItem> backpack) {
    if (backpack.isEmpty()) return;
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_BAGITEMS, requirePlayerId(),
        0, 0, backpack.size(), ClientItemCodec.encodeBag(backpack))));
  }

  private void sendStatus(boolean accepted) {
    long tick = Math.floorMod(serverTick.getAsLong(), (long) Integer.MAX_VALUE);
    output.accept(new GameOutbound.Status(accepted, tick));
  }

  private void reportExceptionalFailure(CompletableFuture<?> operation) {
    operation.whenComplete((ignored, error) -> {
      if (error != null) sendStatus(false);
    });
  }

  private int requirePlayerId() {
    int id = playerId;
    if (id <= 0) throw new IllegalStateException("GAME adapter has not entered the world");
    return id;
  }

  private static String logonBody(WorldObjectSnapshot player) {
    // TMessageBodyWL: feature, status, group/featureEx, reserved.
    return messageBodyWl(player.feature(), player.status(), 0, 0);
  }

  private static String messageBodyWl(int param1, int param2, int tag1, int tag2) {
    byte[] bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(param1).putInt(param2).putInt(tag1).putInt(tag2).array();
    return new String(SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1);
  }

  private static WirePacket packet(
      int ident, int recog, int param, int tag, int series, String encodedBody) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series), encodedBody);
  }
}
