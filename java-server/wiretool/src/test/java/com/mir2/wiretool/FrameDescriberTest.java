package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.gate.WireMessageCodec;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.MessageCodec;
import com.mir2.protocol.ProtocolConstants;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameDescriberTest {

  @Test
  void describesClientPacketsWithCmNameAndOldmodeBody() {
    byte[] payload = clientPayload(CM(ProtocolConstants.CM_SELECTSERVER, 0, 0, 0, 0), "MIR2");
    String description = FrameDescriber.describe(Recording.Kind.CLIENT_FRAME, payload);
    assertTrue(description.contains("C→S"), description);
    assertTrue(description.contains("CM_SELECTSERVER(" + ProtocolConstants.CM_SELECTSERVER + ")"),
        description);
    assertTrue(description.contains("body=\"MIR2"), description);
    assertTrue(FrameDescriber.parses(Recording.Kind.CLIENT_FRAME, payload));
  }

  @Test
  void describesServerPacketsWithSmName() {
    byte[] payload = serverPayload(
        new DefaultMessage(0, ProtocolConstants.SM_CERTIFICATION_SUCCESS, 0, 0, 0), "");
    String description = FrameDescriber.describe(Recording.Kind.SERVER_FRAME, payload);
    assertTrue(description.contains("S→C"), description);
    assertTrue(description.contains("SM_CERTIFICATION_SUCCESS"), description);
  }

  @Test
  void identifiesHeaderlessRunLoginAndMasksTheCertification() {
    String body = WireMessageCodec
        .encodeBody("**hero001/gm角色/123456789/120040918/9");
    byte[] payload = ('1' + body).getBytes(StandardCharsets.ISO_8859_1);
    String description = FrameDescriber.describe(Recording.Kind.CLIENT_FRAME, payload);
    assertTrue(description.contains("RunLogin account=hero001"), description);
    assertTrue(description.contains("character=gm角色"), description);
    assertTrue(description.contains("cert=***"), description);
    assertFalse(description.contains("123456789"), description);
    assertTrue(FrameDescriber.parses(Recording.Kind.CLIENT_FRAME, payload));
  }

  @Test
  void fallsBackToHexForUnparseableFrames() {
    byte[] garbage = new byte[] {(byte) 0xEE, 0x01, 0x02, (byte) 0xFF, 0x10};
    String description = FrameDescriber.describe(Recording.Kind.SERVER_FRAME, garbage);
    assertTrue(description.contains("unparsed 5B"), description);
    assertTrue(description.contains("hex=ee0102ff10"), description);
    assertFalse(FrameDescriber.parses(Recording.Kind.SERVER_FRAME, garbage));
  }

  @Test
  void describesNoiseAsPrintablePreview() {
    String description = FrameDescriber.describe(Recording.Kind.SERVER_NOISE,
        "hello".getBytes(StandardCharsets.ISO_8859_1));
    assertTrue(description.contains("noise 5B"), description);
    assertTrue(description.contains("\"hello\""), description);
  }

  @Test
  void identNamesComeFromProtocolConstants() {
    assertEquals("CM_PROTOCOL(" + ProtocolConstants.CM_PROTOCOL + ")",
        FrameDescriber.identName(true, ProtocolConstants.CM_PROTOCOL));
    assertTrue(FrameDescriber.identName(false, 4242).startsWith("IDENT_4242"));
  }

  /** Builds a legacy client packet payload: rotating sequence digit + header + encoded body. */
  private static byte[] clientPayload(DefaultMessage message, String plainBody) {
    String header = MessageCodec.encode(message);
    String body = plainBody.isEmpty() ? "" : WireMessageCodec.encodeBody(plainBody);
    return ('3' + header + body).getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] serverPayload(DefaultMessage message, String plainBody) {
    String header = MessageCodec.encode(message);
    String body = plainBody.isEmpty() ? "" : WireMessageCodec.encodeBody(plainBody);
    return (header + body).getBytes(StandardCharsets.ISO_8859_1);
  }

  private static DefaultMessage CM(int ident, int recog, int param, int tag, int series) {
    return new DefaultMessage(recog, ident, param, tag, series);
  }
}
