package com.mir2.bootstrap;

import com.mir2.auth.AuthService;
import com.mir2.character.CharacterService;
import com.mir2.gate.GateServer;
import com.mir2.gate.SessionRouter;
import com.mir2.persistence.SqliteStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Owns the process-wide services and their shutdown order. */
public final class Mir2Server implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(Mir2Server.class.getName());

  private final ServerConfig config;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private SqliteStore store;
  private GateServer gates;

  public Mir2Server(ServerConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  public void start() throws IOException {
    if (!started.compareAndSet(false, true)) throw new IllegalStateException("server already started");
    if (closed.get()) throw new IllegalStateException("server already closed");

    try {
      Path database = config.database().toAbsolutePath().normalize();
      Path parent = database.getParent();
      if (parent != null) Files.createDirectories(parent);
      store = new SqliteStore("jdbc:sqlite:" + database);

      AuthService auth = new AuthService(store);
      if (config.bootstrapUser() != null && store.find(config.bootstrapUser()).isEmpty()) {
        auth.register(config.bootstrapUser(), config.bootstrapPassword());
        LOG.info(() -> "Created bootstrap account '" + config.bootstrapUser() + "'");
      }

      CharacterService characters = new CharacterService(store);
      gates = new GateServer(config.ports(), new SessionRouter(auth, characters), config.gateConfig());
      gates.start();
      LOG.info(() -> "MIR2 Java server started: login=" + config.ports().login()
          + ", select=" + config.ports().select() + ", game=" + config.ports().game()
          + ", advertisedHost=" + config.advertisedHost() + ", database=" + database);
    } catch (IOException | RuntimeException error) {
      close();
      throw error;
    }
  }

  public boolean isRunning() {
    return gates != null && gates.isRunning() && !closed.get();
  }

  public void awaitShutdown() throws InterruptedException {
    stopped.await();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (gates != null) gates.close();
    if (store != null) store.close();
    stopped.countDown();
    LOG.info("MIR2 Java server stopped");
  }
}
