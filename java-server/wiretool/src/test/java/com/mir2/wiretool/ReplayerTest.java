package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ReplayerTest {

  @Test
  void replaysFramesAndCapturesServerAnswersByteForByte() throws Exception {
    Recording recording = new Recording(Map.of("label", "t"), List.of(
        clientFrame(0, "REQ-A"),
        serverFrame(40, "RESP-1"),
        clientFrame(100, "REQ-B"),
        clientFrame(200, "REQ-C"),
        serverFrame(240, "RESP-2")));

    ScriptedServer server = new ScriptedServer()
        .onFrame(1, "RESP-1")
        .onFrame(3, "RESP-2");
    server.start();
    try {
      Replayer.Outcome outcome = new Replayer().replay(recording,
          new Replayer.Options("127.0.0.1", server.port(), 1.0, true,
              Duration.ofMillis(600), Duration.ofSeconds(3)));
      assertTrue(outcome.sendCompleted());
      assertEquals(3, outcome.eventsSent());
      assertEquals(2, outcome.serverFrames().size());
      assertEquals("RESP-1", text(outcome.serverFrames().get(0).payload()));
      assertEquals("RESP-2", text(outcome.serverFrames().get(1).payload()));

      ReplayDiff.Result diff = ReplayDiff.compare(recordedServerPayloads(recording),
          capturedPayloads(outcome), Set.of(), outcome.noiseCount());
      assertTrue(diff.passed(false));
      assertEquals(2, diff.count(ReplayDiff.Status.IDENTICAL));
    } finally {
      server.close();
    }
  }

  @Test
  void reportsFirstDifferingByteOffset() throws Exception {
    Recording recording = new Recording(Map.of(), List.of(
        clientFrame(0, "REQ"),
        serverFrame(10, "RESP")));

    ScriptedServer server = new ScriptedServer().onFrame(1, "RESX");
    server.start();
    try {
      Replayer.Outcome outcome = new Replayer().replay(recording,
          new Replayer.Options("127.0.0.1", server.port(), 1.0, true,
              Duration.ofMillis(400), Duration.ofSeconds(3)));
      ReplayDiff.Result diff = ReplayDiff.compare(recordedServerPayloads(recording),
          capturedPayloads(outcome), Set.of(), 0);
      assertFalse(diff.passed(false));
      assertTrue(diff.passed(true));
      ReplayDiff.Row row = diff.rows().get(0);
      assertEquals(ReplayDiff.Status.CONTENT, row.status());
      assertEquals(3, row.firstDiffOffset());
    } finally {
      server.close();
    }
  }

  @Test
  void reportsMissingFramesWhenTheServerStopsAnswering() throws Exception {
    Recording recording = new Recording(Map.of(), List.of(
        clientFrame(0, "REQ-1"),
        serverFrame(10, "RESP-1"),
        clientFrame(20, "REQ-2"),
        serverFrame(30, "RESP-2")));

    ScriptedServer server = new ScriptedServer().onFrame(1, "RESP-1"); // no answer to frame 2
    server.start();
    try {
      Replayer.Outcome outcome = new Replayer().replay(recording,
          new Replayer.Options("127.0.0.1", server.port(), 1.0, true,
              Duration.ofMillis(400), Duration.ofSeconds(3)));
      ReplayDiff.Result diff = ReplayDiff.compare(recordedServerPayloads(recording),
          capturedPayloads(outcome), Set.of(), 0);
      assertEquals(1, diff.count(ReplayDiff.Status.MISSING));
      assertFalse(diff.passed(true)); // missing frames are structural failures
    } finally {
      server.close();
    }
  }

  @Test
  void extraServerFramesAreReportedAndServerNoiseIsCounted() throws Exception {
    Recording recording = new Recording(Map.of(), List.of(
        clientFrame(0, "REQ-1"),
        serverFrame(10, "RESP-1")));

    ScriptedServer server = new ScriptedServer()
        .onFrame(1, "RESP-1")
        .onFrame(1, "RESP-BONUS")
        .noiseAfterFrame(1, "RAW");
    server.start();
    try {
      Replayer.Outcome outcome = new Replayer().replay(recording,
          new Replayer.Options("127.0.0.1", server.port(), 1.0, true,
              Duration.ofMillis(400), Duration.ofSeconds(3)));
      ReplayDiff.Result diff = ReplayDiff.compare(recordedServerPayloads(recording),
          capturedPayloads(outcome), Set.of(), outcome.noiseCount());
      assertEquals(1, diff.count(ReplayDiff.Status.EXTRA));
      assertEquals(1, outcome.noiseCount());
      assertFalse(diff.passed(true));
    } finally {
      server.close();
    }
  }

  @Test
  void pacingScalesWithSpeed() throws Exception {
    Recording recording = new Recording(Map.of(), List.of(
        clientFrame(0, "REQ-1"),
        clientFrame(400, "REQ-2")));

    ScriptedServer slow = new ScriptedServer();
    slow.start();
    long pacedNanos = System.nanoTime();
    try {
      new Replayer().replay(recording, new Replayer.Options("127.0.0.1", slow.port(), 1.0,
          false, Duration.ofMillis(50), Duration.ofSeconds(3)));
    } finally {
      slow.close();
    }
    long pacedMillis = (System.nanoTime() - pacedNanos) / 1_000_000L;
    assertTrue(pacedMillis >= 350, "1x replay must keep the recorded spacing, took "
        + pacedMillis + "ms");

    ScriptedServer fast = new ScriptedServer();
    fast.start();
    long rushedNanos = System.nanoTime();
    try {
      new Replayer().replay(recording, new Replayer.Options("127.0.0.1", fast.port(), 16.0,
          false, Duration.ofMillis(50), Duration.ofSeconds(3)));
    } finally {
      fast.close();
    }
    long rushedMillis = (System.nanoTime() - rushedNanos) / 1_000_000L;
    assertTrue(rushedMillis < 300, "16x replay should finish quickly, took "
        + rushedMillis + "ms");
  }

  /** Continues the diff even when the server hangs up mid-replay. */
  @Test
  void serverClosingMidReplayLeavesSendTruncatedButComparable() throws Exception {
    Recording recording = new Recording(Map.of(), List.of(
        clientFrame(0, "REQ-1"),
        serverFrame(10, "RESP-1"),
        clientFrame(20, "REQ-2"),
        serverFrame(30, "RESP-2")));

    ScriptedServer server = new ScriptedServer()
        .onFrame(1, "RESP-1")
        .closeAfterFrames(1);
    server.start();
    try {
      Replayer.Outcome outcome = new Replayer().replay(recording,
          new Replayer.Options("127.0.0.1", server.port(), 1.0, true,
              Duration.ofMillis(800), Duration.ofSeconds(3)));
      ReplayDiff.Result diff = ReplayDiff.compare(recordedServerPayloads(recording),
          capturedPayloads(outcome), Set.of(), outcome.noiseCount());
      // Frame 2 was recorded but can no longer arrive; the diff must say MISSING, not throw.
      assertEquals(1, diff.count(ReplayDiff.Status.MISSING));
      assertFalse(diff.passed(false));
    } finally {
      server.close();
    }
  }

  // ------------------------------------------------------------ helpers

  private static Recording.Event clientFrame(long millis, String payload) {
    return new Recording.Event(Recording.Kind.CLIENT_FRAME, millis, bytes(payload));
  }

  private static Recording.Event serverFrame(long millis, String payload) {
    return new Recording.Event(Recording.Kind.SERVER_FRAME, millis, bytes(payload));
  }

  private static List<byte[]> recordedServerPayloads(Recording recording) {
    List<byte[]> payloads = new ArrayList<>();
    for (Recording.Event event : recording.serverFrames()) payloads.add(event.payload());
    return payloads;
  }

  private static List<byte[]> capturedPayloads(Replayer.Outcome outcome) {
    List<byte[]> payloads = new ArrayList<>();
    for (Replayer.Captured captured : outcome.serverFrames()) payloads.add(captured.payload());
    return payloads;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String text(byte[] payload) {
    return new String(payload, StandardCharsets.ISO_8859_1);
  }

  /**
   * Single-connection scripted server: for every received client frame it sends the queued
   * {@code #…!} responses registered for that frame index (1-based) plus optional raw noise.
   */
  private static final class ScriptedServer implements AutoCloseable {
    private final Map<Integer, List<byte[]>> responses = new java.util.HashMap<>();
    private final Map<Integer, List<byte[]>> noises = new java.util.HashMap<>();
    private final CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
    private volatile ServerSocket listener;
    private int closeAfter = Integer.MAX_VALUE;

    ScriptedServer onFrame(int frameIndex, String responsePayload) {
      responses.computeIfAbsent(frameIndex, key -> new ArrayList<>()).add(bytes(responsePayload));
      return this;
    }

    ScriptedServer noiseAfterFrame(int frameIndex, String noise) {
      noises.computeIfAbsent(frameIndex, key -> new ArrayList<>()).add(bytes(noise));
      return this;
    }

    ScriptedServer closeAfterFrames(int frameIndex) {
      closeAfter = frameIndex;
      return this;
    }

    int port() {
      return listener.getLocalPort();
    }

    void start() throws IOException {
      listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Thread.startVirtualThread(this::serve);
    }

    private void serve() {
      try (Socket socket = listener.accept()) {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        FrameSplitter splitter = new FrameSplitter();
        byte[] buffer = new byte[4096];
        while (true) {
          final int count;
          try {
            count = in.read(buffer);
          } catch (IOException gone) {
            break;
          }
          if (count < 0) break;
          for (FrameSplitter.Segment segment : splitter.feed(buffer, 0, count)) {
            if (!segment.frame()) continue;
            received.add(segment.bytes());
            Deque<byte[]> queue = new ArrayDeque<>(
                responses.getOrDefault(received.size(), List.of()));
            while (!queue.isEmpty()) {
              byte[] payload = queue.pollFirst();
              out.write('#');
              out.write(payload);
              out.write('!');
            }
            for (byte[] noise : noises.getOrDefault(received.size(), List.of())) {
              out.write(noise);
            }
            out.flush();
            if (received.size() >= closeAfter) return;
          }
        }
      } catch (IOException acceptOrStreamClosed) {
        if (!listener.isClosed()) throw new RuntimeException(acceptOrStreamClosed);
      }
    }

    @Override
    public void close() throws IOException {
      listener.close();
    }
  }
}
