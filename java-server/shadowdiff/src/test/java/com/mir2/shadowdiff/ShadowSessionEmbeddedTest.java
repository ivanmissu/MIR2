package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.auth.AuthService;
import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the shadow harness against real in-process {@link Mir2Server} assemblies over
 * real TCP: the same three-gate chain, RunLogin, ops and drain windows the CLI uses.
 */
class ShadowSessionEmbeddedTest {
  private static final Duration SETTLE = Duration.ofMillis(300);

  @TempDir
  Path tempDir;

  @Test
  void twoIdenticallyConfiguredServersProduceIdenticalObservations() throws Exception {
    List<Op> script = Op.parseScript("""
        bag
        turn 2
        walk 2
        walk 4
        run 6
        hit 0
        pickup
        say @who
        drop 木剑
        relog
        walk 4
        """);
    try (World left = World.boot(tempDir.resolve("left"));
        World right = World.boot(tempDir.resolve("right"))) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right",
          leftRun, rightRun, true);
      assertTrue(result.passed(), () -> ShadowReport.markdown(result, script));
      assertEquals(script.size() + 1, result.entries().size(), "enter plus every op");
    }
  }

  @Test
  void divergentWorldsAreCaught() throws Exception {
    // The right-hand server spawns the player elsewhere: the very first snapshot differs,
    // proving the diff is a real comparison and not vacuously green.
    List<Op> script = Op.parseScript("walk 4");
    try (World left = World.boot(tempDir.resolve("left"));
        World right = World.boot(tempDir.resolve("right"), 30, 30)) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right",
          leftRun, rightRun, false);
      assertEquals(ShadowDiff.Severity.STATE, result.entries().get(0).severity());
      assertTrue(result.count(ShadowDiff.Severity.STATE) >= 1);
      assertTrue(!result.passed());
    }
  }

  @Test
  void sessionTracksWireStateThroughTheEntryChain() throws Exception {
    try (World world = World.boot(tempDir.resolve("solo"))) {
      List<OpObservation> run = ShadowDiffMain.observe(world.target(),
          Op.parseScript("walk 4\nturn 2"), SETTLE, "shadow01", "shadow-pw", "MIR2");

      OpObservation entry = run.get(0);
      assertEquals("enter", entry.op());
      assertTrue(entry.messages().contains("SM_NEWMAP"));
      assertTrue(entry.messages().contains("SM_LOGON"));
      assertTrue(entry.messages().contains("SM_MAPDESCRIPTION"));
      assertEquals("0", entry.state().mapId());
      assertEquals(20, entry.state().x());
      assertEquals(20, entry.state().y());
      // The bootstrap SM_ABILITY carries the pools of the untouched level-1 block.
      assertEquals(1, entry.state().level());
      assertEquals(15, entry.state().hp());
      assertEquals(15, entry.state().maxHp());
      assertEquals(0, entry.state().gold());

      OpObservation walk = run.get(1);
      assertEquals(List.of("+GOOD"), walk.acks());
      assertEquals(21, walk.state().y(), "an accepted walk south moves the belief");
      assertNotEquals(walk.state(), entry.state());

      OpObservation turn = run.get(2);
      assertEquals(List.of("+GOOD"), turn.acks());
      assertEquals(2, turn.state().direction());
    }
  }

  // ------------------------------------------------------------ harness

  private record World(Mir2Server server, WireTarget target) implements AutoCloseable {

    static World boot(Path directory) throws Exception {
      return boot(directory, 20, 20);
    }

    static World boot(Path directory, int spawnX, int spawnY) throws Exception {
      Path database = directory.resolve("mir2.db");
      java.nio.file.Files.createDirectories(directory);
      try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
        AuthService auth = new AuthService(store);
        if (store.find("shadow01").isEmpty()) auth.register("shadow01", "shadow-pw");
      }
      GatePorts ports = freePorts();
      ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", "MIR2",
          null, "0", spawnX, spawnY, 50, 0, "chicken", null, null);
      Mir2Server server = new Mir2Server(config);
      server.start();
      return new World(server, WireTarget.of(directory.getFileName().toString(),
          "127.0.0.1", ports.login()));
    }

    @Override
    public void close() {
      server.close();
    }
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
