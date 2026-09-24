package com.mir2.gate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks over real TCP that the admission guard and the read-burst guard actually
 * refuse traffic at the socket, rather than only being correct in isolation.
 */
class GateAdmissionIntegrationTest {

  private static AccessPolicy.Config config(int maxActive, int burst1, int burst3,
      BlockMethod method) {
    return new AccessPolicy.Config(maxActive, burst1, burst3, Duration.ofSeconds(10), method);
  }

  /** A handler that just drains its connection, so the socket stays open until the peer stops. */
  private static java.util.function.BiConsumer<ClientConnection, IOException> drainingHandler() {
    return (connection, error) -> {
      if (connection == null) return;
      try (connection; InputStream in = connection.input()) {
        byte[] scratch = new byte[256];
        while (in.read(scratch) >= 0) {
          // keep reading until the client hangs up or a guard fails the stream
        }
      } catch (IOException ignored) {
        // Expected: the oversize guard fails the read, or the peer closed.
      }
    };
  }

  @Test
  void blockedAddressIsRefusedAtTheSocket() throws Exception {
    AccessPolicy policy = new AccessPolicy(
        config(50, 1000, 1000, BlockMethod.DISCONNECT),
        BlockIpList.ofPermanent(Set.of("127.0.0.1")));
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy)) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.login()), 2_000);
        socket.setSoTimeout(2_000);
        // The gate accepts then immediately closes a banned address, so the first read
        // reports end-of-stream instead of hanging.
        assertEquals(-1, socket.getInputStream().read(), "banned address must be dropped");
      }
    }
  }

  @Test
  void concurrencyLimitRefusesTheExtraConnection() throws Exception {
    AccessPolicy policy = new AccessPolicy(config(2, 1000, 1000, BlockMethod.DISCONNECT));
    GatePorts ports = distinctPorts();
    List<Socket> open = new ArrayList<>();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy)) {
      gates.start();
      for (int i = 0; i < 2; i++) {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", ports.login()), 2_000);
        open.add(socket);
      }
      // Give the accept loop a moment to register both permits.
      assertTrue(waitFor(() -> policy.activeConnections("127.0.0.1") == 2),
          "both connections should hold a permit");

      try (Socket third = new Socket()) {
        third.connect(new InetSocketAddress("127.0.0.1", ports.login()), 2_000);
        third.setSoTimeout(2_000);
        assertEquals(-1, third.getInputStream().read(), "third connection is over the cap");
      }
    } finally {
      for (Socket socket : open) socket.close();
    }
  }

  @Test
  void oversizedGameReadIsKickedAndBansTheAddressUnderBlockMethod() throws Exception {
    // bokickOverPacketSize with mBlock: the offender is dropped and its address is banned,
    // so the next connection from it is refused outright.
    AccessPolicy policy = new AccessPolicy(config(50, 1000, 1000, BlockMethod.BLOCK));
    PacketSizePolicy packets = new PacketSizePolicy(150, 7000, 15, true);
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy, packets)) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.game()), 2_000);
        socket.setSoTimeout(5_000);
        OutputStream out = socket.getOutputStream();
        byte[] flood = new byte[8_000];
        java.util.Arrays.fill(flood, (byte) 'A');
        out.write(flood);
        out.flush();
        assertTrue(waitFor(() -> policy.blockList().isBlocked("127.0.0.1")),
            "an oversized read must ban the address under mBlock");
      }
      // The ban is in force for the next attempt, on every gate.
      try (Socket rejected = new Socket()) {
        rejected.connect(new InetSocketAddress("127.0.0.1", ports.login()), 2_000);
        rejected.setSoTimeout(2_000);
        assertEquals(-1, rejected.getInputStream().read(), "banned address stays out");
      }
    }
  }

  @Test
  void loginGateDoesNotApplyTheGameReadGuard() throws Exception {
    // Delphi's size guard exists only in RunGate; LoginGate/SelGate read unchecked.
    AccessPolicy policy = new AccessPolicy(config(50, 1000, 1000, BlockMethod.BLOCK));
    PacketSizePolicy packets = new PacketSizePolicy(150, 7000, 15, true);
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy, packets)) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.login()), 2_000);
        OutputStream out = socket.getOutputStream();
        byte[] flood = new byte[8_000];
        java.util.Arrays.fill(flood, (byte) 'A');
        out.write(flood);
        out.flush();
        // Nothing should ban this address; give the server a moment to prove it doesn't.
        Thread.sleep(300);
        assertFalse(policy.blockList().isBlocked("127.0.0.1"),
            "the login gate must not enforce RunGate's read-burst guard");
      }
    }
  }

  @Test
  void tooManyFramesInOneReadTripsTheGuardEvenWhenTheBytesAreFew() throws Exception {
    // RunGate/Main.pas:982-1003 counts '!' terminators per recv, not bytes: 16 frames in a
    // 200-byte read is oversized even though 200 is far below nMaxClientPacketSize.
    AccessPolicy policy = new AccessPolicy(config(50, 1000, 1000, BlockMethod.BLOCK));
    PacketSizePolicy packets = new PacketSizePolicy(150, 7000, 15, true);
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy, packets)) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.game()), 2_000);
        socket.getOutputStream().write(framesOfExactly(16, 200));
        socket.getOutputStream().flush();
        assertTrue(waitFor(() -> policy.blockList().isBlocked("127.0.0.1")),
            "16 frames in one read must trip the count guard");
      }
    }
  }

  @Test
  void frameCountAtOrBelowTheCapIsAccepted() throws Exception {
    // The Delphi test is `> nMaxClientMsgCount`, so exactly 15 frames is still fine.
    AccessPolicy policy = new AccessPolicy(config(50, 1000, 1000, BlockMethod.BLOCK));
    PacketSizePolicy packets = new PacketSizePolicy(150, 7000, 15, true);
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy, packets)) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.game()), 2_000);
        socket.getOutputStream().write(framesOfExactly(15, 200));
        socket.getOutputStream().flush();
        Thread.sleep(400);
        assertFalse(policy.blockList().isBlocked("127.0.0.1"),
            "exactly nMaxClientMsgCount frames must be accepted");
      }
    }
  }

  /** Builds a read of exactly {@code total} bytes containing exactly {@code frames} '!' marks. */
  private static byte[] framesOfExactly(int frames, int total) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < frames - 1; i++) text.append("#1x!");
    text.append("#1");
    while (text.length() < total - 1) text.append('y');
    text.append('!');
    byte[] bytes = text.toString().getBytes(StandardCharsets.ISO_8859_1);
    if (bytes.length != total) throw new IllegalStateException("built " + bytes.length + "B");
    return bytes;
  }

  @Test
  void normalSizedGameTrafficIsUnaffected() throws Exception {
    AccessPolicy policy = new AccessPolicy(config(50, 1000, 1000, BlockMethod.BLOCK));
    GatePorts ports = distinctPorts();
    try (GateServer gates = new GateServer(ports, drainingHandler(), policy,
        PacketSizePolicy.defaults())) {
      gates.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", ports.game()), 2_000);
        OutputStream out = socket.getOutputStream();
        out.write("#1hello!".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Thread.sleep(300);
        assertFalse(policy.blockList().isBlocked("127.0.0.1"),
            "ordinary client traffic must never trip the guard");
      }
    }
  }

  private static boolean waitFor(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(25);
    }
    return condition.getAsBoolean();
  }

  private static GatePorts distinctPorts() throws IOException {
    int login = availablePort();
    int select = availablePort();
    int game = availablePort();
    while (select == login) select = availablePort();
    while (game == login || game == select) game = availablePort();
    return new GatePorts(login, select, game);
  }

  private static int availablePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
