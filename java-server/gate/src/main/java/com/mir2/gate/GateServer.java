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
  private final AccessPolicy accessPolicy;
  private final PacketSizePolicy packetSizePolicy;
  private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
  private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
  private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
  private volatile boolean running;

  public GateServer(GatePorts ports, BiConsumer<ClientConnection, IOException> handler) {
    this(ports, handler, new AccessPolicy());
  }

  public GateServer(GatePorts ports, BiConsumer<ClientConnection, IOException> handler,
      AccessPolicy accessPolicy) {
    this(ports, handler, accessPolicy, PacketSizePolicy.defaults());
  }

  public GateServer(GatePorts ports, BiConsumer<ClientConnection, IOException> handler,
      AccessPolicy accessPolicy, PacketSizePolicy packetSizePolicy) {
    this.ports = Objects.requireNonNull(ports);
    this.handler = Objects.requireNonNull(handler);
    this.accessPolicy = Objects.requireNonNull(accessPolicy);
    this.packetSizePolicy = Objects.requireNonNull(packetSizePolicy);
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

  public GateServer(GatePorts ports, SessionRouter router, LegacyGateHandler.Config config,
      LegacyGateHandler.WorldConfig world, AccessPolicy accessPolicy) {
    this(ports, new LegacyGateHandler(
        router, new GateSessionRegistry(), config, Throwable::printStackTrace, world), accessPolicy);
  }

  /** Full wiring: admission guard plus RunGate's per-read size/count guard on the game gate. */
  public GateServer(GatePorts ports, SessionRouter router, LegacyGateHandler.Config config,
      LegacyGateHandler.WorldConfig world, AccessPolicy accessPolicy,
      PacketSizePolicy packetSizePolicy) {
    this(ports, new LegacyGateHandler(
        router, new GateSessionRegistry(), config, Throwable::printStackTrace, world),
        accessPolicy, packetSizePolicy);
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
        AccessPolicy.Denial[] denial = new AccessPolicy.Denial[1];
        AccessPolicy.Permit permit =
            accessPolicy.tryAcquire(socket.getRemoteSocketAddress(), denial);
        if (permit == null) {
          // Delphi's admission failure path: apply BlockMethod, then close. A ban (mBlock /
          // mBlockList) also runs CloseConnect, dropping this address's other live sockets.
          String ip = BlockIpList.ip(socket.getRemoteSocketAddress());
          if (denial[0] != AccessPolicy.Denial.BLOCKED && accessPolicy.applyBlockMethod(ip)) {
            closeConnectionsFrom(ip);
          }
          socket.close();
          continue;
        }
        socket.setKeepAlive(true);
        socket.setSoTimeout(Math.toIntExact(accessPolicy.idleTimeout().toMillis()));
        ClientConnection connection = new ClientConnection(socket, kind, readBurstPolicy(kind));
        clients.add(connection);
        connections.submit(() -> {
          try {
            handler.accept(connection, null);
          } finally {
            // RunGate/Main.pas:986-1001 — a read tripped the size/count guard while
            // bokickOverPacketSize was set: apply BlockMethod, then drop every socket held by
            // that address. Checked as a flag rather than caught, because the handler's read
            // loop swallows IOException and the kick never escapes it.
            if (connection.oversizedReadDetected()) {
              String ip = BlockIpList.ip(connection.remoteAddress());
              if (accessPolicy.applyBlockMethod(ip)) closeConnectionsFrom(ip);
            }
            clients.remove(connection);
            connection.close();
            permit.close();
          }
        });
      } catch (IOException error) {
        if (!server.isClosed()) handler.accept(null, error);
      }
    }
  }

  /**
   * Only the game gate inspects read sizes — Delphi's guard lives in RunGate alone; LoginGate
   * and SelGate read without any size test (LoginGate/Main.pas:290-327).
   */
  private PacketSizePolicy readBurstPolicy(GateKind kind) {
    return kind == GateKind.GAME ? packetSizePolicy : null;
  }

  /**
   * {@code CloseConnect(sIPaddr)} (RunGate/Main.pas:1290-1307): closes every connection held by
   * an address, which is what makes a {@code mBlock}/{@code mBlockList} ban take effect at once
   * instead of only on the offender's next connect.
   */
  private void closeConnectionsFrom(String ip) {
    for (ClientConnection client : clients) {
      if (ip.equals(BlockIpList.ip(client.remoteAddress()))) client.close();
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
