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
          List<WirePacket> oneEntry = readPackets(one, 8);
          assertEquals(List.of(ProtocolConstants.SM_NEWMAP, ProtocolConstants.SM_CHANGELIGHT,
              ProtocolConstants.SM_LOGON, ProtocolConstants.SM_FEATURECHANGED,
              ProtocolConstants.SM_USERNAME, ProtocolConstants.SM_MAPDESCRIPTION,
              ProtocolConstants.SM_ABILITY, ProtocolConstants.SM_SUBABILITY), idents(oneEntry));
          byte[] logonBody = SixBitCodec.decodeString(oneEntry.get(2).encodedBody());
          assertEquals(0x01050100,
              ByteBuffer.wrap(logonBody).order(ByteOrder.LITTLE_ENDIAN).getInt(),
              "CM_NEWCHR gender/hair must reach SM_LOGON Feature");

          Socket two = connectGame(ports.game(), "two", "乙", certificationTwo);
          try (two) {
            List<WirePacket> twoEntry = readPackets(two, 9);
            assertEquals(List.of(ProtocolConstants.SM_NEWMAP, ProtocolConstants.SM_CHANGELIGHT,
                ProtocolConstants.SM_LOGON, ProtocolConstants.SM_FEATURECHANGED,
                ProtocolConstants.SM_USERNAME, ProtocolConstants.SM_MAPDESCRIPTION,
                ProtocolConstants.SM_TURN, ProtocolConstants.SM_ABILITY,
                ProtocolConstants.SM_SUBABILITY), idents(twoEntry));
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
  void softCloseKeepsTheCertificationForTheReSelectFlow() throws Exception {
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

        // A second GAME connection while the character is online must still fail entry
        // (the world's "player is already online" guard), which is what keeps a retained
        // certification from being quietly double-admitted. The first session's entry is
        // drained before the second connects so the guard's outcome is deterministic.
        try (Socket first = connectGame(ports.game(), "solo", "甲", certification)) {
          readPackets(first, 7);
          try (Socket replayed = connectGame(ports.game(), "solo", "甲", certification)) {
            WirePacket rejected = WireMessageCodec.readPacket(replayed.getInputStream());
            assertEquals(ProtocolConstants.SM_STARTFAIL, rejected.message().ident(),
                "a live session must block a duplicate GAME entry");
          }
        }
        assertEquals(0, awaitOnlinePlayers(world, 0), "both sessions must leave the world on disconnect");

        // mir2.exe's CM_SOFTCLOSE flow (ClMain.pas AppLogout -> tcSoftClose -> tcReSelConnect):
        // after the game socket closes, the client goes back to the SELECT gate and
        // re-queries/re-selects with the very same certification — the Delphi id-server
        // session survives the game entry (IdSrvClient.pas admission is a pure check), so
        // the re-query must succeed and a fresh GAME entry must be admitted.
        LegacyGateHandler.ConnectionState reselectState = new LegacyGateHandler.ConnectionState();
        WirePacket requery = handler.dispatch(GateKind.SELECT, reselectState,
            request(ProtocolConstants.CM_QUERYCHR, "solo/" + certification));
        assertEquals(ProtocolConstants.SM_QUERYCHR, requery.message().ident(),
            "the certification must survive the game entry for the soft-close re-select");

        WirePacket reselected = handler.dispatch(GateKind.SELECT, reselectState,
            request(ProtocolConstants.CM_SELCHR, "solo/甲"));
        assertEquals(ProtocolConstants.SM_STARTPLAY, reselected.message().ident());

        try (Socket reentered = connectGame(ports.game(), "solo", "甲", certification)) {
          List<WirePacket> entry = readPackets(reentered, 7);
          assertEquals(ProtocolConstants.SM_NEWMAP, entry.getFirst().message().ident(),
              "the same certification must re-enter the world after the re-select");
        }
        assertEquals(0, awaitOnlinePlayers(world, 0));
      }
    }
  }

  @Test
  void softClosePacketLeavesTheWorldWithoutAnAckAndTeardownIsIdempotent() throws Exception {
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

        try (Socket socket = connectGame(ports.game(), "solo", "甲", certification)) {
          readPackets(socket, 7);
          assertEquals(1, awaitOnlinePlayers(world, 1));
          // CM_SOFTCLOSE: recog/param/tag/series are all zero on the wire (ClMain.pas:1728).
          WireMessageCodec.writePacket(socket.getOutputStream(),
              new WirePacket(new DefaultMessage(0, ProtocolConstants.CM_SOFTCLOSE, 0, 0, 0)));
          // Delphi answers nothing (ObjBase.pas:4751 only raises m_boSoftClose); the only
          // tolerated traffic is a status frame, so wait for the world to ghost the player.
          assertEquals(0, awaitOnlinePlayers(world, 0),
              "CM_SOFTCLOSE must remove the player like MakeGhost on the next tick");
        }
        // The teardown path runs softClose again after the socket closes; it must be a no-op
        // rather than throwing for the already-departed player id.
        assertEquals(0, awaitOnlinePlayers(world, 0));
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
