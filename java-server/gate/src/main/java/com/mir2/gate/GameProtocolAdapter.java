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

/** Converts the W03 movement subset between legacy CM_/SM_ packets and the world engine. */
public final class GameProtocolAdapter implements WorldEventSink {
  private static final int CHAR_DESC_BYTES = 8;

  private final WorldEngine world;
  private final int playerId;
  private final Consumer<GameOutbound> output;
  private final LongSupplier serverTick;

  public GameProtocolAdapter(WorldEngine world, int playerId, Consumer<GameOutbound> output) {
    this(world, playerId, output, () -> System.nanoTime() / 1_000_000L);
  }

  GameProtocolAdapter(
      WorldEngine world, int playerId, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this.world = Objects.requireNonNull(world, "world");
    if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
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
      Direction direction = Direction.fromCode(message.tag());
      Position position = unpackPosition(message.recog());
      switch (message.ident()) {
        case ProtocolConstants.CM_TURN -> reportExceptionalFailure(
            world.turn(playerId, position, direction));
        case ProtocolConstants.CM_WALK -> reportExceptionalFailure(
            world.move(playerId, position, direction, MovementKind.WALK));
        case ProtocolConstants.CM_RUN -> reportExceptionalFailure(
            world.move(playerId, position, direction, MovementKind.RUN));
        default -> throw new AssertionError("movement ident changed after validation");
      }
      return true;
    } catch (IllegalArgumentException error) {
      sendStatus(false);
      return true;
    }
  }

  @Override
  public void send(WorldEvent event) {
    Objects.requireNonNull(event, "event");
    switch (event) {
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
      case WorldEvent.ObjectMoved moved -> sendMovement(moved.object(), moved.movement());
      case WorldEvent.ObjectTurned turned -> sendObjectAction(ProtocolConstants.SM_TURN, turned.object());
      case WorldEvent.ObjectDisappeared disappeared -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_DISAPPEAR, disappeared.objectId(), 0, 0, 0, "")));
      default -> {
        // Map entry and appearance messages are implemented by the next W03 adapter step.
      }
    }
  }

  static Position unpackPosition(int packed) {
    return new Position(packed & 0xffff, (packed >>> 16) & 0xffff);
  }

  private void sendMovement(WorldObjectSnapshot object, MovementKind movement) {
    int ident = movement == MovementKind.RUN ? ProtocolConstants.SM_RUN : ProtocolConstants.SM_WALK;
    sendObjectAction(ident, object);
  }

  private void sendObjectAction(int ident, WorldObjectSnapshot object) {
    Position position = object.position();
    output.accept(new GameOutbound.Packet(packet(
        ident, object.id(), position.x(), position.y(), object.direction().code(), emptyCharDesc())));
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

  private static WirePacket packet(
      int ident, int recog, int param, int tag, int series, String encodedBody) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series), encodedBody);
  }

  private static String emptyCharDesc() {
    // TCharDesc = Feature: Integer + Status: Integer, both little-endian. Appearance work will
    // replace these placeholders with the real feature/status values.
    byte[] bytes = ByteBuffer.allocate(CHAR_DESC_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0).putInt(0).array();
    return new String(SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1);
  }
}
