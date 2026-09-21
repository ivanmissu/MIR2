package com.mir2.loadtest;

import com.mir2.auth.AuthService;
import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Command-line entry for the bot-swarm harness.
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@code --embedded} — boots the full production {@link Mir2Server} assembly in this
 *       process (temp database, random free ports, seeded bot accounts) and runs the swarm
 *       against it. This is the self-contained CI/G0 rehearsal mode.</li>
 *   <li>{@code --prepare-db <path>} — registers the bot accounts in a SQLite database and
 *       exits. Run this once <em>before</em> starting the real server, then use remote mode.</li>
 *   <li>remote (default) — runs against an already listening server. The select/game
 *       endpoints are discovered from the server's advertised routes, exactly like
 *       {@code mir2.exe}.</li>
 * </ul>
 *
 * <p>The G0 gate criterion is "50 bots × 1 hour stable": run with
 * {@code --bots 50 --duration PT1H}. The exit code is 0 on PASS, 1 on FAIL, 2 on usage or
 * setup errors.
 */
public final class LoadtestMain {

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (Exception error) {
      System.err.println("bot-swarm failed: " + error.getMessage());
      error.printStackTrace();
      exitCode = 2;
    }
    System.exit(exitCode);
  }

  static int run(String[] args) throws Exception {
    Map<String, String> options = Args.parse(args);
    if (options.containsKey("help")) {
      System.out.println(USAGE);
      return 0;
    }

    int bots = intOption(options, "bots", 50);
    String prefix = options.getOrDefault("account-prefix", "bot");
    String password = options.getOrDefault("account-password", "bot-pw");

    if (options.containsKey("prepare-db")) {
      Path database = Path.of(options.get("prepare-db"));
      seedAccounts(database, prefix, bots, password);
      System.out.printf(Locale.ROOT,
          "seeded %d bot accounts (%s0001..) into %s%n", bots, prefix, database);
      return 0;
    }

    BotSwarm.Spec requestedSpec = buildSpec(options, bots, prefix, password);
    Path reportDir = Path.of(options.getOrDefault("report-dir", "reports"));

    Mir2Server server = null;
    HeapSampler sampler = null;
    Thread serverHook = null;
    try {
      // The effective spec (with the embedded server's actual ports) is fixed before the
      // swarm starts, so the shutdown hook can capture it.
      BotSwarm.Spec spec;
      if (options.containsKey("embedded")) {
        EmbeddedServer embedded = startEmbeddedServer(options, requestedSpec);
        Mir2Server started = embedded.server();
        server = started;
        sampler = embedded.sampler();
        serverHook = new Thread(started::close, "bot-swarm-embedded-server-hook");
        Runtime.getRuntime().addShutdownHook(serverHook);
        spec = embedded.spec();
      } else {
        spec = requestedSpec;
      }

      BotSwarm swarm = new BotSwarm(spec);
      AtomicReference<Instant> botSwarmStartedAt = new AtomicReference<>(Instant.now());
      CountDownLatch runDone = new CountDownLatch(1);
      AtomicReference<BotSwarm.Result> resultRef = new AtomicReference<>();
      AtomicBoolean reportWritten = new AtomicBoolean(false);
      Thread hook = new Thread(() -> {
        swarm.requestStop();
        try {
          runDone.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          // Shutdown cannot wait forever; the partial metrics are still useful.
        }
        BotSwarm.Result result = resultRef.get();
        if (result == null) {
          // Interrupted mid-run: fall back to the live metrics so the partial report
          // still shows everything the swarm did before the signal.
          result = new BotSwarm.Result(spec, swarm.metrics().snapshot(),
              botSwarmStartedAt.get(), Instant.now(), true, swarm.notes());
        }
        writeReportQuietly(result, reportDir, reportWritten);
      }, "bot-swarm-shutdown-hook");
      Runtime.getRuntime().addShutdownHook(hook);

      BotSwarm.Result result;
      try {
        result = swarm.run();
        resultRef.set(result);
      } finally {
        runDone.countDown();
      }
      Runtime.getRuntime().removeShutdownHook(hook);

      if (server != null) {
        swarm.addNote("embedded server still running after the run: " + server.isRunning());
        swarm.addNote(sampler.summary());
      }
      // Re-wrap with the notes that were added after run() captured its own copy.
      result = new BotSwarm.Result(result.spec(), swarm.metrics().snapshot(), result.startedAt(),
          result.endedAt(), result.stoppedEarly(), swarm.notes());

      Path markdown = BotReport.writeAll(reportDir, result);
      reportWritten.set(true);
      printSummary(result, markdown);
      return BotReport.verdictPassed(result) ? 0 : 1;
    } finally {
      if (sampler != null) sampler.close();
      if (server != null) {
        server.close();
        if (serverHook != null) Runtime.getRuntime().removeShutdownHook(serverHook);
      }
    }
  }

  // ------------------------------------------------------------ modes

  private record EmbeddedServer(Mir2Server server, HeapSampler sampler, BotSwarm.Spec spec) {}

  private static EmbeddedServer startEmbeddedServer(Map<String, String> options,
      BotSwarm.Spec fallbackSpec) throws IOException {
    Path workDir = Files.createTempDirectory("mir2-botswarm-");
    Path database = workDir.resolve("mir2.db");
    seedAccounts(database, fallbackSpec.accountPrefix(), fallbackSpec.bots(),
        fallbackSpec.accountPassword());

    GatePorts ports = freePorts();
    int monsters = intOption(options, "monsters", 0);
    String monsterKind = options.getOrDefault("monster-kind", "chicken");
    int tickMillis = intOption(options, "tick-ms", 50);
    int spawnX = intOption(options, "spawn-x", 10);
    int spawnY = intOption(options, "spawn-y", 10);
    Path mapFile = options.containsKey("map-file") ? Path.of(options.get("map-file")) : null;
    // Every bot dials in from 127.0.0.1, so the whole swarm shares one AccessPolicy bucket.
    // The shipped per-IP defaults (128 active, 300 attempts/minute) are sized for real
    // players behind distinct addresses: a 50-bot run opens three connections per session
    // (login + select + game) and relogs on a timer, which lands right on top of the
    // 300/minute ceiling and makes the verdict depend on how many sessions happen to fit in
    // the window. Scale both limits with the swarm so the run measures the server, not the
    // anti-abuse guard. Real deployments keep the defaults.
    int loginsPerSession = 3;
    int maxActivePerIp = Math.max(128, fallbackSpec.bots() * loginsPerSession);
    // Budget every bot for a relog every 10s of the window, then double it for headroom.
    int attemptsPerWindow = Math.max(300, fallbackSpec.bots() * loginsPerSession * 12);
    ServerConfig config = new ServerConfig(database, ports, "127.0.0.1",
        fallbackSpec.serverName(), mapFile, null, "0", spawnX, spawnY, tickMillis, monsters,
        monsterKind, null, null, null, maxActivePerIp, attemptsPerWindow, 60, 900, 600, 0);
    Mir2Server server = new Mir2Server(config);
    server.start();

    BotSwarm.Spec spec = new BotSwarm.Spec(fallbackSpec.bots(), fallbackSpec.duration(),
        fallbackSpec.rampUp(), "127.0.0.1", ports.login(), 0, 0, fallbackSpec.serverName(),
        fallbackSpec.accountPrefix(), fallbackSpec.accountPassword(), fallbackSpec.thinkMin(),
        fallbackSpec.thinkMax(), fallbackSpec.relogEvery(), fallbackSpec.seed());
    HeapSampler sampler = new HeapSampler();
    sampler.start();
    System.out.printf(Locale.ROOT,
        "[bot-swarm] embedded server up: login=%d select=%d game=%d monsters=%dx%s map=%s%n",
        ports.login(), ports.select(), ports.game(), monsters, monsterKind,
        mapFile == null ? "PoC-empty" : mapFile.getFileName());
    return new EmbeddedServer(server, sampler, spec);
  }

  /** Registers {@code prefix0001..} accounts; safe to re-run, skips existing ones. */
  static void seedAccounts(Path database, String prefix, int count, String password)
      throws IOException {
    Path absolute = database.toAbsolutePath().normalize();
    if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
    try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + absolute)) {
      AuthService auth = new AuthService(store);
      for (int index = 1; index <= count; index++) {
        String username = String.format("%s%04d", prefix, index);
        if (store.find(username).isEmpty()) auth.register(username, password);
      }
    }
  }

  // ------------------------------------------------------------ options

  private static BotSwarm.Spec buildSpec(Map<String, String> options, int bots,
      String prefix, String password) {
    Duration duration = durationOption(options, "duration", Duration.ofMinutes(5));
    Duration ramp = durationOption(options, "ramp", Duration.ofSeconds(10));
    Duration relogEvery = durationOption(options, "relog-every", Duration.ofSeconds(45));
    Duration thinkMin = Duration.ofMillis(intOption(options, "think-min-ms", 300));
    Duration thinkMax = Duration.ofMillis(intOption(options, "think-max-ms", 900));
    long seed = longOption(options, "seed", 20260919L);
    String host = options.getOrDefault("host", "127.0.0.1");
    int loginPort = intOption(options, "login-port", GatePorts.DEFAULT_LOGIN);
    int selectPort = intOption(options, "select-port", 0);
    int gamePort = intOption(options, "game-port", 0);
    String serverName = options.getOrDefault("server-name", "MIR2");
    return new BotSwarm.Spec(bots, duration, ramp, host, loginPort, selectPort, gamePort,
        serverName, prefix, password, thinkMin, thinkMax, relogEvery, seed);
  }

  private static final class Args {
    static Map<String, String> parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index++) {
        String argument = args[index];
        if (!argument.startsWith("--"))
          throw new IllegalArgumentException("unexpected argument: " + argument);
        String key = argument.substring(2);
        if (key.equals("embedded") || key.equals("help")) {
          options.put(key, "true");
          continue;
        }
        if (index + 1 >= args.length)
          throw new IllegalArgumentException("--" + key + " requires a value");
        options.put(key, args[++index]);
      }
      return options;
    }
  }

  private static int intOption(Map<String, String> options, String key, int fallback) {
    String value = options.get(key);
    return value == null ? fallback : Integer.parseInt(value);
  }

  private static long longOption(Map<String, String> options, String key, long fallback) {
    String value = options.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }

  /** Accepts ISO-8601 ({@code PT1H}), {@code 500ms}/{@code 45s}/{@code 5m}/{@code 1h} or seconds. */
  private static Duration durationOption(Map<String, String> options, String key,
      Duration fallback) {
    String value = options.get(key);
    if (value == null) return fallback;
    try {
      return Duration.parse(value);
    } catch (RuntimeException notIso) {
      // fall through to the lenient suffix forms below
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    long amount;
    try {
      if (normalized.endsWith("ms")) return Duration.ofMillis(Long.parseLong(chop(normalized, 2)));
      if (normalized.endsWith("s")) return Duration.ofSeconds(Long.parseLong(chop(normalized, 1)));
      if (normalized.endsWith("m")) return Duration.ofMinutes(Long.parseLong(chop(normalized, 1)));
      if (normalized.endsWith("h")) return Duration.ofHours(Long.parseLong(chop(normalized, 1)));
      amount = Long.parseLong(normalized);
    } catch (RuntimeException notNumeric) {
      throw new IllegalArgumentException("--" + key + " is not a duration: " + value);
    }
    return Duration.ofSeconds(amount);
  }

  private static String chop(String value, int suffixLength) {
    return value.substring(0, value.length() - suffixLength).trim();
  }

  private static GatePorts freePorts() throws IOException {
    int login = availablePort();
    int select = availablePort();
    int game = availablePort();
    while (select == login) select = availablePort();
    while (game == login || game == select) game = availablePort();
    return new GatePorts(login, select, game);
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  // ------------------------------------------------------------ reporting

  private static void writeReportQuietly(BotSwarm.Result result, Path reportDir,
      AtomicBoolean reportWritten) {
    if (!reportWritten.compareAndSet(false, true)) return;
    try {
      Path markdown = BotReport.writeAll(reportDir, result);
      System.err.println("[bot-swarm] partial report written to " + markdown);
    } catch (IOException error) {
      System.err.println("[bot-swarm] failed to write report: " + error);
    }
  }

  private static void printSummary(BotSwarm.Result result, Path markdown) {
    BotMetrics.Snapshot metrics = result.metrics();
    long sent = metrics.actions().values().stream()
        .mapToLong(BotMetrics.ActionStats::sent).sum();
    long received = metrics.receivedByIdent().stream().mapToLong(Map.Entry::getValue).sum();
    System.out.println();
    System.out.println("==== bot-swarm summary ====");
    System.out.printf(Locale.ROOT, "verdict: %s (errors=%d)%n",
        BotReport.verdictPassed(result) ? "PASS" : "FAIL", metrics.totalErrors());
    System.out.printf(Locale.ROOT, "bots entered: %d/%d, game entries=%d, relogs=%d%n",
        metrics.get(BotMetrics.Key.BOTS_ENTERED), result.spec().bots(),
        metrics.get(BotMetrics.Key.GAME_ENTRIES), metrics.get(BotMetrics.Key.RELOGS_COMPLETED));
    System.out.printf(Locale.ROOT, "actions sent=%d, packets received=%d%n", sent, received);
    System.out.println("report: " + markdown.toAbsolutePath());
  }

  /** Samples the (shared, embedded-mode) JVM heap so the report can show a growth trend. */
  private static final class HeapSampler implements AutoCloseable {
    private final List<Long> usedMegabytes = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;

    void start() {
      running = true;
      thread = Thread.ofVirtual().name("bot-swarm-heap-sampler").start(() -> {
        while (running) {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException interrupted) {
            return;
          }
          Runtime runtime = Runtime.getRuntime();
          long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
          synchronized (usedMegabytes) {
            usedMegabytes.add(used);
          }
        }
      });
    }

    String summary() {
      synchronized (usedMegabytes) {
        if (usedMegabytes.isEmpty()) return "JVM 堆无采样";
        long max = usedMegabytes.stream().mapToLong(Long::longValue).max().orElse(0);
        long last = usedMegabytes.getLast();
        long limit = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return String.format(Locale.ROOT,
            "embedded 进程 JVM 堆使用峰值 %d MB、结束 %d MB / 上限 %d MB（%d 次采样）",
            max, last, limit, usedMegabytes.size());
      }
    }

    @Override
    public void close() {
      running = false;
      if (thread != null) thread.interrupt();
    }
  }

  private static final String USAGE = """
      MIR2 bot-swarm — G0 稳定性压测工具（走真实 7000/7100/7200 TCP 三端口全链路）

      用法:
        java -jar mir2-loadtest.jar --embedded [选项]          # 进程内拉起完整 Mir2Server 再压测
        java -jar mir2-loadtest.jar --prepare-db <db> [选项]   # 先播种机器人账号（服务端启动前执行）
        java -jar mir2-loadtest.jar --host H --login-port P [选项]  # 压测已运行的服务端

      G0 正式跑法（50 机器人 × 1 小时）:
        java -jar mir2-loadtest.jar --embedded --bots 50 --duration PT1H --monsters 24

      选项:
        --bots N               机器人数量（默认 50）
        --duration D           运行时长，ISO-8601 或纯秒数（默认 PT5M）
        --ramp D               全员上线用时（默认 PT10S）
        --relog-every D        每个机器人的重登周期（默认 PT45S，0 禁用不支持，设很大即可）
        --think-min-ms N       动作最小间隔（默认 300）
        --think-max-ms N       动作最大间隔（默认 900）
        --seed N               随机种子（默认 20260919）
        --host H               登录网关地址（默认 127.0.0.1）
        --login-port P         登录网关端口（默认 7000）
        --select-port P        选人端口覆盖（默认 0 = 按服务端广播地址，与真实客户端一致）
        --game-port P          游戏端口覆盖（默认 0 = 按服务端广播地址）
        --server-name NAME     须与服务端 MIR2_SERVER_NAME 一致（默认 MIR2）
        --account-prefix S     机器人账号前缀（默认 bot）
        --account-password S   机器人账号密码（默认 bot-pw）
        --report-dir DIR       报告目录（默认 reports，生成 report.md / report.csv）
        --monsters N           （embedded）初始怪物数量（默认 0）
        --monster-kind KIND    （embedded）首批 10 种模板名（chicken/deer/scarecrow/hookcat/rakecat/cavemaggot/scorpion/orc/orcwarrior/orcfighter，默认 chicken）
        --map-file PATH        （embedded）Delphi .map 地图（默认 PoC 空图）
        --tick-ms N            （embedded）世界 tick 毫秒（默认 50）
        --spawn-x N/--spawn-y N（embedded）出生点（默认 10 10）

      退出码: 0 = PASS, 1 = FAIL, 2 = 参数/环境错误
      """;
}
