package com.mir2.gate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Three-listener access layer. Each accepted socket runs in its own virtual thread. */
public final class GateServer implements AutoCloseable {
  private final GatePorts ports;
  private final BiConsumer<ClientConnection, IOException> handler;
  private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
  private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
  private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
  private volatile boolean running;

  public GateServer(GatePorts ports, BiConsumer<ClientConnection, IOException> handler) {
    this.ports = Objects.requireNonNull(ports);
    this.handler = Objects.requireNonNull(handler);
  }

  /** Creates a directly usable PoC server with login and character-selection routing. */
  public GateServer(GatePorts ports, SessionRouter router, LegacyGateHandler.Config config) {
    this(ports, new LegacyGateHandler(router, new GateSessionRegistry(), config, Throwable::printStackTrace));
  }

  /** Creates all three gates and connects authenticated GAME sessions to the world engine. */
  public GateServer(GatePorts ports, SessionRouter router, LegacyGateHandler.Config config,
      LegacyGateHandler.WorldConfig world) {
    this(ports, new LegacyGateHandler(
        router, new GateSessionRegistry(), config, Throwable::printStackTrace, world));
  }

  public synchronized void start() throws IOException {
    if (running) throw new IllegalStateException("already started");
    try {
      for (GateKind kind : GateKind.values()) {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(ports.port(kind)));
        listeners.add(server);
        connections.submit(() -> acceptLoop(server, kind));
      }
      running = true;
    } catch (IOException error) {
      close();
      throw error;
    }
  }

  private void acceptLoop(ServerSocket server, GateKind kind) {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        socket.setKeepAlive(true);
        ClientConnection connection = new ClientConnection(socket, kind);
        clients.add(connection);
        connections.submit(() -> {
          try {
            handler.accept(connection, null);
          } finally {
            clients.remove(connection);
            connection.close();
          }
        });
      } catch (IOException error) {
        if (!server.isClosed()) handler.accept(null, error);
      }
    }
  }

  public boolean isRunning() {
    return running;
  }

  @Override
  public synchronized void close() {
    running = false;
    for (ServerSocket listener : listeners) {
      try {
        listener.close();
      } catch (IOException ignored) {
        // Continue closing the remaining listeners.
      }
    }
    listeners.clear();
    for (ClientConnection client : clients) client.close();
    clients.clear();
    connections.shutdownNow();
  }
}
