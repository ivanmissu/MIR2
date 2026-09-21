package com.mir2.shadowdiff;

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
 * Blocking legacy-wire client for the shadow harness: {@code #<sequence><header><body>!}
 * frames with the rotating 1..9 sequence digit, the headerless RunLogin first packet on the
 * GAME gate and raw {@code +GOOD/+FAIL} acknowledgements — the same dialect
 * {@code loadtest.BotWireClient} speaks, restated here because that class is intentionally
 * package-private to the swarm.
 *
 * <p>The harness reads synchronously on one thread: every op sends, then drains answers
 * until the socket stays quiet for the settle window. That keeps the captured
 * packet-per-op buckets deterministic without a reader thread.
 */
final class ShadowWireClient implements AutoCloseable {
  private static final int MAX_FRAME_BYTES = 8 * 1024;

  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;
  private int sequence;

  private ShadowWireClient(Socket socket) throws IOException {
    this.socket = socket;
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();
  }

  static ShadowWireClient connect(String host, int port, Duration connectTimeout)
      throws IOException {
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
    return new ShadowWireClient(socket);
  }

  /** Applies a read timeout; timed-out reads surface as {@link java.net.SocketTimeoutException}. */
  void setReadTimeout(Duration timeout) throws IOException {
    socket.setSoTimeout((int) timeout.toMillis());
  }

  /** Sends one framed request with the rotating sequence digit, like ClMain.pas. */
  void sendPacket(DefaultMessage message, String plainBody) throws IOException {
    byte[] header = MessageCodec.encode(message).getBytes(StandardCharsets.ISO_8859_1);
    byte[] body = plainBody == null || plainBody.isEmpty() ? new byte[0]
        : WireMessageCodec.encodeBody(plainBody).getBytes(StandardCharsets.ISO_8859_1);
    sequence = sequence % 9 + 1;
    output.write('#');
    output.write('0' + sequence);
    output.write(header);
    output.write(body);
    output.write('!');
    output.flush();
  }

  /** The GAME gate's headerless {@code **account/character/cert/version/code} first packet. */
  void sendRunLogin(String account, String character, long certification,
      int clientVersion, int loginCode) throws IOException {
    String body = WireMessageCodec.encodeBody(
        "**" + account + "/" + character + "/" + certification
            + "/" + clientVersion + "/" + loginCode);
    sequence = sequence % 9 + 1;
    output.write('#');
    output.write('0' + sequence);
    output.write(body.getBytes(StandardCharsets.ISO_8859_1));
    output.write('!');
    output.flush();
  }

  /** Next frame payload between '#' and '!'; {@code null} on orderly EOF. */
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
      // Best-effort close; the session treats the connection as gone either way.
    }
  }

  static WirePacket parsePacket(byte[] frame) throws IOException {
    int offset = hasSequence(frame) ? 1 : 0;
    if (frame.length - offset < 16) throw new IOException("frame shorter than a message header");
    byte[] header = Arrays.copyOfRange(frame, offset, offset + 16);
    String body = new String(frame, offset + 16, frame.length - offset - 16,
        StandardCharsets.ISO_8859_1);
    return new WirePacket(MessageCodec.decode(new String(header, StandardCharsets.ISO_8859_1)),
        body);
  }

  static boolean isStatusFrame(byte[] frame) {
    return frame.length > 0 && frame[0] == '+';
  }

  static boolean statusAccepted(byte[] frame) {
    return frame.length >= 2 && frame[1] == 'G';
  }

  private static boolean hasSequence(byte[] frame) {
    return frame.length > 0 && frame[0] >= '1' && frame[0] <= '9';
  }
}
