package com.mir2.wiretool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory model of one recorded TCP session.
 *
 * <p>A recording is a metadata map plus an ordered list of {@link Event}s. Frame events carry
 * the payload bytes found between the legacy {@code '#'} / {@code '!'} delimiters; noise events
 * carry any bytes observed outside a well-formed frame, so the original byte stream can be
 * reconstructed losslessly. {@code millis} is the event time in milliseconds relative to the
 * session start (monotonic clock).
 */
public final class Recording {
  /** Kind of a recorded event, identified on disk by its tag byte. */
  public enum Kind {
    CLIENT_FRAME('>'),
    SERVER_FRAME('<'),
    CLIENT_NOISE('n'),
    SERVER_NOISE('N'),
    MARKER('E');

    private final char tag;

    Kind(char tag) {
      this.tag = tag;
    }

    public char tag() {
      return tag;
    }

    public boolean clientToServer() {
      return this == CLIENT_FRAME || this == CLIENT_NOISE;
    }

    public boolean serverToClient() {
      return this == SERVER_FRAME || this == SERVER_NOISE;
    }

    public boolean frame() {
      return this == CLIENT_FRAME || this == SERVER_FRAME;
    }

    public static Kind fromTag(int tag) {
      for (Kind kind : values()) {
        if (kind.tag == tag) return kind;
      }
      throw new IllegalArgumentException("unknown record tag: 0x" + Integer.toHexString(tag));
    }
  }

  /** One recorded event. Payload bytes are defensively copied. */
  public record Event(Kind kind, long millis, byte[] payload) {
    public Event {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(payload, "payload");
      if (millis < 0) throw new IllegalArgumentException("millis must not be negative");
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    int length() {
      return payload.length;
    }
  }

  private final Map<String, String> metadata;
  private final List<Event> events;

  public Recording(Map<String, String> metadata, List<Event> events) {
    this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(metadata)));
    this.events = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(events)));
  }

  public Map<String, String> metadata() {
    return metadata;
  }

  public List<Event> events() {
    return events;
  }

  /** All client→server events (frames and noise) in recording order. */
  public List<Event> clientEvents() {
    List<Event> result = new ArrayList<>();
    for (Event event : events) if (event.kind().clientToServer()) result.add(event);
    return result;
  }

  /** All server→client frame events in recording order (noise excluded). */
  public List<Event> serverFrames() {
    List<Event> result = new ArrayList<>();
    for (Event event : events) if (event.kind() == Kind.SERVER_FRAME) result.add(event);
    return result;
  }

  /** Duration of the session in milliseconds: the largest event timestamp. */
  public long durationMillis() {
    long duration = 0;
    for (Event event : events) duration = Math.max(duration, event.millis());
    return duration;
  }

  public int count(Kind kind) {
    int total = 0;
    for (Event event : events) if (event.kind() == kind) total++;
    return total;
  }
}
