package com.mir2.wiretool;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Incrementally classifies a byte stream into legacy {@code #…!} frames and inter-frame noise,
 * mirroring how {@code WireMessageCodec.readPacket} scans for {@code '#'} and collects until
 * {@code '!'}. The delimiters themselves never leave a trace: well-formed traffic is recorded
 * as frame payloads only, anything else surfaces as noise so the stream stays reconstructible.
 *
 * <p>A candidate frame whose payload grows beyond {@code MAX_PAYLOAD_BYTES} is demoted to
 * noise (opening {@code '#'} plus collected bytes, then the rest of the stream is re-scanned),
 * which keeps a malformed peer from exhausting memory.
 */
final class FrameSplitter {
  static final int MAX_PAYLOAD_BYTES = 8 * 1024;

  private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
  private boolean insideFrame;
  private boolean closed;

  /** One classified segment: {@code frame=true} → payload of a complete {@code #…!} frame. */
  record Segment(boolean frame, byte[] bytes) {
    Segment {
      Objects.requireNonNull(bytes, "bytes");
    }
  }

  private final List<Segment> emitted = new ArrayList<>();

  /** Classifies a complete buffer in one call. */
  List<Segment> feed(byte[] bytes) {
    return feed(bytes, 0, bytes.length);
  }

  /** Classifies new stream bytes; returns the segments completed by this call. */
  List<Segment> feed(byte[] bytes, int offset, int length) {
    if (closed) throw new IllegalStateException("splitter is closed");
    emitted.clear();
    for (int index = offset; index < offset + length; index++) {
      byte value = bytes[index];
      if (!insideFrame) {
        if (value == '#') {
          flushNoise();
          insideFrame = true;
        } else {
          pending.write(value);
        }
      } else if (value == '!') {
        emit(true, pending.toByteArray());
        pending.reset();
        insideFrame = false;
      } else {
        pending.write(value);
        if (pending.size() > MAX_PAYLOAD_BYTES) {
          // Oversized "frame": demote to noise but keep the opening '#' so the raw stream
          // stays byte-for-byte reconstructible; frame detection re-arms at the next '#'.
          byte[] collected = pending.toByteArray();
          byte[] demoted = new byte[collected.length + 1];
          demoted[0] = '#';
          System.arraycopy(collected, 0, demoted, 1, collected.length);
          emit(false, demoted);
          pending.reset();
          insideFrame = false;
        }
      }
    }
    return new ArrayList<>(emitted);
  }

  /** Flushes any partial tail (unterminated frame or trailing noise) as noise. */
  List<Segment> finish() {
    if (closed) return List.of();
    closed = true;
    List<Segment> tail = new ArrayList<>();
    if (pending.size() > 0) tail.add(new Segment(false, pending.toByteArray()));
    pending.reset();
    return tail;
  }

  private void flushNoise() {
    if (pending.size() > 0) {
      emit(false, pending.toByteArray());
      pending.reset();
    }
  }

  private void emit(boolean frame, byte[] bytes) {
    emitted.add(new Segment(frame, bytes));
  }
}
