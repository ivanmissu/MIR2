package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameSplitterTest {

  @Test
  void singleFrameInOneChunk() {
    FrameSplitter splitter = new FrameSplitter();
    List<FrameSplitter.Segment> segments = splitter.feed(bytes("#abc!"));
    assertEquals(1, segments.size());
    assertTrue(segments.get(0).frame());
    assertArrayEquals(bytes("abc"), segments.get(0).bytes());
    assertTrue(splitter.finish().isEmpty());
  }

  @Test
  void frameSplitAcrossChunks() {
    FrameSplitter splitter = new FrameSplitter();
    assertTrue(splitter.feed(bytes("#ab")).isEmpty());
    assertTrue(splitter.feed(bytes("c")).isEmpty());
    List<FrameSplitter.Segment> segments = splitter.feed(bytes("d!"));
    assertEquals(1, segments.size());
    assertArrayEquals(bytes("abcd"), segments.get(0).bytes());
  }

  @Test
  void noiseAroundFrames() {
    FrameSplitter splitter = new FrameSplitter();
    List<FrameSplitter.Segment> segments = splitter.feed(bytes("xy#p!zw#q!"));
    assertEquals(4, segments.size());
    assertFalse(segments.get(0).frame());
    assertArrayEquals(bytes("xy"), segments.get(0).bytes());
    assertTrue(segments.get(1).frame());
    assertArrayEquals(bytes("p"), segments.get(1).bytes());
    assertFalse(segments.get(2).frame());
    assertArrayEquals(bytes("zw"), segments.get(2).bytes());
    assertTrue(segments.get(3).frame());
  }

  @Test
  void unterminatedTailBecomesNoiseOnFinish() {
    FrameSplitter splitter = new FrameSplitter();
    splitter.feed(bytes("#abc"));
    List<FrameSplitter.Segment> tail = splitter.finish();
    assertEquals(1, tail.size());
    assertFalse(tail.get(0).frame());
    assertArrayEquals(bytes("abc"), tail.get(0).bytes());
    assertTrue(splitter.finish().isEmpty());
  }

  @Test
  void oversizedPayloadIsDemotedToNoiseAndTheStreamStillReconstructsByteForByte() {
    FrameSplitter splitter = new FrameSplitter();
    byte[] big = new byte[FrameSplitter.MAX_PAYLOAD_BYTES + 8];
    big[0] = '#';
    for (int index = 1; index < big.length; index++) big[index] = 'x';
    byte[] tail = "!#ok!".getBytes(StandardCharsets.ISO_8859_1);
    byte[] stream = new byte[big.length + tail.length];
    System.arraycopy(big, 0, stream, 0, big.length);
    System.arraycopy(tail, 0, stream, big.length, tail.length);

    List<FrameSplitter.Segment> segments = new ArrayList<>(splitter.feed(stream));
    segments.addAll(splitter.finish());

    // Demotion emits the '#' + 8193 collected bytes as noise, then the leftover 'x' run plus
    // the stray '!' as a second noise segment, then the good frame.
    assertEquals(3, segments.size());
    assertFalse(segments.get(0).frame());
    assertEquals('#', segments.get(0).bytes()[0]);
    assertTrue(segments.get(2).frame());
    assertArrayEquals(bytes("ok"), segments.get(2).bytes());

    // The classified segments must rebuild the original stream byte-for-byte.
    assertArrayEquals(stream, rebuild(segments));
  }

  private static byte[] rebuild(List<FrameSplitter.Segment> segments) {
    java.io.ByteArrayOutputStream rebuilt = new java.io.ByteArrayOutputStream();
    for (FrameSplitter.Segment segment : segments) {
      if (segment.frame()) {
        rebuilt.write('#');
        rebuilt.write(segment.bytes(), 0, segment.bytes().length);
        rebuilt.write('!');
      } else {
        rebuilt.write(segment.bytes(), 0, segment.bytes().length);
      }
    }
    return rebuilt.toByteArray();
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }
}
