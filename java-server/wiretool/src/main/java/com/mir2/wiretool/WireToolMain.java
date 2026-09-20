package com.mir2.wiretool;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry of the traffic-capture trio member (P0 三件套: recorder / replayer /
 * bot-swarm). One shaded JAR, three subcommands:
 *
 * <ul>
 *   <li>{@code record} — transparent recording proxy (mir2.exe ↔ Delphi/Java server) writing
 *       one {@code .mrec} per connection;</li>
 *   <li>{@code replay} — replays the recorded client frames against a target server and diffs
 *       the answers, byte-level or structural, producing a Chinese Markdown/CSV report;</li>
 *   <li>{@code inspect} — prints a summary of a recording with best-effort frame annotation.</li>
 * </ul>
 *
 * Exit codes: 0 = OK / replay PASS; 1 = replay FAIL verdict or inspect verify mismatch;
 * 2 = usage or I/O errors.
 */
public final class WireToolMain {

  private static final String USAGE = """
      mir2-wiretool — 传奇2 线上流量录制/回放对拍工具（P0 三件套: recorder / replayer / bot-swarm）

      用法：
        java -jar mir2-wiretool.jar record  --listen-port <端口> --target-host <主机> \\
            --target-port <端口> [--bind <地址>] [--out-dir <目录>] [--label <名>] \\
            [--max-connections <N>]
        java -jar mir2-wiretool.jar replay  --file <捕获.mrec> --target-host <主机> \\
            --target-port <端口> [--speed <倍率> | --max-speed] [--drain-ms <毫秒>] \\
            [--skip-server-frames <i,j,…>] [--structural-only] [--report-dir <目录>] \\
            [--label <名>]
        java -jar mir2-wiretool.jar inspect --file <捕获.mrec> [--verify]

      record   透明代理：客户端连 --listen-port，字节原样转发到 --target-host:--target-port；
               每条连接写一个 <label>-<时间戳>-<端口>-<序号>.mrec。挂在 mir2.exe 与 Delphi
               服务端之间即可抓取字节级 golden；SIGTERM 结束。
      replay   把录制中的客户端帧（含轮转序号，原样）按录制节奏发给目标服务端，收取应答后
               与录制中的服务端帧逐帧对拍：缺失/多余/首个差异偏移分类；已知易变帧（认证码、
               tick）用 --skip-server-frames（服务端帧序号，inspect 可查）排除；加
               --structural-only 时只判定帧序列形状，用于带随机字段的会话冒烟。退出码即结论。
      inspect  打印录制摘要与逐帧注解（ident 名、GBK 正文预览、RunLogin 识别；cert 打码）。
               --verify 时统计无法按包头/RunLogin 解析的帧，有则退出码为 1。
      """;

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (IllegalArgumentException error) {
      System.err.println("参数错误：" + error.getMessage());
      exitCode = 2;
    } catch (Exception error) {
      System.err.println("wiretool 失败：" + error.getMessage());
      error.printStackTrace();
      exitCode = 2;
    }
    System.exit(exitCode);
  }

  static int run(String[] args) throws Exception {
    if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
      System.out.println(USAGE);
      return 0;
    }
    String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
    return switch (args[0]) {
      case "record" -> record(Args.parse(rest));
      case "replay" -> replay(Args.parse(rest));
      case "inspect" -> inspect(Args.parse(rest));
      default -> throw new IllegalArgumentException("未知子命令: " + args[0] + "（见 --help）");
    };
  }

  // ------------------------------------------------------------ record

  private static int record(Map<String, String> options) throws Exception {
    int listenPort = intOption(options, "listen-port", -1);
    String targetHost = options.get("target-host");
    int targetPort = intOption(options, "target-port", -1);
    if (listenPort < 1 || targetHost == null || targetPort < 1)
      throw new IllegalArgumentException("record 需要 --listen-port --target-host --target-port");
    InetAddress bind = InetAddress.getByName(options.getOrDefault("bind", "0.0.0.0"));
    Path outDir = Path.of(options.getOrDefault("out-dir", "captures"));
    String label = options.getOrDefault("label", "capture");
    int maxConnections = intOption(options, "max-connections", Integer.MAX_VALUE);

    RecorderProxy proxy = new RecorderProxy(new RecorderProxy.Config(
        bind, listenPort, targetHost, targetPort, outDir, label, maxConnections));
    proxy.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        proxy.close();
      } catch (IOException ignored) {
        // JVM is going down; the recording writer is closed per connection already.
      }
    }, "wiretool-record-shutdown"));
    System.out.printf(Locale.ROOT,
        "[wiretool] record: %s:%d → %s:%d，录制目录 %s（Ctrl+C 结束）%n",
        bind.getHostAddress(), listenPort, targetHost, targetPort, outDir.toAbsolutePath());
    proxy.awaitTermination();
    for (RecorderProxy.Session session : proxy.sessions()) {
      System.out.printf(Locale.ROOT, "[wiretool] session %s %s: %s%n",
          session.aborted() ? "aborted" : "ok", session.file().getFileName(), session.note());
    }
    System.out.printf(Locale.ROOT, "[wiretool] 完成：%d 条连接，%d 个录制文件%n",
        proxy.completedConnections(), proxy.sessions().size());
    return 0;
  }

  // ------------------------------------------------------------ replay

  private static int replay(Map<String, String> options) throws Exception {
    String file = options.get("file");
    String targetHost = options.get("target-host");
    int targetPort = intOption(options, "target-port", -1);
    if (file == null || targetHost == null || targetPort < 1)
      throw new IllegalArgumentException("replay 需要 --file --target-host --target-port");
    double speed = doubleOption(options, "speed", 1.0);
    boolean maxSpeed = options.containsKey("max-speed");
    Duration drain = Duration.ofMillis(longOption(options, "drain-ms", 1500));
    Set<Integer> skip = ReplayReport.parseSkipList(options.get("skip-server-frames"));
    boolean structuralOnly = options.containsKey("structural-only");
    Path reportDir = Path.of(options.getOrDefault("report-dir", "reports"));
    String label = options.getOrDefault("label", "diff");

    Recording recording = RecordingCodec.read(Path.of(file));
    List<Recording.Event> clientEvents = recording.clientEvents();
    if (clientEvents.isEmpty())
      throw new IllegalArgumentException("录制中没有客户端事件可回放");

    Replayer replayer = new Replayer();
    Replayer.Options replayOptions =
        new Replayer.Options(targetHost, targetPort, speed, maxSpeed, drain,
            Duration.ofSeconds(5));
    System.out.printf(Locale.ROOT,
        "[wiretool] replay: %d 个客户端事件 → %s:%d（%s）%n", clientEvents.size(),
        targetHost, targetPort,
        maxSpeed ? "最快速度" : String.format(Locale.ROOT, "%.2fx", speed));
    Replayer.Outcome outcome = replayer.replay(recording, replayOptions);

    List<byte[]> expected = new ArrayList<>();
    for (Recording.Event event : recording.serverFrames()) expected.add(event.payload());
    List<byte[]> actual = new ArrayList<>();
    for (Replayer.Captured captured : outcome.serverFrames()) actual.add(captured.payload());
    ReplayDiff.Result diff = ReplayDiff.compare(expected, actual, skip, outcome.noiseCount());

    List<String> notes = new ArrayList<>(outcome.notes());
    if (!skip.isEmpty()) notes.add("跳过服务端帧序号：" + skip);
    ReplayReport.Context context = new ReplayReport.Context(file,
        targetHost + ":" + targetPort, structuralOnly, maxSpeed, speed,
        outcome.eventsSent(), outcome.eventsTotal(), outcome, diff, notes);
    Path markdown = ReplayReport.writeAll(reportDir, label, context);

    printSummary(context, markdown);
    return diff.passed(structuralOnly) ? 0 : 1;
  }

  private static void printSummary(ReplayReport.Context context, Path markdown) {
    ReplayDiff.Result diff = context.diff();
    boolean pass = diff.passed(context.structuralOnly());
    System.out.printf(Locale.ROOT,
        "[wiretool] %s：一致=%d 内容差异=%d 缺失=%d 多余=%d 跳过=%d（%s模式）%n",
        pass ? "PASS" : "FAIL",
        diff.count(ReplayDiff.Status.IDENTICAL), diff.count(ReplayDiff.Status.CONTENT),
        diff.count(ReplayDiff.Status.MISSING), diff.count(ReplayDiff.Status.EXTRA),
        diff.skipped(), context.structuralOnly() ? "结构级" : "字节级");
    System.out.println("[wiretool] 报告：" + markdown.toAbsolutePath());
  }

  // ------------------------------------------------------------ inspect

  private static int inspect(Map<String, String> options) throws IOException {
    String file = options.get("file");
    if (file == null) throw new IllegalArgumentException("inspect 需要 --file <捕获.mrec>");
    Recording recording = RecordingCodec.read(Path.of(file));
    boolean verify = options.containsKey("verify");
    Inspector.Summary summary = Inspector.inspect(recording, verify, System.out);
    return verify && summary.unparseableFrames() > 0 ? 1 : 0;
  }

  // ------------------------------------------------------------ options

  private static final class Args {
    private static final Set<String> FLAGS = new LinkedHashSet<>(
        List.of("max-speed", "structural-only", "verify", "help"));

    static Map<String, String> parse(String[] args) {
      Map<String, String> options = new java.util.LinkedHashMap<>();
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

  private static int intOption(Map<String, String> options, String key, int fallback) {
    String value = options.get(key);
    return value == null ? fallback : Integer.parseInt(value);
  }

  private static long longOption(Map<String, String> options, String key, long fallback) {
    String value = options.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }

  private static double doubleOption(Map<String, String> options, String key, double fallback) {
    String value = options.get(key);
    return value == null ? fallback : Double.parseDouble(value);
  }

  private WireToolMain() {}
}
