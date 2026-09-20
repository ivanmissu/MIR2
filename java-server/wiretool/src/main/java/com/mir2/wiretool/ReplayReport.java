package com.mir2.wiretool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders one replay outcome + diff as a Chinese Markdown report plus a flat CSV. */
public final class ReplayReport {
  private static final int MAX_TABLE_ROWS = 500;

  private ReplayReport() {}

  /** All inputs needed to describe one replay run in a report. */
  public record Context(String recordingFile, String target, boolean structuralOnly,
      boolean maxSpeed, double speed, int clientEventsSent, int clientEventsTotal,
      Replayer.Outcome outcome, ReplayDiff.Result diff, java.util.List<String> notes) {}

  /** Writes {@code replay-<label>-<timestamp>.md/.csv}; returns the Markdown path. */
  public static Path writeAll(Path directory, String label, Context context) throws IOException {
    Files.createDirectories(directory);
    String stamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path markdown = directory.resolve("replay-" + label + "-" + stamp + ".md");
    Path csv = directory.resolve("replay-" + label + "-" + stamp + ".csv");
    Files.writeString(markdown, markdown(context), StandardCharsets.UTF_8);
    Files.writeString(csv, csv(context), StandardCharsets.UTF_8);
    return markdown;
  }

  static String markdown(Context context) {
    ReplayDiff.Result diff = context.diff();
    boolean pass = diff.passed(context.structuralOnly());
    StringBuilder text = new StringBuilder();
    text.append("# MIR2 wiretool 回放对拍报告\n\n");
    text.append("- 生成时间：").append(Instant.now()).append('\n');
    text.append("- 录制文件：`").append(context.recordingFile()).append("`\n");
    text.append("- 回放目标：`").append(context.target()).append("`\n");
    text.append("- 对拍模式：").append(context.structuralOnly()
        ? "结构级（帧序列形状一致即可，内容差异仅提示）" : "字节级（逐字节一致才算一致）").append('\n');
    text.append("- 回放节奏：").append(context.maxSpeed()
        ? "最快速度" : String.format(Locale.ROOT, "%.2fx 原速", context.speed())).append('\n');
    text.append("- 客户端事件：发送 ").append(context.clientEventsSent()).append('/')
        .append(context.clientEventsTotal());
    if (!context.outcome().sendCompleted()) text.append("（服务端提前关闭，发送被截断）");
    text.append('\n');
    for (String note : context.notes()) text.append("- ").append(note).append('\n');
    text.append('\n');

    text.append("## 结论：").append(pass ? "PASS ✅" : "FAIL ❌").append('\n');
    if (pass) {
      text.append(context.structuralOnly()
          ? "服务端帧序列形状与录制一致（内容差异不纳入判定，见下表）。\n\n"
          : "服务端应答与录制逐字节一致。\n\n");
    } else {
      text.append("存在缺失/多余").append(context.structuralOnly() ? "" : "/内容")
          .append("差异，详见差异明细。\n\n");
    }

    text.append("## 汇总\n\n| 指标 | 值 |\n|---|---|\n");
    row(text, "录制服务端帧", String.valueOf(diff.rows().stream()
        .filter(r -> r.status() != ReplayDiff.Status.EXTRA).count()));
    row(text, "实收服务端帧", String.valueOf(context.outcome().serverFrames().size()));
    row(text, "逐字节一致", String.valueOf(diff.count(ReplayDiff.Status.IDENTICAL)));
    row(text, "内容差异帧", String.valueOf(diff.count(ReplayDiff.Status.CONTENT)));
    row(text, "缺失帧", String.valueOf(diff.count(ReplayDiff.Status.MISSING)));
    row(text, "多余帧", String.valueOf(diff.count(ReplayDiff.Status.EXTRA)));
    row(text, "跳过（已知易变）", String.valueOf(diff.skipped()));
    row(text, "服务端噪声段", String.valueOf(diff.serverNoise()));
    text.append('\n');

    text.append("## 差异明细\n\n");
    text.append("| 帧序号 | 状态 | 期望长度 | 实际长度 | 首个差异偏移 | 帧注解 |\n");
    text.append("|---|---|---|---|---|---|\n");
    int rendered = 0;
    for (ReplayDiff.Row row : diff.rows()) {
      if (row.status() == ReplayDiff.Status.IDENTICAL) continue;
      if (rendered >= MAX_TABLE_ROWS) {
        text.append("| … | 仅列前 ").append(MAX_TABLE_ROWS).append(" 条非一致行 | | | | |\n");
        break;
      }
      text.append("| ").append(row.index()).append(" | ").append(statusLabel(row.status()))
          .append(" | ").append(dash(row.expectedLength()))
          .append(" | ").append(dash(row.actualLength()))
          .append(" | ").append(dash(row.firstDiffOffset()))
          .append(" | ").append(escapeCell(row.annotation())).append(" |\n");
      rendered++;
    }
    if (rendered == 0) text.append("| — | 全部帧逐字节一致 | | | | |\n");
    text.append('\n');
    return text.toString();
  }

  static String csv(Context context) {
    StringBuilder text = new StringBuilder("index,status,expected_len,actual_len,first_diff_offset,annotation\n");
    for (ReplayDiff.Row row : context.diff().rows()) {
      text.append(row.index()).append(',').append(row.status()).append(',')
          .append(row.expectedLength()).append(',').append(row.actualLength()).append(',')
          .append(row.firstDiffOffset()).append(',')
          .append('"').append(row.annotation().replace("\"", "\"\"")).append('"')
          .append('\n');
    }
    return text.toString();
  }

  private static void row(StringBuilder text, String label, String value) {
    text.append("| ").append(label).append(" | ").append(value).append(" |\n");
  }

  private static String dash(int value) {
    return value < 0 ? "—" : String.valueOf(value);
  }

  private static String statusLabel(ReplayDiff.Status status) {
    return switch (status) {
      case IDENTICAL -> "一致";
      case CONTENT -> "内容差异";
      case MISSING -> "缺失";
      case EXTRA -> "多余";
      case SKIPPED -> "跳过";
    };
  }

  private static String escapeCell(String annotation) {
    return annotation.replace("|", "\\|");
  }

  /** Parses {@code 0,3,7} style skip lists into a set, rejecting out-of-range entries. */
  public static Set<Integer> parseSkipList(String csv) {
    if (csv == null || csv.isBlank()) return Set.of();
    return java.util.Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(part -> !part.isEmpty())
        .map(part -> {
          try {
            int index = Integer.parseInt(part);
            if (index < 0) throw new IllegalArgumentException("skip index must be >= 0: " + part);
            return index;
          } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid skip index: " + part, error);
          }
        })
        .collect(Collectors.toUnmodifiableSet());
  }
}
