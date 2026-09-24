package com.mir2.gate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the read-burst guard of {@code ServerSocketClientRead} (RunGate/Main.pas:978-1005) and
 * the stream that applies it.
 */
class PacketSizePolicyTest {

  private static final PacketSizePolicy DEFAULTS = PacketSizePolicy.defaults();

  @Test
  void defaultsMatchTheShippedRunGateValues() {
    assertEquals(150, DEFAULTS.normalSize(), "nNomClientPacketSize");
    assertEquals(7000, DEFAULTS.maxSize(), "nMaxClientPacketSize");
    assertEquals(15, DEFAULTS.maxMessagesPerRead(), "nMaxClientMsgCount");
    assertTrue(DEFAULTS.kickOnOversize(), "bokickOverPacketSize");
  }

  @Test
  void readsAtOrBelowTheNormalSizeAreNeverInspected() {
    // The frame count is only computed inside 'if nReviceLen > nNomClientPacketSize', so a
    // small read packed with frames cannot trip the message-count limit at all.
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, DEFAULTS.evaluate(150, 999));
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, DEFAULTS.evaluate(1, 50));
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, DEFAULTS.evaluate(0, 0));
  }

  @Test
  void oversizedReadTripsOnEitherFrameCountOrByteCap() {
    // Past 150 bytes both limits apply, each with a strict '>' comparison.
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, DEFAULTS.evaluate(151, 15), "15 frames is allowed");
    assertEquals(PacketSizePolicy.Verdict.KICK, DEFAULTS.evaluate(151, 16), "16 frames > 15");
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, DEFAULTS.evaluate(7000, 1), "exactly the cap");
    assertEquals(PacketSizePolicy.Verdict.KICK, DEFAULTS.evaluate(7001, 1), "7001 > 7000");
  }

  @Test
  void discardsWithoutClosingWhenKickIsDisabled() {
    // bokickOverPacketSize = False: Delphi still 'exit's, dropping the bytes, but leaves the
    // connection open.
    PacketSizePolicy lenient = new PacketSizePolicy(150, 7000, 15, false);
    assertEquals(PacketSizePolicy.Verdict.DISCARD, lenient.evaluate(8000, 1));
    assertEquals(PacketSizePolicy.Verdict.DISCARD, lenient.evaluate(200, 99));
    assertEquals(PacketSizePolicy.Verdict.ACCEPT, lenient.evaluate(200, 2));
  }

  @Test
  void countsFrameTerminators() {
    byte[] buffer = "#abc!#de!#f!".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(3, PacketSizePolicy.countFrames(buffer, 0, buffer.length));
    assertEquals(1, PacketSizePolicy.countFrames(buffer, 0, 5));
    assertEquals(0, PacketSizePolicy.countFrames(buffer, 0, 4));
  }

  @Test
  void guardedStreamPassesNormalTrafficThrough() throws IOException {
    byte[] payload = "#1hello!".getBytes(StandardCharsets.ISO_8859_1);
    try (InputStream guarded = new ClientConnection.BurstGuardedInputStream(
        new ByteArrayInputStream(payload), DEFAULTS)) {
      assertArrayEquals(payload, guarded.readAllBytes());
    }
  }

  @Test
  void guardedStreamFailsTheConnectionOnAnOversizedRead() {
    // One delivery carrying more than nMaxClientPacketSize bytes.
    byte[] payload = new byte[7001];
    java.util.Arrays.fill(payload, (byte) 'A');
    try (InputStream guarded = new ClientConnection.BurstGuardedInputStream(
        new ByteArrayInputStream(payload), DEFAULTS)) {
      assertThrows(ClientConnection.OversizedReadException.class, guarded::readAllBytes);
    } catch (IOException closeFailure) {
      fail(closeFailure);
    }
  }

  @Test
  void guardedStreamDropsTheReadButKeepsGoingWhenKickIsDisabled() throws IOException {
    PacketSizePolicy lenient = new PacketSizePolicy(4, 8, 2, false);
    // First delivery is oversized and must vanish; the stream then serves the next one.
    InputStream source = new InputStream() {
      private final byte[][] deliveries = {
          "!!!!!!!!!".getBytes(StandardCharsets.ISO_8859_1),   // 9 bytes, 9 frames -> discard
          "ok".getBytes(StandardCharsets.ISO_8859_1)            // small -> accepted
      };
      private int index;
      @Override public int read() { throw new UnsupportedOperationException(); }
      @Override public int read(byte[] destination, int offset, int length) {
        if (index >= deliveries.length) return -1;
        byte[] delivery = deliveries[index++];
        System.arraycopy(delivery, 0, destination, offset, delivery.length);
        return delivery.length;
      }
    };
    try (InputStream guarded = new ClientConnection.BurstGuardedInputStream(source, lenient)) {
      assertEquals("ok", new String(guarded.readAllBytes(), StandardCharsets.ISO_8859_1),
          "the oversized delivery was silently dropped, as Delphi does");
    }
  }

  @Test
  void rejectsIncoherentConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new PacketSizePolicy(200, 100, 15, true));
    assertThrows(IllegalArgumentException.class, () -> new PacketSizePolicy(150, 0, 15, true));
    assertThrows(IllegalArgumentException.class, () -> new PacketSizePolicy(150, 7000, 0, true));
    assertThrows(IllegalArgumentException.class, () -> new PacketSizePolicy(-1, 7000, 15, true));
  }
}
