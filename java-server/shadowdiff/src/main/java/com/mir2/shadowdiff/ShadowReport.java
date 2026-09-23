package com.mir2.shadowdiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Renders one {@link ShadowDiff.Result} as a Chinese Markdown report plus a flat CSV. */
public final class ShadowReport {

  private ShadowReport() {}

  /** Writes {@code shadow-report.md} and {@code shadow-report.csv}; returns the Markdown. */
  public static Path writeAll(Path directory, ShadowDiff.Result result, List<Op> script)
      throws IOException {
    Files.createDirectories(directory);
    Path markdown = directory.resolve("shadow-report.md");
    Files.writeString(markdown, markdown(result, script), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("shadow-report.csv"), csv(result),
        StandardCharsets.UTF_8);
    return markdown;
  }

  static String markdown(ShadowDiff.Result result, List<Op> script) {
    StringBuilder text = new StringBuilder();
    text.append("# MIR2 影子对拍报告（shadowdiff）\n\n");
    text.append("- 生成时间：").append(Instant.now()).append('\n');
    text.append("- 左侧目标：`").append(result.leftLabel()).append("`\n");
    text.append("- 右侧目标：`").append(result.rightLabel()).append("`\n");
    text.append("- 操作流：").append(result.entries().size()).append(" 步（含进图）\n");
    text.append("- 消息判定：").append(result.strictMessages()
        ? "严格（消息集合差异也算 FAIL）" : "宽松（消息集合差异仅提示）").append('\n');
    text.append('\n');

    long stateDiffs = result.count(ShadowDiff.Severity.STATE);
    long ackDiffs = result.count(ShadowDiff.Severity.ACKS);
    long messageDiffs = result.count(ShadowDiff.Severity.MESSAGES);
    boolean pass = result.passed();
    text.append("## 结论：").append(pass ? "PASS ✅" : "FAIL ❌").append("\n\n");
    text.append(String.format(Locale.ROOT,
        "状态差异 %d 步，应答差异 %d 步，消息集合差异 %d 步，共 %d 步。%n%n",
        stateDiffs, ackDiffs, messageDiffs, result.entries().size()));

    text.append("| # | 操作 | 判定 | 详情 |\n|---|---|---|---|\n");
    for (ShadowDiff.Entry entry : result.entries()) {
      text.append("| ").append(entry.index())
          .append(" | `").append(entry.op())
          .append("` | ").append(verdict(entry.severity()))
          .append(" | ").append(entry.details().isEmpty()
              ? "" : String.join("<br>", entry.details()))
          .append(" |\n");
    }
    text.append('\n');

    List<ShadowDiff.Entry> differing = result.entries().stream()
        .filter(entry -> entry.severity() != ShadowDiff.Severity.MATCH).toList();
    if (!differing.isEmpty()) {
      text.append("## 差异明细\n\n");
      for (ShadowDiff.Entry entry : differing) {
        text.append("### #").append(entry.index()).append(" `").append(entry.op())
            .append("` — ").append(verdict(entry.severity())).append("\n\n");
        text.append("- ").append(result.leftLabel()).append("：")
            .append(entry.left().state().describe()).append('\n');
        text.append("- ").append(result.rightLabel()).append("：")
            .append(entry.right().state().describe()).append('\n');
        for (String detail : entry.details()) {
          text.append("- 差异：").append(detail).append('\n');
        }
        text.append('\n');
      }
    }

    // The observation trace exists so a PASS is auditable. Without it a report that says
    // "18 steps, 0 differences" cannot be distinguished from a run in which nothing
    // happened at all — which matters most for the AI comparison, whose whole claim is that
    // monsters moved identically rather than that they stood still identically.
    text.append("## 观测轨迹（左侧逐步状态）\n\n");
    text.append("| # | 操作 | 状态 |\n|---|---|---|\n");
    for (ShadowDiff.Entry entry : result.entries()) {
      text.append("| ").append(entry.index())
          .append(" | `").append(entry.op())
          .append("` | ").append(entry.left().state().describe().replace("|", "\\|"))
          .append(" |\n");
    }
    text.append('\n');

    text.append("## 操作脚本\n\n```\n");
    for (Op op : script) text.append(op.describe()).append('\n');
    text.append("```\n");
    return text.toString();
  }

  static String csv(ShadowDiff.Result result) {
    StringBuilder text = new StringBuilder("index,op,severity,details\n");
    for (ShadowDiff.Entry entry : result.entries()) {
      text.append(entry.index()).append(',')
          .append(quote(entry.op())).append(',')
          .append(entry.severity().name()).append(',')
          .append(quote(String.join(" | ", entry.details()))).append('\n');
    }
    return text.toString();
  }

  private static String verdict(ShadowDiff.Severity severity) {
    return switch (severity) {
      case MATCH -> "一致";
      case MESSAGES -> "消息差异";
      case ACKS -> "应答差异";
      case STATE -> "**状态差异**";
    };
  }

  private static String quote(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
