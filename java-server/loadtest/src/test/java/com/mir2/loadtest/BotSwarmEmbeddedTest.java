package com.mir2.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the swarm against a real in-process {@link Mir2Server} assembly: seeded SQLite
 * accounts, three TCP gates, the world tick and the full client wire protocol.
 */
class BotSwarmEmbeddedTest {
  @TempDir
  Path tempDir;

  @Test
  void singleBotLogsInWalksFightsAndRelogs() throws Exception {
    try (Harness harness = start(1, 2, Duration.ofSeconds(5), Duration.ofSeconds(2))) {
      BotSwarm swarm = new BotSwarm(harness.spec());
      BotSwarm.Result result = swarm.run();

      assertEquals(0, result.metrics().totalErrors(), "no errors are acceptable");
      assertEquals(1, result.metrics().get(BotMetrics.Key.BOTS_ENTERED));
      assertTrue(result.metrics().get(BotMetrics.Key.GAME_ENTRIES) >= 2,
          "the bot must relog at least once");
      assertTrue(result.metrics().get(BotMetrics.Key.RELOGS_COMPLETED) >= 1);
      long moves = sent(BotMetrics.ActionKind.WALK, result) + sent(BotMetrics.ActionKind.RUN, result);
      assertTrue(moves >= 1, "the bot must move at least once");
      assertEquals(result.metrics().get(BotMetrics.Key.GAME_ENTRIES),
          result.metrics().get(BotMetrics.Key.BAG_QUERIES_SENT),
          "one bag query follows every successful game entry");
      assertTrue(harness.server().isRunning(), "the server must survive the bot");
    }
  }

  @Test
  void smallSwarmStaysStableWithMonstersAndWritesTheReport() throws Exception {
    int bots = 8;
    try (Harness harness = start(bots, 6, Duration.ofSeconds(6), Duration.ofSeconds(2))) {
      BotSwarm swarm = new BotSwarm(harness.spec());
      BotSwarm.Result result = swarm.run();

      assertEquals(0, result.metrics().totalErrors(), () ->
          "errors: " + result.metrics().counters());
      assertEquals(bots, result.metrics().get(BotMetrics.Key.BOTS_ENTERED));
      assertTrue(result.metrics().get(BotMetrics.Key.GAME_ENTRIES) >= bots);
      assertTrue(result.metrics().get(BotMetrics.Key.RELOGS_COMPLETED) >= 1,
          "the relog path must be exercised");
      assertTrue(BotReport.verdictPassed(result));
      assertTrue(harness.server().isRunning());

      Path reportDir = tempDir.resolve("reports");
      Path markdown = BotReport.writeAll(reportDir, result);
      assertTrue(Files.isRegularFile(markdown));
      assertTrue(Files.readString(markdown).contains("## 结论：PASS"));
      assertTrue(Files.isRegularFile(reportDir.resolve("report.csv")));
    }
  }

  @Test
  void seedAccountsIsIdempotent() throws Exception {
    Path database = tempDir.resolve("accounts.db");
    LoadtestMain.seedAccounts(database, "tb", 3, "pw");
    LoadtestMain.seedAccounts(database, "tb", 3, "pw");
    try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
      for (int index = 1; index <= 3; index++) {
        assertTrue(store.find(String.format("tb%04d", index)).isPresent());
      }
    }
  }

  // ------------------------------------------------------------ harness

  private static long sent(BotMetrics.ActionKind kind, BotSwarm.Result result) {
    BotMetrics.ActionStats stats = result.metrics().actions().get(kind);
    return stats == null ? 0 : stats.sent();
  }

  private record Harness(Mir2Server server, BotSwarm.Spec spec) implements AutoCloseable {
    @Override
    public void close() {
      server.close();
    }
  }

  private Harness start(int bots, int monsters, Duration duration, Duration relogEvery)
      throws Exception {
    Path database = tempDir.resolve("mir2-" + System.nanoTime() + ".db");
    LoadtestMain.seedAccounts(database, "tb", bots, "pw");
    GatePorts ports = freePorts();
    ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", "MIR2",
        null, "0", 20, 20, 50, monsters, "chicken", null, null);
    Mir2Server server = new Mir2Server(config);
    server.start();
    BotSwarm.Spec spec = new BotSwarm.Spec(bots, duration, Duration.ofMillis(40L * bots),
        "127.0.0.1", ports.login(), 0, 0, "MIR2", "tb", "pw",
        Duration.ofMillis(60), Duration.ofMillis(180), relogEvery, 42L);
    return new Harness(server, spec);
  }

  private static GatePorts freePorts() throws Exception {
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
