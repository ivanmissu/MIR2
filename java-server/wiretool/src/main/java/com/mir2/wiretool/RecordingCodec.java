package com.mir2.wiretool;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binary {@code .mrec} file format, version 1:
 *
 * <pre>
 *   magic    4 bytes  "MREC"
 *   version  1 byte   0x01
 *   metaLen  u32 LE
 *   meta     metaLen bytes UTF-8, one line of "key=value; key=value"
 *   records  until EOF, each:
 *     kind     1 byte  tag of {@link Recording.Kind}
 *     millis   u32 LE (relative to session start)
 *     length   u32 LE
 *     payload  length bytes
 * </pre>
 *
 * The writer is append-oriented and {@link #openForWrite} flushes per record so an interrupted
 * capture still yields a readable file up to the last complete record.
 */
public final class RecordingCodec {
  public static final byte[] MAGIC = {'M', 'R', 'E', 'C'};
  public static final int VERSION = 1;
  public static final String FILE_EXTENSION = ".mrec";

  private RecordingCodec() {}

  /** Writes one recording; safe to share between the two pump threads of a proxied session. */
  public static final class Writer implements AutoCloseable {
    private final OutputStream out;
    private boolean closed;

    private Writer(OutputStream out) {
      this.out = out;
    }

    public synchronized void append(Recording.Kind kind, long millis, byte[] payload)
        throws IOException {
      if (closed) throw new IOException("recording writer is closed");
      if (millis < 0 || millis > 0xFFFF_FFFFL)
        throw new IllegalArgumentException("millis outside u32 range: " + millis);
      out.write(kind.tag());
      writeU32(out, millis);
      writeU32(out, payload.length);
      out.write(payload);
      out.flush();
    }

    public synchronized void append(Recording.Event event) throws IOException {
      append(event.kind(), event.millis(), event.payload());
    }

    /** Convenience for {@link Recording.Kind#MARKER} text lines such as session-open notes. */
    public synchronized void marker(long millis, String text) throws IOException {
      append(Recording.Kind.MARKER, millis, sanitize(text).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      out.close();
    }
  }

  /** Creates (truncating any previous) a recording file and writes the header. */
  public static Writer openForWrite(Path file, Map<String, String> metadata) throws IOException {
    Objects.requireNonNull(file, "file");
    if (file.getParent() != null) Files.createDirectories(file.getParent());
    byte[] meta = encodeMetadata(metadata).getBytes(StandardCharsets.UTF_8);
    OutputStream out = Files.newOutputStream(file);
    boolean ok = false;
    try {
      out.write(MAGIC);
      out.write(VERSION);
      writeU32(out, meta.length);
      out.write(meta);
      out.flush();
      ok = true;
      return new Writer(out);
    } finally {
      if (!ok) out.close();
    }
  }

  /** Writes a complete recording in one call (used by tests and artificial captures). */
  public static void write(Path file, Recording recording) throws IOException {
    try (Writer writer = openForWrite(file, recording.metadata())) {
      for (Recording.Event event : recording.events()) writer.append(event);
    }
  }

  /** Reads a complete recording; tolerates truncated trailing bytes as end-of-file. */
  public static Recording read(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      byte[] magic = in.readNBytes(MAGIC.length + 1);
      if (magic.length < MAGIC.length + 1) throw new EOFException("truncated recording header");
      for (int index = 0; index < MAGIC.length; index++) {
        if (magic[index] != MAGIC[index])
          throw new IOException("not an MREC recording (bad magic)");
      }
      int version = magic[MAGIC.length] & 0xFF;
      if (version != VERSION) throw new IOException("unsupported recording version: " + version);
      long metaLength = readU32(in);
      if (metaLength > 1 << 20) throw new IOException("metadata block too large: " + metaLength);
      byte[] meta = in.readNBytes((int) metaLength);
      if (meta.length != metaLength) throw new EOFException("truncated recording metadata");
      Map<String, String> metadata = decodeMetadata(new String(meta, StandardCharsets.UTF_8));

      List<Recording.Event> events = new ArrayList<>();
      while (true) {
        int tag = in.read();
        if (tag < 0) break;
        final Recording.Kind kind;
        try {
          kind = Recording.Kind.fromTag(tag);
        } catch (IllegalArgumentException error) {
          throw new IOException("corrupt record tag", error);
        }
        long millis = readU32(in);
        long length = readU32(in);
        if (length > Integer.MAX_VALUE - 8) throw new IOException("record too large: " + length);
        byte[] payload = in.readNBytes((int) length);
        if (payload.length != length) break; // truncated tail: keep the records read so far
        events.add(new Recording.Event(kind, millis, payload));
      }
      return new Recording(metadata, events);
    }
  }

  /** Encodes metadata as one {@code key=value; key=value} line (semicolons/equals sanitized). */
  static String encodeMetadata(Map<String, String> metadata) {
    StringBuilder line = new StringBuilder();
    metadata.forEach((key, value) -> {
      if (line.length() > 0) line.append("; ");
      line.append(sanitize(key)).append('=').append(sanitize(value));
    });
    return line.toString();
  }

  static Map<String, String> decodeMetadata(String line) {
    Map<String, String> metadata = new LinkedHashMap<>();
    if (line.isBlank()) return metadata;
    for (String pair : line.split("; ")) {
      int equals = pair.indexOf('=');
      if (equals <= 0) continue;
      metadata.put(pair.substring(0, equals), pair.substring(equals + 1));
    }
    return metadata;
  }

  /** Metadata lives on a single line and must survive the {@code key=value; } grammar. */
  private static String sanitize(String text) {
    return Objects.requireNonNull(text, "metadata text")
        .replace('\n', ' ').replace('\r', ' ').replace(';', ',').replace('=', '~');
  }

  private static void writeU32(OutputStream out, long value) throws IOException {
    out.write((int) (value & 0xFF));
    out.write((int) ((value >>> 8) & 0xFF));
    out.write((int) ((value >>> 16) & 0xFF));
    out.write((int) ((value >>> 24) & 0xFF));
  }

  private static long readU32(InputStream in) throws IOException {
    byte[] bytes = in.readNBytes(4);
    if (bytes.length != 4) throw new EOFException("truncated recording record");
    return (bytes[0] & 0xFFL) | ((bytes[1] & 0xFFL) << 8)
        | ((bytes[2] & 0xFFL) << 16) | ((bytes[3] & 0xFFL) << 24);
  }
}
