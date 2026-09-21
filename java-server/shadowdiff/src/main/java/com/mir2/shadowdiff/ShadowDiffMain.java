package com.mir2.shadowdiff;

import com.mir2.auth.AuthService;
import com.mir2.bootstrap.Mir2Server;
import com.mir2.bootstrap.ServerConfig;
import com.mir2.gate.GatePorts;
import com.mir2.persistence.SqliteStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shadow-comparison harness (P2 影子对拍, the last unstarted P2 row): drives two servers
 * with one deterministic operation stream over the real client wire protocol and diffs the
 * player-observable state after every op.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>embedded</b> — boots two independent in-process {@link Mir2Server} assemblies
 *       (separate SQLite files, separate ports, identical config) and compares them. This is
 *       the self-check skeleton: it proves the harness, the op stream and the differ, and it
 *       doubles as a determinism regression for the Java server itself.</li>
 *   <li><b>remote</b> — compares any two already-running servers given their login gates.
 *       Point one side at the Delphi stack (LoginGate 7000) and the other at the Java stack
 *       and the same script becomes the Delphi-vs-Java 对拍 the G3 gate requires. No Delphi
 *       process is spawned here; the harness only speaks the wire.</li>
 * </ul>
 */
public final class ShadowDiffMain {

  public static void main(String[] args) {
    int status;
    try {
      status = run(args);
    } catch (IllegalArgumentException usage) {
      System.err.println("[shadowdiff] " + usage.getMessage());
      System.err.println();
      System.err.println(USAGE);
      status = 2;
    } catch (Exception error) {
      System.err.println("[shadowdiff] failed: " + error);
      error.printStackTrace(System.err);
      status = 2;
    }
    System.exit(status);
  }

  static int run(String[] args) throws Exception {
    Map<String, String> options = Args.parse(args);
    if (options.containsKey("help")) {
      System.out.println(USAGE);
      return 0;
    }

    List<Op> script = loadScript(options);
    Duration settle = Duration.ofMillis(longOption(options, "settle-ms", 400));
    boolean strictMessages = options.containsKey("strict-messages");
    Path reportDir = Path.of(options.getOrDefault("report-dir", "reports"));
    String account = options.getOrDefault("account", "shadow01");
    String password = options.getOrDefault("password", "shadow-pw");
    String serverName = options.getOrDefault("server-name", "MIR2");

    if (options.containsKey("embedded")) {
      return runEmbedded(options, script, settle, strictMessages, reportDir,
          account, password, serverName);
    }
    return runRemote(options, script, settle, strictMessages, reportDir,
        account, password, serverName);
  }

  // ------------------------------------------------------------ modes

  private static int runEmbedded(Map<String, String> options, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    Path workDir = Files.createTempDirectory("mir2-shadowdiff-");
    long seed = longOption(options, "seed", 20260921);
    try (EmbeddedWorld left = EmbeddedWorld.boot(workDir.resolve("left"), "embedded-left",
            account, password, serverName, seed);
        EmbeddedWorld right = EmbeddedWorld.boot(workDir.resolve("right"), "embedded-right",
            account, password, serverName, seed)) {
      return compare(left.target(), right.target(), script, settle, strictMessages,
          reportDir, account, password, serverName);
    }
  }

  private static int runRemote(Map<String, String> options, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    String leftHost = require(options, "left-host");
    String rightHost = require(options, "right-host");
    WireTarget left = new WireTarget(
        options.getOrDefault("left-label", "left"),
        leftHost,
        (int) longOption(options, "left-login-port", GatePorts.DEFAULT_LOGIN),
        (int) longOption(options, "left-select-port", 0),
        (int) longOption(options, "left-game-port", 0));
    WireTarget right = new WireTarget(
        options.getOrDefault("right-label", "right"),
        rightHost,
        (int) longOption(options, "right-login-port", GatePorts.DEFAULT_LOGIN),
        (int) longOption(options, "right-select-port", 0),
        (int) longOption(options, "right-game-port", 0));
    return compare(left, right, script, settle, strictMessages, reportDir,
        account, password, serverName);
  }

  private static int compare(WireTarget left, WireTarget right, List<Op> script,
      Duration settle, boolean strictMessages, Path reportDir,
      String account, String password, String serverName) throws Exception {
    System.out.printf(Locale.ROOT, "[shadowdiff] left=%s (%s:%d) right=%s (%s:%d), %d ops%n",
        left.label(), left.host(), left.loginPort(),
        right.label(), right.host(), right.loginPort(), script.size());

    List<OpObservation> leftRun = observe(left, script, settle, account, password, serverName);
    List<OpObservation> rightRun = observe(right, script, settle, account, password, serverName);

    ShadowDiff.Result result = ShadowDiff.compare(left.label(), right.label(),
        leftRun, rightRun, strictMessages);
    Path markdown = ShadowReport.writeAll(reportDir, result, script);

    System.out.printf(Locale.ROOT,
        "[shadowdiff] verdict=%s state=%d acks=%d messages=%d, report: %s%n",
        result.passed() ? "PASS" : "FAIL",
        result.count(ShadowDiff.Severity.STATE),
        result.count(ShadowDiff.Severity.ACKS),
        result.count(ShadowDiff.Severity.MESSAGES),
        markdown.toAbsolutePath());
    return result.passed() ? 0 : 1;
  }

