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
    // Negative control: deliberately mis-seed the right-hand world so the harness must
    // report differences. A comparison that still passes with divergent seeds is not
    // observing anything, which is the failure mode this option exists to rule out.
    long rightSeed = longOption(options, "right-seed", seed);
    // Stationary trainer dummies by default: they neither chase nor retaliate, so the only
    // thing a `hit` op depends on is the seeded damage stream — the whole point of the PvE
    // comparison. `--monster-kind` can point at a walking monster, but then only the
    // clock-independent parts of the state stay comparable.
    boolean ai = options.containsKey("ai");
    int monsters = (int) longOption(options, "monsters",
        ai ? 4 : options.containsKey("pve") ? 8 : 0);
    // --ai compares *moving* monsters, so it defaults to a walking template and a manual
    // world clock: world time then only advances on `tick` ops, making every AI decision a
    // scripted quantity rather than a race between two hosts.
    String monsterKind = options.getOrDefault("monster-kind", ai ? "chicken" : "trainer");
    com.mir2.world.WorldClock.Mode clockMode = ai
        ? com.mir2.world.WorldClock.Mode.MANUAL
        : com.mir2.world.WorldClock.Mode.SYSTEM;
    try (EmbeddedWorld left = EmbeddedWorld.boot(workDir.resolve("left"), "embedded-left",
            account, password, serverName, seed, monsters, monsterKind, clockMode);
        EmbeddedWorld right = EmbeddedWorld.boot(workDir.resolve("right"), "embedded-right",
            account, password, serverName, rightSeed, monsters, monsterKind, clockMode)) {
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
        String serverName, long seed, int monsters, String monsterKind,
        com.mir2.world.WorldClock.Mode clockMode) throws Exception {
      Files.createDirectories(directory);
      Path database = directory.resolve("mir2.db");
      try (SqliteStore store = new SqliteStore("jdbc:sqlite:" + database.toAbsolutePath())) {
        AuthService auth = new AuthService(store);
        if (store.find(account).isEmpty()) auth.register(account, password);
      }
      GatePorts ports = freePorts();
      // Both worlds share the map, the spawn cell AND the world seed: with MIR2_WORLD_SEED
      // pinned, each subsystem draws from its own stream (WorldRandom), so the Nth damage
      // roll is the same on both servers regardless of how the clock-driven subsystems
      // interleave. That is what makes the PvE ops below comparable at all.
      // The comparison worlds deliberately seat their dummies right next to the spawn so the
      // PvE script can reach them, so the start-point safe zone is switched off here. A real
      // deployment keeps the shipped nSafeZoneSize=10.
      ServerConfig config = new ServerConfig(database, ports, "127.0.0.1", serverName,
          null, "0", 20, 20, 50, monsters, monsterKind, null, null, seed)
          .withSafeZoneSize(0)
          .withWorldClockMode(clockMode);
      Mir2Server server = new Mir2Server(config);
      server.start();
      System.out.printf(Locale.ROOT,
          "[shadowdiff] %s up: login=%d select=%d game=%d db=%s seed=%d monsters=%dx%s clock=%s%n",
          label, ports.login(), ports.select(), ports.game(), database, seed,
          monsters, monsterKind, clockMode.name().toLowerCase(Locale.ROOT));
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
    if (scriptFile != null) {
      return Op.parseScript(Files.readString(Path.of(scriptFile), StandardCharsets.UTF_8));
    }
    // --pve selects the built-in combat script; it only means anything with a pinned seed
    // and trainer dummies, which is exactly what --pve configures below.
    if (options.containsKey("ai")) return Op.aiScript();
    return options.containsKey("pve") ? Op.pveScript() : Op.defaultScript();
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

  /** Package-private so {@code ShadowDiffMainArgsTest} can pin the value-less flag set. */
  static final class Args {
    /**
     * Value-less switches. A flag missing from this set silently swallows the next
     * argument, which is how {@code --ai --monsters 4} once died on "unexpected argument:
     * 4" — every new switch has to be listed here.
     */
    static final java.util.Set<String> FLAGS =
        java.util.Set.of("embedded", "help", "strict-messages", "pve", "ai");

    static Map<String, String> parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index++) {
        String argument = args[index];
        if (!argument.startsWith("--"))
          throw new IllegalArgumentException("unexpected argument: " + argument);
        String key = argument.substring(2);
        if (FLAGS.contains(key)) {
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
                                   sleep <ms>, tick <N>, relog
                               tick <N> = 把 MANUAL 世界时钟推进 N 个 tick（经 @tick 命令），
                               普通时钟下服务端拒绝执行，脚本仍可跑但不推进时间。
        --settle-ms N          每个 op 后的静默窗口毫秒（默认 400；两台服务器共用）
        --strict-messages      消息集合差异也判 FAIL（默认仅提示）
        --report-dir DIR       报告目录（默认 reports，生成 shadow-report.md / .csv）
        --account S            测试账号（默认 shadow01；角色同名）
        --password S           密码（默认 shadow-pw；remote 模式须两边都能登录）
        --server-name NAME     服务器名（默认 MIR2，须与两边一致）
        --seed N               （embedded）世界随机种子（默认 20260921）。两侧共用同一个种子，
                               伤害/掉落等各自独立成流，PvE 对拍才可复现；生产默认不设种子
                               （等价 Delphi 的全局 Random）。remote 模式请用 MIR2_WORLD_SEED
                               给两台服务端配同一个值。
        --pve                  用内置 PvE 对拍脚本（走到木桩前连续攻击），并默认放 8 个木桩。
                               需要两侧同种子；这是「静止怪对拍」的开箱即用入口。
        --ai                   （embedded）**会动的怪对拍**（W23）：两侧世界改用 MANUAL 世界时钟
                               （时间只在 tick op 推进），默认放 4 只鸡并用内置 AI 脚本。
                               怪物走位/攻击节拍因此只由 tick 数决定，与两台主机的墙钟无关；
                               状态快照新增 near=（视野内其它角色的格子+朝向）与 worldTime=。
                               remote 模式需要两台服务端都以 MANUAL 时钟启动（非生产配置）。
        --right-seed N         （embedded）只给右侧换种子 —— 负向对照：对拍必须因此 FAIL，
                               用来证明本次判定不是空转。
        --monsters N           （embedded）出生点周围放 N 只怪（默认 0；--pve 时默认 8，--ai 时默认 4）
        --monster-kind NAME    （embedded）怪物模板（默认 trainer/木桩：站桩不还手，
                               行为与墙钟无关，是唯一可确定性对拍的 PvE 目标）
        --left-label/-host/-login-port/-select-port/-game-port    左侧目标
        --right-label/-host/-login-port/-select-port/-game-port   右侧目标

      判定:
        STATE   状态快照差异（地图/坐标/朝向/HP/MP/等级/经验/金币/背包/装备/战斗/视野内角色/世界时间）→ FAIL
                战斗 = 本 op 期间观测到的 SM_STRUCK 伤害与 HP、SM_DEATH、SM_WINEXP
        ACKS    +GOOD/+FAIL 应答序列差异 → FAIL
        MESSAGES 服务端消息集合差异 → 提示（--strict-messages 时 FAIL）

      退出码: 0 = PASS, 1 = FAIL, 2 = 参数/环境错误
      """;

  private ShadowDiffMain() {}
}
