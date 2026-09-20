package com.mir2.wiretool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderProxyTest {

  @TempDir
  Path captures;

  @Test
  void relaysByteExactAndRecordsFramesAndNoise() throws Exception {
    // Scripted "server": waits for the client bytes, then answers with two frames + noise.
    try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      byte[] serverFrame1 = "#SM-RESPONSE!".getBytes(StandardCharsets.ISO_8859_1);
      byte[] serverNoise = "raw-noise".getBytes(StandardCharsets.ISO_8859_1);
      byte[] serverFrame2 = "#SECOND!".getBytes(StandardCharsets.ISO_8859_1);
      CountDownLatch serverDone = new CountDownLatch(1);
      Thread serverThread = Thread.startVirtualThread(() -> {
        try (Socket session = target.accept()) {
          InputStream in = session.getInputStream();
          OutputStream out = session.getOutputStream();
          byte[] request = readN(in, "#CM-REQUEST!".length());
          assertArrayEquals("#CM-REQUEST!".getBytes(StandardCharsets.ISO_8859_1), request);
          out.write(serverFrame1);
          out.write(serverNoise);
          out.write(serverFrame2);
          out.flush();
          readN(in, "TRAILING-JUNK".length()); // client noise outside any frame
        } catch (IOException error) {
          throw new RuntimeException(error);
        } finally {
          serverDone.countDown();
        }
      });

      int listenPort = freePort();
      RecorderProxy proxy = new RecorderProxy(new RecorderProxy.Config(
          InetAddress.getLoopbackAddress(), listenPort, "127.0.0.1", target.getLocalPort(),
          captures, "test", 1));
      proxy.start();
      try {
        try (Socket client = new Socket("127.0.0.1", listenPort)) {
          OutputStream out = client.getOutputStream();
          InputStream in = client.getInputStream();
          out.write("#CM-REQUEST!".getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
          byte[] reply = readN(in, serverFrame1.length + serverNoise.length + serverFrame2.length);
          out.write("TRAILING-JUNK".getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
          byte[] expected = new byte[serverFrame1.length + serverNoise.length + serverFrame2.length];
          System.arraycopy(serverFrame1, 0, expected, 0, serverFrame1.length);
          System.arraycopy(serverNoise, 0, expected, serverFrame1.length, serverNoise.length);
          System.arraycopy(serverFrame2, 0, expected,
              serverFrame1.length + serverNoise.length, serverFrame2.length);
          assertArrayEquals(expected, reply); // relay is byte-exact
        }
        assertTrue(serverDone.await(10, TimeUnit.SECONDS));
        serverThread.join(TimeUnit.SECONDS.toMillis(10));
        proxy.awaitTermination(); // maxConnections=1 stops the proxy
      } finally {
        proxy.close();
      }

      assertEquals(1, proxy.sessions().size());
      Path file = proxy.sessions().get(0).file();
      assertTrue(Files.exists(file), "recording must exist: " + file);
      Recording recording = RecordingCodec.read(file);
      assertEquals("127.0.0.1:" + target.getLocalPort(), recording.metadata().get("target"));
      assertEquals(1, recording.count(Recording.Kind.CLIENT_FRAME));
      assertEquals(2, recording.count(Recording.Kind.SERVER_FRAME));
      assertEquals(1, recording.count(Recording.Kind.CLIENT_NOISE));
      assertEquals(1, recording.count(Recording.Kind.SERVER_NOISE));
      assertArrayEquals("raw-noise".getBytes(StandardCharsets.ISO_8859_1),
          firstOfKind(recording, Recording.Kind.SERVER_NOISE));
      assertArrayEquals("TRAILING-JUNK".getBytes(StandardCharsets.ISO_8859_1),
          firstOfKind(recording, Recording.Kind.CLIENT_NOISE));
      assertArrayEquals("CM-REQUEST".getBytes(StandardCharsets.ISO_8859_1),
          firstOfKind(recording, Recording.Kind.CLIENT_FRAME));
      // Markers frame the session.
      assertEquals(Recording.Kind.MARKER, recording.events().get(0).kind());
      assertTrue(new String(recording.events().get(0).payload(), StandardCharsets.UTF_8)
          .contains("session-open"));
      // Timestamps are non-decreasing across the session.
      long previous = -1;
      for (Recording.Event event : recording.events()) {
        assertTrue(event.millis() >= previous, "millis must be monotonic");
        previous = event.millis();
      }
    }
  }

  @Test
  void sequentialConnectionsProduceOneFileEach() throws Exception {
    try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread.startVirtualThread(() -> {
        try {
          for (int connection = 0; connection < 2; connection++) {
            try (Socket session = target.accept()) {
              session.getOutputStream().write("#R!".getBytes(StandardCharsets.ISO_8859_1));
              session.getOutputStream().flush();
            }
          }
        } catch (IOException error) {
          throw new RuntimeException(error);
        }
      });
      int listenPort = freePort();
      RecorderProxy proxy = new RecorderProxy(new RecorderProxy.Config(
          InetAddress.getLoopbackAddress(), listenPort, "127.0.0.1", target.getLocalPort(),
          captures, "multi", 2));
      proxy.start();
      try {
        for (int connection = 0; connection < 2; connection++) {
          try (Socket client = new Socket("127.0.0.1", listenPort)) {
            assertEquals(3, readN(client.getInputStream(), 3).length);
          }
        }
        proxy.awaitTermination(); // stops after 2 connections
      } finally {
        proxy.close();
      }
      assertEquals(2, proxy.sessions().size());
      assertEquals(2, proxy.sessions().get(0).file()
          .getParent().toFile().listFiles().length);
    }
  }

  private static byte[] firstOfKind(Recording recording, Recording.Kind kind) {
    return recording.events().stream()
        .filter(event -> event.kind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no event of kind " + kind))
        .payload();
  }

  private static byte[] readN(InputStream in, int count) throws IOException {
    byte[] result = in.readNBytes(count);
    assertEquals(count, result.length, "expected " + count + " bytes from stream");
    return result;
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return probe.getLocalPort();
    }
  }
}
