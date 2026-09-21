package com.mir2.loadtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Renders one swarm {@link BotSwarm.Result} as a Markdown report plus a flat CSV. */
public final class BotReport {
  private static final int RECEIVED_ROWS = 20;

  private BotReport() {}

  /** Writes {@code report.md} and {@code report.csv} into {@code directory}. */
  public static Path writeAll(Path directory, BotSwarm.Result result) throws IOException {
    Files.createDirectories(directory);
    Path markdown = directory.resolve("report.md");
    Files.writeString(markdown, markdown(result), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("report.csv"), csv(result), StandardCharsets.UTF_8);
    return markdown;
  }

  /** PASS when no error counter moved and every bot entered the world at least once. */
  public static boolean verdictPassed(BotSwarm.Result result) {
    BotMetrics.Snapshot metrics = result.metrics();
    return metrics.totalErrors() == 0
        && metrics.get(BotMetrics.Key.BOTS_ENTERED) == result.spec().bots()
        && metrics.get(BotMetrics.Key.GAME_ENTRIES) >= result.spec().bots();
  }

  private static String markdown(BotSwarm.Result result) {
    BotMetrics.Snapshot metrics = result.metrics();
    StringBuilder text = new StringBuilder();
    text.append("# MIR2 bot-swarm 稳定性压测报告\n\n");
    text.append("- 生成时间：").append(result.endedAt()).append('\n');
    text.append("- 目标服务器：`").append(result.spec().host()).append("` login=`")
        .append(result.spec().loginPort()).append("`, select/game 按服务端广播地址，server=`")
        .append(result.spec().serverName()).append("`\n");
    text.append("- 规模：**").append(result.spec().bots()).append(" bots**，duration=")
        .append(result.spec().duration()).append("，ramp=").append(result.spec().rampUp())
        .append("，think=").append(result.spec().thinkMin().toMillis()).append("–")
        .append(result.spec().thinkMax().toMillis()).append("ms，relogEvery=")
        .append(result.spec().relogEvery()).append("，seed=").append(result.spec().seed())
        .append('\n');
    text.append("- 实际墙钟：").append(result.wallClock());
    if (result.stoppedEarly()) text.append("（提前手动停止）");
    text.append('\n');
    for (String note : result.notes()) text.append("- ").append(note).append('\n');
    text.append('\n');

    boolean pass = verdictPassed(result);
    text.append("## 结论：").append(pass ? "PASS ✅" : "FAIL ❌").append('\n');
    text.append(pass
        ? String.format(Locale.ROOT, "错误计数为 0，%d/%d 个机器人至少进图一次，累计进图 %d 次。%n",
            metrics.get(BotMetrics.Key.BOTS_ENTERED), result.spec().bots(),
            metrics.get(BotMetrics.Key.GAME_ENTRIES))
        : String.format(Locale.ROOT, "存在 %d 个错误计数（详见「错误」一节），或并非所有机器人都进图。%n",
            metrics.totalErrors()));
    text.append('\n');

    text.append("## 会话\n\n| 指标 | 值 |\n|---|---|\n");
    for (BotMetrics.Key key : new BotMetrics.Key[] {
        BotMetrics.Key.BOTS_LAUNCHED, BotMetrics.Key.BOTS_ENTERED, BotMetrics.Key.GAME_ENTRIES,
        BotMetrics.Key.ENTER_RETRIES, BotMetrics.Key.RELOGS_COMPLETED,
        BotMetrics.Key.SESSIONS_ENDED, BotMetrics.Key.POSITION_RESYNCS,
        BotMetrics.Key.PLAYERS_DIED}) {
      text.append("| ").append(key.label()).append(" | ").append(metrics.get(key))
          .append(" |\n");
    }
    text.append("| bag queries sent | ").append(metrics.get(BotMetrics.Key.BAG_QUERIES_SENT))
        .append(" |\n");
    text.append("| SM_BAGITEMS received | ")
        .append(metrics.get(BotMetrics.Key.BAG_LISTS_RECEIVED)).append(" |\n");
    text.append("| SM_ADDITEM (拾取成功) | ")
        .append(metrics.get(BotMetrics.Key.ITEMS_PICKED_UP)).append(" |\n");
    text.append("| SM_WINEXP (经验推送) | ")
        .append(metrics.get(BotMetrics.Key.EXPERIENCE_UPDATES)).append(" |\n");
    text.append("| SM_LEVELUP (升级) | ")
        .append(metrics.get(BotMetrics.Key.LEVEL_UPS)).append(" |\n");
    text.append("| SM_ALIVE (复活) | ")
        .append(metrics.get(BotMetrics.Key.REVIVALS_SEEN)).append(" |\n");
    text.append("| SM_DEATH (他者死亡) | ")
        .append(metrics.get(BotMetrics.Key.OBSERVED_DEATHS)).append(" |\n");
    text.append('\n');

    text.append("## 动作与应答延迟\n\n");
    text.append("| 动作 | 发送 | +GOOD | +FAIL | 超时 | p50 | p90 | p99 | max |\n");
    text.append("|---|---|---|---|---|---|---|---|---|\n");
    for (Map.Entry<BotMetrics.ActionKind, BotMetrics.ActionStats> entry
        : metrics.actions().entrySet()) {
      BotMetrics.ActionStats stats = entry.getValue();
      text.append(String.format(Locale.ROOT,
          "| %s | %d | %d | %d | %d | %s | %s | %s | %s |%n",
          entry.getKey().label(), stats.sent(), stats.good(), stats.fail(), stats.timeout(),
          millis(stats.p50Millis()), millis(stats.p90Millis()), millis(stats.p99Millis()),
          millis(stats.maxMillis())));
    }
    text.append('\n');

    text.append("## 服务端消息（前 ").append(RECEIVED_ROWS).append("）\n\n");
    text.append("| 消息 | 数量 |\n|---|---|\n");
    int rows = 0;
    for (Map.Entry<String, Long> entry : metrics.receivedByIdent()) {
      if (rows++ >= RECEIVED_ROWS) break;
      text.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue())
          .append(" |\n");
    }
    if (rows == 0) text.append("| (无) | 0 |\n");
    text.append('\n');

