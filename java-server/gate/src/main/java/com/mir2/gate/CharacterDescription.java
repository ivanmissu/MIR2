package com.mir2.gate;

import com.mir2.protocol.SixBitCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Legacy packed TCharDesc: Feature(Integer) + Status(Integer), little-endian then 6-bit encoded. */
public record CharacterDescription(int feature, int status) {
  public static final int BYTES = 8;

  public String encode() {
    byte[] bytes = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(feature).putInt(status).array();
    return new String(SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1);
  }

  public static CharacterDescription decode(String encoded) {
    byte[] bytes = SixBitCodec.decodeString(encoded);
    if (bytes.length != BYTES) throw new IllegalArgumentException("TCharDesc must decode to 8 bytes");
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    return new CharacterDescription(buffer.getInt(), buffer.getInt());
  }
}
