package com.mir2.gate;

import com.mir2.protocol.ByteStrings;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.MessageCodec;
import com.mir2.protocol.SixBitCodec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Codec for the legacy {@code #<sequence?><header><body>!} TCP packet format. */
public final class WireMessageCodec {
  public static final int WIRE_BYTES = 16;
  public static final int MAX_PACKET_BYTES = 8 * 1024;
  private static final byte START = '#';
  private static final byte END = '!';

  private WireMessageCodec() {}

  /** Writes a bare 16-byte encoded header. Kept for protocol-level tools and golden tests. */
  public static void write(OutputStream out, DefaultMessage message) throws IOException {
    byte[] wire = encodedHeader(message);
    out.write(wire);
    out.flush();
  }

  /** Reads a bare 16-byte encoded header. */
  public static DefaultMessage read(InputStream in) throws IOException {
    byte[] wire = in.readNBytes(WIRE_BYTES);
    if (wire.length == 0) return null;
    if (wire.length != WIRE_BYTES) throw new EOFException("truncated MIR2 message");
    return decodeHeader(wire);
  }

  /** Reads the next complete client packet, tolerating acknowledgements/noise before '#'. */
  public static WirePacket readPacket(InputStream in) throws IOException {
    int value;
    do {
      value = in.read();
      if (value < 0) return null;
    } while (value != START);

    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    while ((value = in.read()) >= 0 && value != END) {
      if (payload.size() >= MAX_PACKET_BYTES) throw new IOException("MIR2 packet exceeds 8192 bytes");
      payload.write(value);
    }
    if (value < 0) throw new EOFException("truncated MIR2 packet");

    byte[] bytes = payload.toByteArray();
    int offset = hasClientSequence(bytes) ? 1 : 0;
    if (bytes.length - offset < WIRE_BYTES) throw new IOException("MIR2 packet has no complete header");
    byte[] header = java.util.Arrays.copyOfRange(bytes, offset, offset + WIRE_BYTES);
    String body = new String(bytes, offset + WIRE_BYTES, bytes.length - offset - WIRE_BYTES,
        StandardCharsets.ISO_8859_1);
    return new WirePacket(decodeHeader(header), body);
  }

  /** Server responses do not need the client's rotating sequence digit. */
  public static void writePacket(OutputStream out, WirePacket packet) throws IOException {
    out.write(START);
    out.write(encodedHeader(packet.message()));
    out.write(packet.encodedBody().getBytes(StandardCharsets.ISO_8859_1));
    out.write(END);
    out.flush();
  }

  public static String encodeBody(String text) {
    return SixBitCodec.encodeString(ByteStrings.gbk(text));
  }

  public static String decodeBody(String encodedBody) {
    return ByteStrings.fromGbk(SixBitCodec.decodeString(encodedBody));
  }

  private static boolean hasClientSequence(byte[] payload) {
    // The Delphi client prefixes each request with a rotating ASCII digit 1..9.
    return payload.length > WIRE_BYTES && payload[0] >= '1' && payload[0] <= '9';
  }

  private static byte[] encodedHeader(DefaultMessage message) throws IOException {
    byte[] wire = MessageCodec.encode(message).getBytes(StandardCharsets.ISO_8859_1);
    if (wire.length != WIRE_BYTES) throw new IOException("unexpected encoded message length: " + wire.length);
    return wire;
  }

  private static DefaultMessage decodeHeader(byte[] wire) {
    return MessageCodec.decode(new String(wire, StandardCharsets.ISO_8859_1));
  }
}
