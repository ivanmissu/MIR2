package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireMessageCodecTest {
  @Test
  void framedPacketRoundTripsHeaderAndGbkBody() throws Exception {
    WirePacket expected = new WirePacket(new DefaultMessage(7, 2001, 2, 3, 4),
        WireMessageCodec.encodeBody("账号/口令"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    WireMessageCodec.writePacket(output, expected);

    WirePacket actual = WireMessageCodec.readPacket(new ByteArrayInputStream(output.toByteArray()));
    assertEquals(expected.message(), actual.message());
    assertEquals("账号/口令", WireMessageCodec.decodeBody(actual.encodedBody()));
  }

  @Test
  void readerStripsClientSequenceAndLeadingAcknowledgement() throws Exception {
    WirePacket packet = new WirePacket(new DefaultMessage(0, 100, 0, 0, 0),
        WireMessageCodec.encodeBody("hero/42"));
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    WireMessageCodec.writePacket(framed, packet);
    String serverFrame = framed.toString(StandardCharsets.ISO_8859_1);
    byte[] clientFrame = ("*#7" + serverFrame.substring(1)).getBytes(StandardCharsets.ISO_8859_1);

    WirePacket actual = WireMessageCodec.readPacket(new ByteArrayInputStream(clientFrame));
    assertEquals(packet.message(), actual.message());
    assertEquals("hero/42", WireMessageCodec.decodeBody(actual.encodedBody()));
  }

  @Test
  void truncatedAndOversizedPacketsAreRejected() {
    assertThrows(java.io.EOFException.class,
        () -> WireMessageCodec.readPacket(new ByteArrayInputStream("#abc".getBytes(StandardCharsets.US_ASCII))));
    byte[] oversized = ("#" + "A".repeat(WireMessageCodec.MAX_PACKET_BYTES + 1) + "!")
        .getBytes(StandardCharsets.US_ASCII);
    assertThrows(java.io.IOException.class,
        () -> WireMessageCodec.readPacket(new ByteArrayInputStream(oversized)));
  }
}
