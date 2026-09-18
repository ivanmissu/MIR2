package com.mir2.bootstrap;

import com.mir2.gate.GatePorts;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mir2ServerTest {
  @Test
  void startsAllListenersAndCreatesDurableDatabase() throws Exception {
    int login = availablePort();
    int select = availablePort();
    int game = availablePort();
    while (select == login) select = availablePort();
    while (game == login || game == select) game = availablePort();
    Path directory = Files.createTempDirectory("mir2-bootstrap-");
    Path database = directory.resolve("server.db");
    ServerConfig config = new ServerConfig(database, new GatePorts(login, select, game),
        "127.0.0.1", "MIR2", "hero", "pw");

    try (Mir2Server server = new Mir2Server(config)) {
      server.start();
      assertTrue(server.isRunning());
      assertConnects(login);
      assertConnects(select);
      assertConnects(game);
      assertTrue(Files.isRegularFile(database));
    } finally {
      Files.deleteIfExists(database);
      Files.deleteIfExists(directory);
    }
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void assertConnects(int port) throws Exception {
    try (Socket ignored = new Socket("127.0.0.1", port)) {
      assertTrue(ignored.isConnected());
    }
  }
}
