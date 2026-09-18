package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.MessageCodec;
import java.io.*;

/** Reads/writes one legacy 6-bit encoded TDefaultMessage from a TCP stream. */
public final class WireMessageCodec {
  public static final int WIRE_BYTES = 16;
  private WireMessageCodec() {}
  public static void write(OutputStream out, DefaultMessage message) throws IOException {
    byte[] wire=MessageCodec.encode(message).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    if(wire.length!=WIRE_BYTES) throw new IOException("unexpected encoded message length: "+wire.length);
    out.write(wire); out.flush();
  }
  public static DefaultMessage read(InputStream in) throws IOException {
    byte[] wire=in.readNBytes(WIRE_BYTES);
    if(wire.length==0)return null; if(wire.length!=WIRE_BYTES)throw new EOFException("truncated MIR2 message");
    return MessageCodec.decode(new String(wire,java.nio.charset.StandardCharsets.ISO_8859_1));
  }
}
