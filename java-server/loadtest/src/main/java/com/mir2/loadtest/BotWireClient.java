package com.mir2.loadtest;

import com.mir2.gate.WireMessageCodec;
import com.mir2.gate.WirePacket;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.MessageCodec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * Minimal legacy-wire client used by the bot swarm.
 *
 * <p>It speaks exactly what {@code mir2.exe} speaks on the gate: {@code #<sequence><header>
 * <body>!} frames with the Delphi client's rotating 1..9 sequence digit, the headerless
 * {@code **account/character/cert/version/code} RunLogin first packet on the GAME gate, and
 * raw {@code +GOOD/<tick>} / {@code +FAIL/<tick>} action acknowledgements.
 */
final class BotWireClient implements AutoCloseable {
  private static final int MAX_FRAME_BYTES = 8 * 1024;

  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;
  private int sequence;

  private BotWireClient(Socket socket) throws IOException {
    this.socket = socket;
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();
  }

  /** Connects with a bounded connect timeout; the socket starts without a read timeout. */
  static BotWireClient connect(String host, int port, Duration connectTimeout) throws IOException {
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
    } catch (IOException error) {
      try {
        socket.close();
      } catch (IOException suppressed) {
        error.addSuppressed(suppressed);
      }
      throw error;
    }
    return new BotWireClient(socket);
  }

  /** Applies (or clears with zero) a read timeout, used for synchronous login-phase reads. */
  void setReadTimeout(Duration timeout) {
    try {
      socket.setSoTimeout((int) timeout.toMillis());
    } catch (IOException ignored) {
      // A socket that refuses the option still works; reads simply block longer.
    }
  }

  /** Sends one framed request, prefixed with the rotating sequence digit like ClMain.pas. */
  void sendPacket(DefaultMessage message, String plainBody) throws IOException {
    byte[] header = MessageCodec.encode(message).getBytes(StandardCharsets.ISO_8859_1);
    byte[] body = plainBody == null || plainBody.isEmpty() ? new byte[0]
        : WireMessageCodec.encodeBody(plainBody).getBytes(StandardCharsets.ISO_8859_1);
    sequence = sequence % 9 + 1;
    synchronized (output) {
      output.write('#');
      output.write('0' + sequence);
      output.write(header);
      output.write(body);
      output.write('!');
      output.flush();
    }
  }

  /**
   * Sends the GAME gate's headerless first packet:
   * {@code #<sequence>EncodeString("**account/character/cert/version/code")!}.
   */
  void sendRunLogin(String account, String character, long certification,
      int clientVersion, int loginCode) throws IOException {
    String body = WireMessageCodec.encodeBody(
        "**" + account + "/" + character + "/" + certification
            + "/" + clientVersion + "/" + loginCode);
    sequence = sequence % 9 + 1;
    synchronized (output) {
      output.write('#');
      output.write('0' + sequence);
      output.write(body.getBytes(StandardCharsets.ISO_8859_1));
      output.write('!');
      output.flush();
    }
  }

  /**
   * Reads the next frame payload (bytes between '#' and '!'); {@code null} on orderly EOF.
   * A read timeout surfaces as {@link java.net.SocketTimeoutException}.
   */
  byte[] readFrame() throws IOException {
    int value;
    do {
      value = input.read();
      if (value < 0) return null;
    } while (value != '#');

    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    while ((value = input.read()) >= 0 && value != '!') {
      if (payload.size() >= MAX_FRAME_BYTES) throw new IOException("frame exceeds 8192 bytes");
      payload.write(value);
    }
    if (value < 0) throw new EOFException("truncated frame");
    return payload.toByteArray();
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Closing is best-effort; the caller treats the bot as gone either way.
    }
  }

  /** Parses a server frame into a {@link WirePacket}, tolerating an echoed sequence digit. */
  static WirePacket parsePacket(byte[] frame) throws IOException {
    int offset = hasSequence(frame) ? 1 : 0;
    if (frame.length - offset < 16) throw new IOException("frame shorter than a message header");
    byte[] header = Arrays.copyOfRange(frame, offset, offset + 16);
    String body = new String(frame, offset + 16, frame.length - offset - 16,
        StandardCharsets.ISO_8859_1);
    return new WirePacket(MessageCodec.decode(new String(header, StandardCharsets.ISO_8859_1)),
        body);
  }

  /** Action acknowledgements are raw {@code +GOOD/<tick>} / {@code +FAIL/<tick>} payloads. */
  static boolean isStatusFrame(byte[] frame) {
    return frame.length > 0 && frame[0] == '+';
  }

  /** {@code true} for {@code +GOOD}, {@code false} for {@code +FAIL}. */
  static boolean statusAccepted(byte[] frame) {
    return frame.length >= 2 && frame[1] == 'G';
  }

  private static boolean hasSequence(byte[] frame) {
    return frame.length > 0 && frame[0] >= '1' && frame[0] <= '9';
  }
}