    text.append("## 错误\n\n");
    long errors = metrics.totalErrors();
    if (errors == 0 && metrics.get(BotMetrics.Key.UNMATCHED_ACKS) == 0) {
      text.append("无。\n");
    } else {
      text.append("| 错误 | 数量 |\n|---|---|\n");
      for (BotMetrics.Key key : BotMetrics.Key.values()) {
        if (key.isError()) {
          text.append("| ").append(key.label()).append(" | ").append(metrics.get(key))
              .append(" |\n");
        }
      }
      text.append("| ").append(BotMetrics.Key.UNMATCHED_ACKS.label()).append(" | ")
          .append(metrics.get(BotMetrics.Key.UNMATCHED_ACKS)).append(" |\n");
    }
    return text.toString();
  }

  private static String csv(BotSwarm.Result result) {
    BotMetrics.Snapshot metrics = result.metrics();
    StringBuilder text = new StringBuilder("section,kind,metric,value\n");
    csvRow(text, "summary", "bots", "count", result.spec().bots());
    csvRow(text, "summary", "duration", "seconds", result.spec().duration().toSeconds());
    csvRow(text, "summary", "wall_clock", "seconds", result.wallClock().toSeconds());
    csvRow(text, "summary", "stopped_early", "flag", result.stoppedEarly() ? 1 : 0);
    csvRow(text, "summary", "verdict", "pass", verdictPassed(result) ? 1 : 0);
    for (BotMetrics.Key key : BotMetrics.Key.values()) {
      csvRow(text, "session", key.name().toLowerCase(), "count", metrics.get(key));
    }
    for (Map.Entry<BotMetrics.ActionKind, BotMetrics.ActionStats> entry
        : metrics.actions().entrySet()) {
      String kind = entry.getKey().name().toLowerCase();
      BotMetrics.ActionStats stats = entry.getValue();
      csvRow(text, "action", kind, "sent", stats.sent());
      csvRow(text, "action", kind, "good", stats.good());
      csvRow(text, "action", kind, "fail", stats.fail());
      csvRow(text, "action", kind, "timeout", stats.timeout());
      csvRow(text, "action", kind, "p50_ms", round(stats.p50Millis()));
      csvRow(text, "action", kind, "p90_ms", round(stats.p90Millis()));
      csvRow(text, "action", kind, "p99_ms", round(stats.p99Millis()));
      csvRow(text, "action", kind, "max_ms", round(stats.maxMillis()));
    }
    for (Map.Entry<String, Long> entry : metrics.receivedByIdent()) {
      csvRow(text, "received", entry.getKey(), "count", entry.getValue());
    }
    return text.toString();
  }

  private static void csvRow(StringBuilder text, String section, String kind,
      String metric, Object value) {
    text.append(section).append(',').append(kind).append(',').append(metric).append(',')
        .append(value).append('\n');
  }

  private static String millis(double value) {
    return Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.1f ms", value);
  }

  private static String round(double value) {
    return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
  }
}
