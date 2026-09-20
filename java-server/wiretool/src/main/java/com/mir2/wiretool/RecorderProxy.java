package com.mir2.wiretool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traffic-recording TCP proxy: it sits between {@code mir2.exe} and a server (Delphi for golden
 * captures, the Java server for rehearsals) and relays bytes transparently in both directions
 * while writing every classified frame/noise segment into one {@code .mrec} file per connection.
 *
 * <p>The relay forwards the raw chunks exactly as read; the recording is derived from the same
 * bytes by a {@link FrameSplitter}, never from re-assembled frames, so the proxy cannot distort
 * the traffic it captures. Pumps run on virtual threads and propagate half-closes so connection
 * teardown matches the un-proxied behaviour.
 */
public final class RecorderProxy implements AutoCloseable {
  /** One recorded connection: output file, peer, and a short outcome note. */
  public record Session(Path file, String peer, boolean aborted, String note) {}

  public record Config(InetAddress bindAddress, int listenPort, String targetHost,
      int targetPort, Path outDir, String label, int maxConnections) {
    public Config {
      Objects.requireNonNull(bindAddress, "bindAddress");
      Objects.requireNonNull(targetHost, "targetHost");
      Objects.requireNonNull(outDir, "outDir");
      Objects.requireNonNull(label, "label");
      if (listenPort < 1 || listenPort > 65535 || targetPort < 1 || targetPort > 65535)
        throw new IllegalArgumentException("port outside 1..65535");
      if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be >= 1");
    }
  }

  private final Config config;
  private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap.KeySetView<Socket, Boolean> openSockets =
      ConcurrentHashMap.newKeySet();
  private final CountDownLatch terminated = new CountDownLatch(1);
  private final AtomicInteger completedConnections = new AtomicInteger();
  private final AtomicInteger sequence = new AtomicInteger();
  private volatile ServerSocket listener;
  private volatile Thread acceptThread;
  private volatile boolean closing;

  public RecorderProxy(Config config) {
    this.config = Objects.requireNonNull(config);
  }

  /** Starts the accept loop on a virtual thread and returns immediately. */
  public void start() throws IOException {
    if (listener != null) throw new IllegalStateException("proxy already started");
    Files.createDirectories(config.outDir());
    listener = new ServerSocket(config.listenPort(), 50, config.bindAddress());
    acceptThread = Thread.ofVirtual()
        .name("wiretool-record-accept-" + config.listenPort())
        .start(this::acceptLoop);
  }

  /** Blocks until the proxy stops: SIGTERM/close, or {@code maxConnections} sessions done. */
  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  public java.util.List<Session> sessions() {
    return java.util.List.copyOf(sessions);
  }

  public int completedConnections() {
    return completedConnections.get();
  }

  public boolean isRunning() {
    ServerSocket socket = listener;
    return socket != null && !socket.isClosed() && !closing;
  }

  @Override
  public void close() throws IOException {
    if (closing) return;
    closing = true;
    ServerSocket socket = listener;
    if (socket != null) socket.close();
    for (Socket open : openSockets) {
      try {
        open.close();
      } catch (IOException ignored) {
        // Already racing with the pump's own close path.
      }
    }
    terminated.countDown();
  }

  private void acceptLoop() {
    try {
      while (!closing) {
        final Socket client;
        try {
          client = listener.accept();
        } catch (IOException closed) {
          break; // listener closed by close()
        }
        if (completedConnections.get() >= config.maxConnections()) {
          try {
            client.close();
          } catch (IOException ignored) {
            // Rejected connection; nothing else to clean up.
          }
          break;
        }
        openSockets.add(client);
        Thread.startVirtualThread(() -> handleConnection(client));
      }
    } finally {
      terminated.countDown();
    }
  }