  /** Runs the whole script against one target: enter, ops, close. */
  static List<OpObservation> observe(WireTarget target, List<Op> script, Duration settle,
      String account, String password, String serverName) throws IOException {
    List<OpObservation> observations = new ArrayList<>(script.size() + 1);
    try (ShadowSession session = new ShadowSession(target, account, password,
        account, serverName, settle)) {
      observations.add(session.enter());
      for (Op op : script) {
        observations.add(session.perform(op));
      }
    }
    return observations;
  }

  // ------------------------------------------------------------ embedded worlds

  /** One in-process server with its own SQLite file and ports, seeded with the account. */
  private record EmbeddedWorld(Mir2Server server, WireTarget target) implements AutoCloseable {

    static EmbeddedWorld boot(Path directory, String label, String account, String password,
        String serverName, long seed) throws Exception {
      Files.createDirectories(directory);
      Path database = directory.resolve("mir2.db");
      try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
        AuthService auth = new AuthService(store);
        if (store.find(account).isEmpty()) auth.register(account, password);
      }
      GatePorts ports = freePorts();
      // Both worlds must share the map and spawn; monsters stay off so combat outcomes do
      // not depend on each server's independent Random. Deterministic PvE 对拍 needs the
      // seeded-random slice, noted in the module docs.
      ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", serverName,
          null, "0", 20, 20, 50, 0, "chicken", null, null);
      Mir2Server server = new Mir2Server(config);
      server.start();
      System.out.printf(Locale.ROOT,
          "[shadowdiff] %s up: login=%d select=%d game=%d db=%s (seed=%d)%n",
          label, ports.login(), ports.select(), ports.game(), database, seed);
      return new EmbeddedWorld(server, WireTarget.of(label, "127.0.0.1", ports.login()));
    }

    @Override
    public void close() {
      server.close();
    }
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

  // ------------------------------------------------------------ options

  private static List<Op> loadScript(Map<String, String> options) throws IOException {
    String scriptFile = options.get("script");
    if (scriptFile == null) return Op.defaultScript();
    return Op.parseScript(Files.readString(Path.of(scriptFile), StandardCharsets.UTF_8));
  }

  private static String require(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null) throw new IllegalArgumentException("--" + key + " is required");
    return value;
  }

  private static long longOption(Map<String, String> options, String key, long fallback) {
    String value = options.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }

  private static final class Args {
    static Map<String, String> parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index++) {
        String argument = args[index];
        if (!argument.startsWith("--"))
          throw new IllegalArgumentException("unexpected argument: " + argument);
        String key = argument.substring(2);
        if (key.equals("embedded") || key.equals("help") || key.equals("strict-messages")) {
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

  private static final String USAGE = """
      MIR2 shadowdiff — 影子对拍 harness（同一操作流驱动双服并 diff 状态快照）

      用法:
        java -jar mir2-shadowdiff.jar --embedded [选项]
            进程内拉起两个独立 Java 服务端（独立 SQLite + 独立端口 + 相同配置），
            回放同一操作流并对拍 —— 骨架自检 + Java 服务端确定性回归。

        java -jar mir2-shadowdiff.jar --left-host H1 --right-host H2 [选项]
            对拍两个已运行的服务端（Delphi 或 Java 均可，只要说 mir2.exe 的线上协议）。
            Delphi 侧就绪后：--left-host 指向 Delphi LoginGate，--right-host 指向 Java。

      选项:
        --script FILE          操作脚本（每行一个 op，# 注释；缺省用内置冒烟脚本）
                               op: turn/walk/run/hit/heavyhit/bighit <dir 0..7>,
                                   pickup, bag, say <text>, drop/eat/takeon/takeoff <物品名>,
                                   sleep <ms>, relog
        --settle-ms N          每个 op 后的静默窗口毫秒（默认 400；两台服务器共用）
        --strict-messages      消息集合差异也判 FAIL（默认仅提示）
        --report-dir DIR       报告目录（默认 reports，生成 shadow-report.md / .csv）
        --account S            测试账号（默认 shadow01；角色同名）
        --password S           密码（默认 shadow-pw；remote 模式须两边都能登录）
        --server-name NAME     服务器名（默认 MIR2，须与两边一致）
        --seed N               （embedded）预留的世界随机种子标注（默认 20260921）
        --left-label/-host/-login-port/-select-port/-game-port    左侧目标
        --right-label/-host/-login-port/-select-port/-game-port   右侧目标

      判定:
        STATE   状态快照差异（地图/坐标/朝向/HP/MP/等级/金币/背包/装备）→ FAIL
        ACKS    +GOOD/+FAIL 应答序列差异 → FAIL
        MESSAGES 服务端消息集合差异 → 提示（--strict-messages 时 FAIL）

      退出码: 0 = PASS, 1 = FAIL, 2 = 参数/环境错误
      """;

  private ShadowDiffMain() {}
}
