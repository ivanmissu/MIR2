package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Direction;
import com.mir2.world.MovementKind;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Converts the W03 movement and minimum map-entry subset between legacy packets and the world. */
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

  GameProtocolAdapter(
      WorldEngine world, int playerId, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this.world = Objects.requireNonNull(world, "world");
    if (playerId < 0) throw new IllegalArgumentException("playerId must not be negative");
    this.playerId = playerId;
    this.output = Objects.requireNonNull(output, "output");
    this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
  }

  /** Returns false for messages outside the movement subset. */
  public boolean handle(WirePacket packet) {
    Objects.requireNonNull(packet, "packet");
    DefaultMessage message = packet.message();
    if (message.ident() != ProtocolConstants.CM_TURN
        && message.ident() != ProtocolConstants.CM_WALK
        && message.ident() != ProtocolConstants.CM_RUN) return false;
    try {
      int boundPlayer = requirePlayerId();
      Direction direction = Direction.fromCode(message.tag());
      Position position = unpackPosition(message.recog());
      switch (message.ident()) {
        case ProtocolConstants.CM_TURN -> reportExceptionalFailure(
            world.turn(boundPlayer, position, direction));
        case ProtocolConstants.CM_WALK -> reportExceptionalFailure(
            world.move(boundPlayer, position, direction, MovementKind.WALK));
        case ProtocolConstants.CM_RUN -> reportExceptionalFailure(
            world.move(boundPlayer, position, direction, MovementKind.RUN));
        default -> throw new AssertionError("movement ident changed after validation");
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
      case WorldEvent.ObjectAppeared appeared -> sendObjectAction(
          ProtocolConstants.SM_TURN, appeared.object());
      case WorldEvent.ObjectMoved moved -> sendMovement(moved.object(), moved.movement());
      case WorldEvent.ObjectTurned turned -> sendObjectAction(ProtocolConstants.SM_TURN, turned.object());
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
    byte[] bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(player.feature()).putInt(player.status()).putInt(0).putInt(0).array();
    return new String(SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1);
  }

  private static WirePacket packet(
      int ident, int recog, int param, int tag, int series, String encodedBody) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series), encodedBody);
  }
}