  private void handleConnection(Socket client) {
    int id = sequence.incrementAndGet();
    Path file = config.outDir().resolve(fileName(id));
    long startedNanos = System.nanoTime();
    InetSocketAddress peer = (InetSocketAddress) client.getRemoteSocketAddress();
    String peerText = addressOf(peer);
    String target = config.targetHost() + ":" + config.targetPort();
    try {
      Socket upstream = new Socket(config.targetHost(), config.targetPort());
      openSockets.add(upstream);
      try (RecordingCodec.Writer recording =
          RecordingCodec.openForWrite(file, metadata(peer, target))) {
        recording.marker(0, "session-open peer=" + peerText + " target=" + target);
        CountDownLatch pumps = new CountDownLatch(2);
        AtomicInteger frames = new AtomicInteger();
        Thread.startVirtualThread(() ->
            pump(client, upstream, recording, true, startedNanos, frames, pumps));
        Thread.startVirtualThread(() ->
            pump(upstream, client, recording, false, startedNanos, frames, pumps));
        pumps.await();
        long millis = elapsedMillis(startedNanos);
        recording.marker(millis, "session-close");
        sessions.add(new Session(file, peerText, false,
            frames.get() + " frames, " + millis + " ms"));
      } finally {
        openSockets.remove(upstream);
        try {
          upstream.close();
        } catch (IOException ignored) {
          // Pump threads race to close; that is expected.
        }
      }
    } catch (IOException | InterruptedException error) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // The partial capture stays on disk if deletion also fails; it is reported anyway.
      }
      sessions.add(new Session(file, peerText, true, "aborted: " + error.getMessage()));
    } finally {
      openSockets.remove(client);
      try {
        client.close();
      } catch (IOException ignored) {
        // Close is idempotent for our purposes.
      }
      int done = completedConnections.incrementAndGet();
      if (done >= config.maxConnections()) {
        try {
          close();
        } catch (IOException ignored) {
          // close() races are harmless; the latch is already counted down.
        }
      }
    }
  }

  /**
   * Relays one direction: forwards every chunk byte-exact, records the classified segments.
   * On EOF it half-closes the destination so the peer observes the same teardown as without
   * a proxy, and on any stream error it closes both sockets to unblock the sibling pump.
   */
  private void pump(Socket source, Socket destination, RecordingCodec.Writer recording,
      boolean clientToServer, long startedNanos, AtomicInteger frames, CountDownLatch pumps) {
    Recording.Kind frameKind =
        clientToServer ? Recording.Kind.CLIENT_FRAME : Recording.Kind.SERVER_FRAME;
    Recording.Kind noiseKind =
        clientToServer ? Recording.Kind.CLIENT_NOISE : Recording.Kind.SERVER_NOISE;
    FrameSplitter splitter = new FrameSplitter();
    byte[] buffer = new byte[8192];
    try {
      final InputStream in;
      final OutputStream out;
      try {
        in = source.getInputStream();
        out = destination.getOutputStream();
      } catch (IOException streamSetupFailed) {
        return; // Sibling pump tears the session down via its own error path.
      }
      while (true) {
        final int read;
        try {
          read = in.read(buffer);
        } catch (IOException closed) {
          break;
        }
        if (read < 0) break;
        for (FrameSplitter.Segment segment : splitter.feed(buffer, 0, read)) {
          record(recording, segment.frame() ? frameKind : noiseKind,
              elapsedMillis(startedNanos), segment.bytes());
          if (segment.frame()) frames.incrementAndGet();
        }
        try {
          out.write(buffer, 0, read);
          out.flush();
        } catch (IOException closed) {
          break;
        }
      }
      for (FrameSplitter.Segment segment : splitter.finish()) {
        record(recording, noiseKind, elapsedMillis(startedNanos), segment.bytes());
      }
      try {
        destination.shutdownOutput();
      } catch (IOException ignored) {
        // Destination already torn down by the sibling pump.
      }
    } finally {
      pumps.countDown();
    }
  }

  private static void record(RecordingCodec.Writer recording, Recording.Kind kind, long millis,
      byte[] payload) {
    try {
      recording.append(kind, millis, payload);
    } catch (IOException writeError) {
      // A failed disk write must never break the relay: drop the event, keep proxying.
      System.err.println("[wiretool] recording write failed: " + writeError.getMessage());
    }
  }

  private String fileName(int id) {
    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    return String.format("%s-%s-%d-%03d%s", config.label(), stamp, config.listenPort(), id,
        RecordingCodec.FILE_EXTENSION);
  }

  private Map<String, String> metadata(InetSocketAddress peer, String target) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("tool", "mir2-wiretool");
    metadata.put("format", "mrec-v1");
    metadata.put("label", config.label());
    metadata.put("listen", config.listenPort() + "");
    metadata.put("target", target);
    metadata.put("peer", peer == null ? "unknown" : addressOf(peer));
    metadata.put("started", java.time.Instant.now().toString());
    return metadata;
  }

  private static String addressOf(InetSocketAddress address) {
    return address.getAddress() == null
        ? address.getHostString() + ":" + address.getPort()
        : address.getAddress().getHostAddress() + ":" + address.getPort();
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }
}
