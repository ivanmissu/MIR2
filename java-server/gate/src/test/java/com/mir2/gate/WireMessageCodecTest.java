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
  void readsHeaderlessGameRunLoginPacket() throws Exception {
    String encoded = WireMessageCodec.encodeBody("**账号/战士/2345/120040918/9");
    byte[] frame = ("noise#4" + encoded + "!").getBytes(StandardCharsets.ISO_8859_1);

    RunLogin login = WireMessageCodec.readRunLogin(new ByteArrayInputStream(frame));

    assertEquals("账号", login.account());
    assertEquals("战士", login.characterName());
    assertEquals(2345, login.certification());
    assertEquals(120040918, login.clientVersion());
    assertEquals(9, login.loginCode());
  }

  @Test
  void writesLegacyGoodAndFailStatusFrames() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    WireMessageCodec.writeStatus(output, new GameOutbound.Status(true, 1234));
    WireMessageCodec.writeStatus(output, new GameOutbound.Status(false, 5678));
    assertEquals("#+GOOD/1234!#+FAIL/5678!", output.toString(StandardCharsets.US_ASCII));
  }

  @Test
  void malformedRunLoginPacketsAreRejected() {
    String noPrefix = WireMessageCodec.encodeBody("hero/warrior/2/120040918/9");
    String missingField = WireMessageCodec.encodeBody("**hero/warrior/2/120040918");
    assertThrows(java.io.IOException.class, () -> WireMessageCodec.readRunLogin(
        new ByteArrayInputStream(("#1" + noPrefix + "!").getBytes(StandardCharsets.ISO_8859_1))));
    assertThrows(java.io.IOException.class, () -> WireMessageCodec.readRunLogin(
        new ByteArrayInputStream(("#1" + missingField + "!").getBytes(StandardCharsets.ISO_8859_1))));
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
