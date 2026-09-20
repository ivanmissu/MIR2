package com.mir2.wiretool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Replays the client→server half of a recording against a target server while capturing the
 * server's answers. Frames are re-wrapped in {@code '#'} / {@code '!'} exactly as recorded
 * (the rotating sequence digit rides along), noise bytes are sent raw, and the pacing between
 * events follows the recorded timestamps divided by {@code speed}.
 *
 * <p>The send loop degrades gracefully: if the server closes mid-session the replay stops
 * sending, notes the truncation, and still returns whatever was captured so the diff can report
 * the tail as missing frames instead of dying with a stack trace.
 */
public final class Replayer {
  /** A frame/noise segment observed on the wire during the replay. */
  public record Captured(boolean frame, long millis, byte[] payload) {
    public Captured {
      Objects.requireNonNull(payload, "payload");
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  public record Outcome(List<Captured> captured, boolean sendCompleted, int eventsSent,
      int eventsTotal, List<String> notes) {
    public Outcome {
      captured = List.copyOf(captured);
      notes = List.copyOf(notes);
    }

    /** Only the server→client frames, in arrival order (noise excluded). */
    public List<Captured> serverFrames() {
      List<Captured> frames = new ArrayList<>();
      for (Captured event : captured) if (event.frame()) frames.add(event);
      return frames;
    }

    public long noiseCount() {
      return captured.stream().filter(event -> !event.frame()).count();
    }
  }

  public record Options(String host, int port, double speed, boolean maxSpeed,
      Duration drain, Duration connectTimeout) {
    public Options {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(drain, "drain");
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      if (port < 1 || port > 65535) throw new IllegalArgumentException("port outside 1..65535");
      if (speed <= 0) throw new IllegalArgumentException("speed must be positive");
      if (drain.isNegative()) throw new IllegalArgumentException("drain must not be negative");
    }

    public static Options defaults(String host, int port) {
      return new Options(host, port, 1.0, false, Duration.ofMillis(1500), Duration.ofSeconds(5));
    }
  }

  /** Replays {@code recording} against the target and returns everything observed. */
  public Outcome replay(Recording recording, Options options) throws IOException,
      InterruptedException {
    List<Recording.Event> sends = recording.clientEvents();
    List<Captured> captured = Collections.synchronizedList(new ArrayList<>());
    List<String> notes = Collections.synchronizedList(new ArrayList<>());

    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(options.host(), options.port()),
          (int) options.connectTimeout().toMillis());
    } catch (IOException error) {
      try {
        socket.close();
      } catch (IOException suppressed) {
        error.addSuppressed(suppressed);
      }
      throw error;
    }

    long startedNanos = System.nanoTime();
    CountDownLatch readerDone = new CountDownLatch(1);
    Thread reader = Thread.ofVirtual().name("wiretool-replay-reader").start(() ->
        readLoop(socket, captured, notes, startedNanos, readerDone));

    int sent = 0;
    boolean completed = true;
    try {
      OutputStream out = socket.getOutputStream();
      for (Recording.Event event : sends) {
        pace(event.millis(), options, startedNanos);
        byte[] wire = event.kind() == Recording.Kind.CLIENT_FRAME
            ? framed(event.payload())
            : event.payload();
        try {
          out.write(wire);
          out.flush();
        } catch (IOException closed) {
          completed = false;
          notes.add("server closed while sending event " + (sent + 1) + "/" + sends.size()
              + ": " + closed.getMessage());
          break;
        }
        sent++;
      }
      // Give the server a bounded window to answer the final request before we stop listening.
      Thread.sleep(options.drain().toMillis());
    } finally {
      closeQuietly(socket);
      readerDone.await(10, TimeUnit.SECONDS);
      reader.join(TimeUnit.SECONDS.toMillis(5));
    }
    return new Outcome(new ArrayList<>(captured), completed, sent, sends.size(),
        new ArrayList<>(notes));
  }

  private static void readLoop(Socket socket, List<Captured> captured, List<String> notes,
      long startedNanos, CountDownLatch readerDone) {
    FrameSplitter splitter = new FrameSplitter();
    byte[] buffer = new byte[8192];
    try {
      InputStream in = socket.getInputStream();
      while (true) {
        final int read;
        try {
          read = in.read(buffer);
        } catch (IOException closed) {
          break;
        }
        if (read < 0) break;
        for (FrameSplitter.Segment segment : splitter.feed(buffer, 0, read)) {
          captured.add(new Captured(segment.frame(), elapsedMillis(startedNanos),
              segment.bytes()));
        }
      }
    } catch (IOException error) {
      notes.add("reader aborted: " + error.getMessage());
    } finally {
      for (FrameSplitter.Segment segment : splitter.finish()) {
        captured.add(new Captured(segment.frame(), elapsedMillis(startedNanos), segment.bytes()));
      }
      readerDone.countDown();
    }
  }

  /** Sleeps until the recorded timestamp (scaled by speed) has elapsed in replay time. */
  private static void pace(long eventMillis, Options options, long startedNanos)
      throws InterruptedException {
    if (options.maxSpeed()) return;
    long dueNanos = (long) (eventMillis * 1_000_000L / options.speed());
    long remaining = dueNanos - (System.nanoTime() - startedNanos);
    if (remaining > 0) {
      Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
    }
  }

  private static byte[] framed(byte[] payload) {
    byte[] frame = new byte[payload.length + 2];
    frame[0] = '#';
    System.arraycopy(payload, 0, frame, 1, payload.length);
    frame[frame.length - 1] = '!';
    return frame;
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // The reader may already have observed the peer's close.
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }
}
