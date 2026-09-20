package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.MessageCodec;
import com.mir2.protocol.ProtocolConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireToolMainTest {

  @TempDir
  Path tempDir;

  @Test
  void helpAndUnknownCommands() throws Exception {
    assertEquals(0, WireToolMain.run(new String[] {}));
    assertEquals(0, WireToolMain.run(new String[] {"--help"}));
    assertThrows(IllegalArgumentException.class,
        () -> WireToolMain.run(new String[] {"bogus"}));
  }

  @Test
  void recordRequiresEndpointArguments() {
    assertThrows(IllegalArgumentException.class,
        () -> WireToolMain.run(new String[] {"record", "--listen-port", "7000"}));
  }

  @Test
  void replayRequiresArgumentsAndRejectsBlankSkipLists() {
    assertThrows(IllegalArgumentException.class,
        () -> WireToolMain.run(new String[] {"replay", "--file", "x.mrec"}));
    assertThrows(IllegalArgumentException.class,
        () -> ReplayReport.parseSkipList("a,b"));
    assertThrows(IllegalArgumentException.class,
        () -> ReplayReport.parseSkipList("-1"));
  }

  @Test
  void inspectPrintsSummaryAndVerifyCountsUnparseableFrames() throws Exception {
    byte[] goodHeader = MessageCodec
        .encode(new DefaultMessage(0, ProtocolConstants.CM_PROTOCOL, 0, 0, 0))
        .getBytes(StandardCharsets.ISO_8859_1);
    Path clean = tempDir.resolve("clean.mrec");
    RecordingCodec.write(clean, new Recording(Map.of("label", "t"), List.of(
        new Recording.Event(Recording.Kind.CLIENT_FRAME, 0, goodHeader),
        new Recording.Event(Recording.Kind.SERVER_NOISE, 5, "misc".getBytes(StandardCharsets.ISO_8859_1)))));
    assertEquals(0, WireToolMain.run(new String[] {"inspect", "--file", clean.toString()}));
    assertEquals(0, WireToolMain.run(
        new String[] {"inspect", "--file", clean.toString(), "--verify"}));

    Path dirty = tempDir.resolve("dirty.mrec");
    RecordingCodec.write(dirty, new Recording(Map.of(), List.of(
        new Recording.Event(Recording.Kind.SERVER_FRAME, 0,
            new byte[] {(byte) 0xEE, (byte) 0xFF}))));
    assertEquals(1, WireToolMain.run(
        new String[] {"inspect", "--file", dirty.toString(), "--verify"}));
  }

  @Test
  void inspectReportsMissingFilesAsIoError() {
    assertThrows(IOException.class, () -> WireToolMain.run(
        new String[] {"inspect", "--file", tempDir.resolve("nope.mrec").toString()}));
  }
}
