package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.auth.AuthService;
import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import com.mir2.world.WorldClock;
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
  /** Any fixed value; both sides of a comparison must merely agree on it. */
  private static final long SEED = 20260922L;

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

  @Test
  void seededWorldsAgreeOnPveDamageAgainstTheTrainerDummy() throws Exception {
    // The W17 payoff: with a pinned world seed and stationary dummies, a combat script
    // produces byte-identical damage on two independent server processes. Before the
    // seed split this could not even be attempted — the harness had to run monsterless.
    List<Op> script = Op.pveScript();
    try (World left = World.bootSeeded(tempDir.resolve("pve-left"), SEED, 8, "trainer");
        World right = World.bootSeeded(tempDir.resolve("pve-right"), SEED, 8, "trainer")) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right", leftRun, rightRun, false);
      assertTrue(result.passed(), () -> ShadowReport.markdown(result, script));

      // The run must actually have fought something, otherwise the PASS is vacuous.
      List<String> combat = leftRun.stream()
          .flatMap(observation -> observation.state().combat().stream()).toList();
      assertTrue(combat.stream().anyMatch(line -> line.startsWith("struck other")),
          () -> "the script must land blows on a dummy, saw: " + combat);
      assertTrue(combat.stream().anyMatch(line -> line.contains("dmg=")
              && !line.contains("dmg=0")),
          () -> "at least one blow must deal non-zero damage, saw: " + combat);
    }
  }

  @Test
  void differentSeedsAreCaughtByTheCombatDiff() throws Exception {
    // Proves the PvE comparison is a real measurement: two worlds that differ ONLY in
    // their damage seed must be reported as divergent. If this ever goes green the
    // harness has stopped observing combat.
    List<Op> script = Op.pveScript();
    try (World left = World.bootSeeded(tempDir.resolve("seed-a"), SEED, 8, "trainer");
        World right = World.bootSeeded(tempDir.resolve("seed-b"), SEED + 7_919, 8, "trainer")) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right", leftRun, rightRun, false);
      assertTrue(result.count(ShadowDiff.Severity.STATE) >= 1,
          () -> "a different damage seed must surface as a state difference:\n"
              + ShadowReport.markdown(result, script));
      assertTrue(result.entries().stream()
              .flatMap(entry -> entry.details().stream())
              .anyMatch(detail -> detail.startsWith("combat:")),
          "the difference must be attributed to the combat observations");
    }
  }

  @Test
  void manualClockWorldsAgreeOnMovingMonsterAi() throws Exception {
    // The W23 payoff. W17 could only compare a stationary trainer dummy, because a walking
    // monster's cadence is an interval check against the clock and two processes never read
    // the same wall clock. With a MANUAL world clock, world time is what the script pumps,
    // so the Nth AI decision happens after the same tick on both sides.
    List<Op> script = Op.aiScript();
    try (World left = World.bootManual(tempDir.resolve("ai-left"), SEED, 4, "chicken");
        World right = World.bootManual(tempDir.resolve("ai-right"), SEED, 4, "chicken")) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right", leftRun, rightRun, false);
      assertTrue(result.passed(), () -> ShadowReport.markdown(result, script));

      // Non-vacuity, part 1: world time is exactly what the script pumped and nothing else.
      // Walking the script alongside the observations pins both halves at once — that time
      // moved (so the worlds were not frozen) and that nothing else moved it (so the host
      // scheduler contributed no ticks of its own).
      assertWorldTimeTracksTheScript(script, leftRun);
      assertWorldTimeTracksTheScript(script, rightRun);

      // Non-vacuity, part 2: the monsters actually moved. A PASS over four motionless
      // chickens would say nothing about AI determinism.
      List<String> censuses = leftRun.stream()
          .map(observation -> String.join(";", observation.state().neighbours())).toList();
      assertTrue(censuses.stream().distinct().count() > 1,
          () -> "the monster census must change across the run, saw: " + censuses);
      assertTrue(censuses.stream().anyMatch(line -> !line.isEmpty()),
          "the player must see monsters at all");
    }
  }

  @Test
  void divergentAiIsCaughtEvenOnAManualClock() throws Exception {
    // The negative control for the test above: identical pumping, different damage seed.
    // A manual clock removes the timing race, not the comparison — if this goes green the
    // AI observations have stopped being observed.
    List<Op> script = Op.aiScript();
    // 99999 is a seed the CLI run of --right-seed showed diverging at `hit 6` (dmg 2 vs 1).
    // Not every seed pair diverges within one short script — chickens die in few blows and
    // many rolls land on the same value — so the control pins a pair that demonstrably does.
    try (World left = World.bootManual(tempDir.resolve("ai-seed-a"), SEED, 4, "chicken");
        World right = World.bootManual(tempDir.resolve("ai-seed-b"), 99_999L, 4, "chicken")) {
      List<OpObservation> leftRun = ShadowDiffMain.observe(left.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");
      List<OpObservation> rightRun = ShadowDiffMain.observe(right.target(), script, SETTLE,
          "shadow01", "shadow-pw", "MIR2");

      ShadowDiff.Result result = ShadowDiff.compare("left", "right", leftRun, rightRun, false);
      assertTrue(result.count(ShadowDiff.Severity.STATE) >= 1,
          () -> "a different world seed must surface while comparing live AI:\n"
              + ShadowReport.markdown(result, script));
    }
  }

  /**
   * Asserts that every observed {@code worldTime} equals the epoch plus exactly the ticks the
   * script had pumped by that point: world time is a scripted quantity, not a race.
   */
  private static void assertWorldTimeTracksTheScript(List<Op> script,
      List<OpObservation> run) {
    long pumped = 0;
    long epoch = Long.MIN_VALUE;
    int observed = 0;
    for (int index = 0; index < run.size(); index++) {
      // run = [enter] + one observation per op, so op i is described by observation i+1.
      if (index > 0 && script.get(index - 1).kind() == Op.Kind.TICK) {
        pumped += script.get(index - 1).millis();
      }
      long worldTime = run.get(index).state().worldTime();
      if (worldTime < 0) continue; // before the first pump the server has echoed no time yet
      if (epoch == Long.MIN_VALUE) epoch = worldTime - pumped * 50;
      assertEquals(epoch + pumped * 50, worldTime,
          "world time after " + (index == 0 ? "enter" : script.get(index - 1).describe()));
      observed++;
    }
    assertTrue(observed > 1, "the run must observe world time more than once");
    assertTrue(pumped > 0, "the script must pump world time at all");
  }

  // ------------------------------------------------------------ harness

  private record World(Mir2Server server, WireTarget target) implements AutoCloseable {

    static World boot(Path directory) throws Exception {
      return boot(directory, 20, 20);
    }

    static World boot(Path directory, int spawnX, int spawnY) throws Exception {
      return boot(directory, spawnX, spawnY, null, 0, "chicken");
    }

    /** Boots a world with a pinned seed and a ring of monsters (the PvE comparison setup). */
    static World bootSeeded(Path directory, long seed, int monsters, String monsterKind)
        throws Exception {
      return boot(directory, 20, 20, seed, monsters, monsterKind, WorldClock.Mode.SYSTEM);
    }

    /**
     * Boots a world whose time only moves when the script says so — the W23 setup that makes
     * <em>moving</em> monsters comparable.
     */
    static World bootManual(Path directory, long seed, int monsters, String monsterKind)
        throws Exception {
      return boot(directory, 20, 20, seed, monsters, monsterKind, WorldClock.Mode.MANUAL);
    }

    static World boot(Path directory, int spawnX, int spawnY, Long seed, int monsters,
        String monsterKind) throws Exception {
      return boot(directory, spawnX, spawnY, seed, monsters, monsterKind, WorldClock.Mode.SYSTEM);
    }

    static World boot(Path directory, int spawnX, int spawnY, Long seed, int monsters,
        String monsterKind, WorldClock.Mode clockMode) throws Exception {
      Path database = directory.resolve("mir2.db");
      java.nio.file.Files.createDirectories(directory);
      try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
        AuthService auth = new AuthService(store);
        if (store.find("shadow01").isEmpty()) auth.register("shadow01", "shadow-pw");
      }
      GatePorts ports = freePorts();
      // Same as ShadowDiffMain: the dummies have to stand within the PvE script's reach.
      ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", "MIR2",
          null, "0", spawnX, spawnY, 50, monsters, monsterKind, null, null, seed)
          .withSafeZoneSize(0)
          .withWorldClockMode(clockMode);
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
