package com.mir2.gate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle wrapper. Protocol parsing stays at the boundary and never exposes raw streams to
 * game code.
 *
 * <p>When a {@link PacketSizePolicy} is attached (the game gate only), every socket read is
 * screened by Delphi's oversized-read guard before the bytes reach the decoder — see
 * {@link BurstGuardedInputStream}.
 */
public final class ClientConnection implements AutoCloseable {
  private final Socket socket;
  private final GateKind kind;
  private final PacketSizePolicy packetSizePolicy;
  /** Captured at accept time: the socket forgets its peer once closed. */
  private final SocketAddress remoteAddress;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AtomicBoolean oversizedRead = new AtomicBoolean(false);
  private volatile InputStream guarded;

  ClientConnection(Socket socket, GateKind kind) {
    this(socket, kind, null);
  }

  ClientConnection(Socket socket, GateKind kind, PacketSizePolicy packetSizePolicy) {
    this.socket = socket;
    this.kind = kind;
    this.packetSizePolicy = packetSizePolicy;
    this.remoteAddress = socket.getRemoteSocketAddress();
  }

  public SocketAddress remoteAddress() { return remoteAddress; }

  public GateKind kind() { return kind; }

  public InputStream input() throws IOException {
    if (packetSizePolicy == null) return socket.getInputStream();
    InputStream current = guarded;
    if (current == null) {
      synchronized (this) {
        current = guarded;
        if (current == null) {
          current = new BurstGuardedInputStream(
              socket.getInputStream(), packetSizePolicy, oversizedRead);
          guarded = current;
        }
      }
    }
    return current;
  }

  public OutputStream output() throws IOException { return socket.getOutputStream(); }

  public boolean isOpen() { return open.get() && !socket.isClosed(); }

  /**
   * True once a read tripped {@link PacketSizePolicy} with {@code bokickOverPacketSize} set.
   * The gate consults this after the handler returns, because the handler's own read loop
   * catches {@link IOException} and so the kick never propagates out of it on its own.
   */
  public boolean oversizedReadDetected() { return oversizedRead.get(); }

  @Override public void close() {
    if (open.getAndSet(false)) {
      try { socket.close(); } catch (IOException ignored) { }
    }
  }

  /**
   * Raised when a read trips {@link PacketSizePolicy} with {@code bokickOverPacketSize} set.
   * The gate catches it to apply {@code BlockMethod} before closing the socket. It is an
   * {@link IOException} so that the existing handler loops treat it as a terminal read error.
   */
  public static final class OversizedReadException extends IOException {
    private static final long serialVersionUID = 1L;

    OversizedReadException(int length, int frames) {
      super("client read exceeds gate limits: " + length + " bytes, " + frames + " frames");
    }
  }

  /**
   * Applies Delphi's read-burst guard (RunGate/Main.pas:978-1005) to a socket stream.
   *
   * <p>The original limits one {@code Socket.ReceiveText} — "the bytes TCP handed us at once" —
   * so the guard must sit at the socket boundary, before framing. That unit has no exact
   * equivalent in a blocking {@link InputStream}, where the decoder pulls a byte at a time; this
   * stream reconstructs it by screening each underlying {@code read} into the buffer that backs
   * those pulls, which is the same "one delivery from the kernel" quantum.
   *
   * <p>Behaviour on a trip is Delphi's: the offending buffer is <b>discarded</b> either way, and
   * the connection is additionally failed when the policy says kick. Discard-and-continue is
   * reproduced faithfully even though it silently truncates the client's stream.
   */
  static final class BurstGuardedInputStream extends FilterInputStream {
    private final PacketSizePolicy policy;
    private final AtomicBoolean kicked;
    private final byte[] buffer = new byte[8 * 1024];
    private int position;
    private int limit;

    BurstGuardedInputStream(InputStream in, PacketSizePolicy policy) {
      this(in, policy, new AtomicBoolean(false));
    }

    BurstGuardedInputStream(InputStream in, PacketSizePolicy policy, AtomicBoolean kicked) {
      super(in);
      this.policy = policy;
      this.kicked = kicked;
    }

    @Override public int read() throws IOException {
      if (!fill()) return -1;
      return buffer[position++] & 0xff;
    }

    @Override public int read(byte[] destination, int offset, int length) throws IOException {
      if (length == 0) return 0;
      if (!fill()) return -1;
      int available = Math.min(length, limit - position);
      System.arraycopy(buffer, position, destination, offset, available);
      position += available;
      return available;
    }

    @Override public int available() throws IOException {
      return (limit - position) + in.available();
    }

    /** Refills from the socket, screening each delivery; loops when a read is discarded. */
    private boolean fill() throws IOException {
      while (position >= limit) {
        int read = in.read(buffer, 0, buffer.length);
        if (read < 0) return false;
        position = 0;
        limit = read;
        int frames = PacketSizePolicy.countFrames(buffer, 0, read);
        switch (policy.evaluate(read, frames)) {
          case ACCEPT -> { }
          case DISCARD -> {
            // bokickOverPacketSize = False: Delphi exits the read handler, dropping these
            // bytes but leaving the connection open.
            position = 0;
            limit = 0;
          }
          case KICK -> {
            kicked.set(true);
            throw new OversizedReadException(read, frames);
          }
        }
      }
      return true;
    }
  }
}
