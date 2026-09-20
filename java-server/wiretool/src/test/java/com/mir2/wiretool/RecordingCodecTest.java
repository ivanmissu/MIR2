package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingCodecTest {

  @TempDir
  Path tempDir;

  @Test
  void roundTripPreservesMetadataAndEvents() throws IOException {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("tool", "mir2-wiretool");
    metadata.put("target", "127.0.0.1:7000");
    metadata.put("tricky", "weird; value=with\nseparators");
    List<Recording.Event> events = List.of(
        event(Recording.Kind.MARKER, 0, "session-open"),
        event(Recording.Kind.CLIENT_FRAME, 5, new byte[] {1, 2, 3}),
        event(Recording.Kind.SERVER_FRAME, 7, new byte[] {9, 8, 7, 6}),
        event(Recording.Kind.CLIENT_NOISE, 9, new byte[] {'x'}),
        event(Recording.Kind.SERVER_NOISE, 11, new byte[0]),
        event(Recording.Kind.MARKER, 12, "session-close"));
    Path file = tempDir.resolve("capture.mrec");

    RecordingCodec.write(file, new Recording(metadata, events));
    Recording restored = RecordingCodec.read(file);

    assertEquals("mir2-wiretool", restored.metadata().get("tool"));
    assertEquals("127.0.0.1:7000", restored.metadata().get("target"));
    assertEquals(restored.metadata().get("tricky"),
        "weird, value~with separators"); // sanitized, single line
    assertEquals(events.size(), restored.events().size());
    for (int index = 0; index < events.size(); index++) {
      assertEquals(events.get(index).kind(), restored.events().get(index).kind());
      assertEquals(events.get(index).millis(), restored.events().get(index).millis());
      assertArrayEquals(events.get(index).payload(), restored.events().get(index).payload());
    }
    assertEquals(12, restored.durationMillis());
    assertEquals(1, restored.serverFrames().size());
    assertEquals(1, restored.clientEvents().stream().filter(e -> e.kind().frame()).count());
  }

  @Test
  void writerAppendsAreVisibleAfterClose() throws IOException {
    Path file = tempDir.resolve("append.mrec");
    try (RecordingCodec.Writer writer =
        RecordingCodec.openForWrite(file, Map.of("label", "t"))) {
      writer.append(Recording.Kind.CLIENT_FRAME, 3, "abc".getBytes(StandardCharsets.ISO_8859_1));
      writer.marker(4, "hello");
    }
    Recording restored = RecordingCodec.read(file);
    assertEquals(2, restored.events().size());
    assertEquals("t", restored.metadata().get("label"));
    assertEquals("hello", new String(restored.events().get(1).payload(),
        StandardCharsets.UTF_8));
  }

  @Test
  void rejectsBadMagicAndTruncatedHeader() throws IOException {
    Path bad = tempDir.resolve("bad.mrec");
    Files.write(bad, "NOPE".getBytes(StandardCharsets.ISO_8859_1));
    assertThrows(IOException.class, () -> RecordingCodec.read(bad));

    Path empty = tempDir.resolve("empty.mrec");
    Files.write(empty, new byte[0]);
    assertThrows(IOException.class, () -> RecordingCodec.read(empty));
  }

  @Test
  void truncatedTrailingRecordKeepsReadablePrefix() throws IOException {
    Path file = tempDir.resolve("truncated.mrec");
    RecordingCodec.write(file, new Recording(Map.of("a", "b"),
        List.of(event(Recording.Kind.CLIENT_FRAME, 1, new byte[] {42}))));

    // Append a dangling partial record: header declares 8 payload bytes, only 2 on disk.
    byte[] raw = Files.readAllBytes(file);
    byte[] withDanglingTail = new byte[raw.length + 9 + 2];
    System.arraycopy(raw, 0, withDanglingTail, 0, raw.length);
    int cursor = raw.length;
    withDanglingTail[cursor] = '<';                       // tag
    withDanglingTail[cursor + 1] = 5;                     // millis = 5 (u32 LE)
    withDanglingTail[cursor + 5] = 8;                     // declared length = 8 (u32 LE)
    withDanglingTail[cursor + 9] = 1;                     // …but only 2 payload bytes on disk
    Files.write(file, withDanglingTail);

    Recording restored = RecordingCodec.read(file);
    assertEquals(1, restored.events().size());
    assertEquals(Recording.Kind.CLIENT_FRAME, restored.events().get(0).kind());
  }

  private static Recording.Event event(Recording.Kind kind, long millis, byte[] payload) {
    return new Recording.Event(kind, millis, payload);
  }

  private static Recording.Event event(Recording.Kind kind, long millis, String text) {
    return new Recording.Event(kind, millis, text.getBytes(StandardCharsets.UTF_8));
  }
}
