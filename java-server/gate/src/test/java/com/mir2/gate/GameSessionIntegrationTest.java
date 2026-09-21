package com.mir2.gate;

import com.mir2.auth.AuthService;
import com.mir2.character.CharacterService;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameSessionIntegrationTest {
  @Test
  void twoGameSessionsEnterMoveRunAndDisappearOnDisconnect() throws Exception {
    AuthService auth = new AuthService();
    auth.register("one", "pw");
    auth.register("two", "pw");
    CharacterService characters = new CharacterService();
    GateSessionRegistry sessions = new GateSessionRegistry();

    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "比奇", 40, 40)))) {
      world.start();
      LegacyGateHandler handler = new LegacyGateHandler(new SessionRouter(auth, characters), sessions,
          LegacyGateHandler.Config.defaults(), error -> {},
          new LegacyGateHandler.WorldConfig(world, "0", new Position(16, 16), Direction.DOWN));
      GatePorts ports = distinctPorts();
      try (GateServer gates = new GateServer(ports, handler)) {
        gates.start();
        int certificationOne = prepareCharacter(handler, "one", "甲");
        int certificationTwo = prepareCharacter(handler, "two", "乙");

        try (Socket one = connectGame(ports.game(), "one", "甲", certificationOne)) {
          List<WirePacket> oneEntry = readPackets(one, 4);
          assertEquals(List.of(ProtocolConstants.SM_NEWMAP, ProtocolConstants.SM_LOGON,
              ProtocolConstants.SM_MAPDESCRIPTION, ProtocolConstants.SM_ABILITY), idents(oneEntry));
          byte[] logonBody = SixBitCodec.decodeString(oneEntry.get(1).encodedBody());
          assertEquals(0x01050100,
              ByteBuffer.wrap(logonBody).order(ByteOrder.LITTLE_ENDIAN).getInt(),
              "CM_NEWCHR gender/hair must reach SM_LOGON Feature");

          Socket two = connectGame(ports.game(), "two", "乙", certificationTwo);
          try (two) {
            List<WirePacket> twoEntry = readPackets(two, 5);
            assertEquals(List.of(ProtocolConstants.SM_NEWMAP, ProtocolConstants.SM_LOGON,
                ProtocolConstants.SM_MAPDESCRIPTION, ProtocolConstants.SM_ABILITY, ProtocolConstants.SM_TURN), idents(twoEntry));
            Position twoPosition = new Position(twoEntry.getFirst().message().param(),
                twoEntry.getFirst().message().tag());

            WirePacket appeared = WireMessageCodec.readPacket(one.getInputStream());
            assertEquals(ProtocolConstants.SM_TURN, appeared.message().ident());
            int twoId = appeared.message().recog();

            sendAction(two, ProtocolConstants.CM_WALK,
                new Position(twoPosition.x() - 1, twoPosition.y()), Direction.LEFT);
            assertTrue(readRawFrame(two).startsWith("#+GOOD/"));
            WirePacket walked = WireMessageCodec.readPacket(one.getInputStream());
            assertEquals(ProtocolConstants.SM_WALK, walked.message().ident());
            assertEquals(twoId, walked.message().recog());

            int currentX = twoPosition.x() - 3;
            sendAction(two, ProtocolConstants.CM_RUN,
                new Position(currentX, twoPosition.y()), Direction.LEFT);
            assertTrue(readRawFrame(two).startsWith("#+GOOD/"));
            WirePacket ran = WireMessageCodec.readPacket(one.getInputStream());
            assertEquals(ProtocolConstants.SM_RUN, ran.message().ident());
            assertEquals(twoId, ran.message().recog());

            boolean leftView = false;
            while (currentX >= 2 && !leftView) {
              currentX -= 2;
              sendAction(two, ProtocolConstants.CM_RUN,
                  new Position(currentX, twoPosition.y()), Direction.LEFT);
              assertTrue(readRawFrame(two).startsWith("#+GOOD/"));
              WirePacket observed = WireMessageCodec.readPacket(one.getInputStream());
              leftView = observed.message().ident() == ProtocolConstants.SM_DISAPPEAR;
              assertTrue(leftView || observed.message().ident() == ProtocolConstants.SM_RUN);
              assertEquals(twoId, observed.message().recog());
            }
            assertTrue(leftView, "observer should receive SM_DISAPPEAR after the runner leaves view");
          }

          assertEquals(1, awaitOnlinePlayers(world, 1));
        }
      }
    }
  }

  @Test
  void certificationIsConsumedOnEntryAndCannotBeReplayedAfterDisconnect() throws Exception {
    AuthService auth = new AuthService();
    auth.register("solo", "pw");
    CharacterService characters = new CharacterService();
    GateSessionRegistry sessions = new GateSessionRegistry();

    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "比奇", 40, 40)))) {
      world.start();
      LegacyGateHandler handler = new LegacyGateHandler(new SessionRouter(auth, characters), sessions,
          LegacyGateHandler.Config.defaults(), error -> {},
          new LegacyGateHandler.WorldConfig(world, "0", new Position(16, 16), Direction.DOWN));
      GatePorts ports = distinctPorts();
      try (GateServer gates = new GateServer(ports, handler)) {
        gates.start();
        int certification = prepareCharacter(handler, "solo", "甲");

        try (Socket first = connectGame(ports.game(), "solo", "甲", certification)) {
          readPackets(first, 4);
        }
        assertEquals(0, awaitOnlinePlayers(world, 0), "first session must leave the world on disconnect");

        // Same certification, second GAME connection: mir2.exe never resubmits a spent
        // certification, but a replay attempt (or a stale retry after the world already
        // admitted the player) must now be rejected with SM_STARTFAIL instead of quietly
        // re-entering the world (matching the RunLogin rejection path for any other invalid
        // certification, see authenticateGameConnection's SecurityException branch).
        try (Socket replayed = connectGame(ports.game(), "solo", "甲", certification)) {
          WirePacket rejected = WireMessageCodec.readPacket(replayed.getInputStream());
          assertEquals(ProtocolConstants.SM_STARTFAIL, rejected.message().ident(),
              "a spent certification must be rejected, not silently re-admitted");
        }
        assertEquals(0, awaitOnlinePlayers(world, 0), "the replayed certification must not re-enter the world");
      }
    }
  }

  private static int prepareCharacter(LegacyGateHandler handler, String account, String name) {
    LegacyGateHandler.ConnectionState loginState = new LegacyGateHandler.ConnectionState();
    handler.dispatch(GateKind.LOGIN, loginState, request(ProtocolConstants.CM_IDPASSWORD, account + "/pw"));
    WirePacket route = handler.dispatch(GateKind.LOGIN, loginState,
        request(ProtocolConstants.CM_SELECTSERVER, "MIR2"));
    int certification = Integer.parseInt(
        WireMessageCodec.decodeBody(route.encodedBody()).replaceAll(".*/", ""));

    LegacyGateHandler.ConnectionState selectState = new LegacyGateHandler.ConnectionState();
    handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_QUERYCHR, account + "/" + certification));
    handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_NEWCHR, account + "/" + name + "/2/0/1"));
    WirePacket selected = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_SELCHR, account + "/" + name));
    assertEquals(ProtocolConstants.SM_STARTPLAY, selected.message().ident());
    return certification;
  }

  private static Socket connectGame(int port, String account, String name, int certification) throws Exception {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(5_000);
    String body = WireMessageCodec.encodeBody(
        "**" + account + "/" + name + "/" + certification + "/120040918/9");
    socket.getOutputStream().write(("#1" + body + "!").getBytes(StandardCharsets.ISO_8859_1));
    socket.getOutputStream().flush();
    return socket;
  }

  private static void sendAction(Socket socket, int ident, Position target, Direction direction) throws Exception {
    int packed = (target.y() << 16) | target.x();
    WireMessageCodec.writePacket(socket.getOutputStream(),
        new WirePacket(new DefaultMessage(packed, ident, 0, direction.code(), 0)));
  }

  private static String readRawFrame(Socket socket) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int value;
    do {
      value = socket.getInputStream().read();
      if (value < 0) fail("socket closed before frame");
    } while (value != '#');
    output.write(value);
    while ((value = socket.getInputStream().read()) >= 0) {
      output.write(value);
      if (value == '!') return output.toString(StandardCharsets.US_ASCII);
    }
    return fail("socket closed during frame");
  }

  private static List<WirePacket> readPackets(Socket socket, int count) throws Exception {
    java.util.ArrayList<WirePacket> packets = new java.util.ArrayList<>();
    for (int index = 0; index < count; index++) packets.add(WireMessageCodec.readPacket(socket.getInputStream()));
    return packets;
  }

  private static List<Integer> idents(List<WirePacket> packets) {
    return packets.stream().map(packet -> packet.message().ident()).toList();
  }

  private static int awaitOnlinePlayers(WorldEngine world, int expected) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    int actual;
    do {
      actual = world.onlinePlayers().get(1, java.util.concurrent.TimeUnit.SECONDS);
      if (actual == expected) return actual;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return actual;
  }

  private static WirePacket request(int ident, String body) {
    return new WirePacket(new DefaultMessage(0, ident, 0, 0, 0), WireMessageCodec.encodeBody(body));
  }

  private static GatePorts distinctPorts() throws Exception {
    int login = availablePort();
    int select = availablePort();
    int game = availablePort();
    while (select == login) select = availablePort();
    while (game == login || game == select) game = availablePort();
    return new GatePorts(login, select, game);
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
