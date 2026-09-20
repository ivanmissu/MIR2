package com.mir2.wiretool;

import java.io.PrintStream;
import java.util.Locale;

/** Prints a human-readable summary of a {@code .mrec} recording for operators and docs. */
public final class Inspector {
  private static final int MAX_LISTED_EVENTS = 500;

  public record Summary(int clientFrames, int serverFrames, int clientNoise, int serverNoise,
      int markers, long durationMillis, int unparseableFrames) {
    public int frames() {
      return clientFrames + serverFrames;
    }
  }

  private Inspector() {}

  /**
   * Renders the recording to {@code out}. When {@code verify} is set, every frame is passed
   * through the packet/RunLogin decoders and failures are counted into the summary.
   */
  public static Summary inspect(Recording recording, boolean verify, PrintStream out) {
    int unparseable = 0;
    out.println("元数据：" + RecordingCodec.encodeMetadata(recording.metadata()));
    out.printf(Locale.ROOT, "事件总数：%d，会话时长：%d ms%n",
        recording.events().size(), recording.durationMillis());
    out.printf(Locale.ROOT,
        "客户端帧 C→S：%d ｜ 服务端帧 S→C：%d ｜ 噪声段：%d/%d ｜ 标记：%d%n",
        recording.count(Recording.Kind.CLIENT_FRAME),
        recording.count(Recording.Kind.SERVER_FRAME),
        recording.count(Recording.Kind.CLIENT_NOISE),
        recording.count(Recording.Kind.SERVER_NOISE),
        recording.count(Recording.Kind.MARKER));
    out.println();
    out.println("| 序号 | 相对毫秒 | 字节 | 注解 |");
    out.println("|---|---|---|---|");
    int listed = 0;
    for (int index = 0; index < recording.events().size(); index++) {
      Recording.Event event = recording.events().get(index);
      if (verify && event.kind().frame()
          && !FrameDescriber.parses(event.kind(), event.payload())) {
        unparseable++;
      }
      if (event.kind() == Recording.Kind.MARKER) continue;
      if (listed >= MAX_LISTED_EVENTS) {
        out.printf(Locale.ROOT, "| … | | | 仅列前 %d 条，余 %d 条省略 |%n",
            MAX_LISTED_EVENTS, recording.events().size() - index - 1);
        break;
      }
      out.printf(Locale.ROOT, "| %d | %d | %d | %s |%n", index, event.millis(),
          event.length(), FrameDescriber.describe(event.kind(), event.payload()));
      listed++;
    }
    if (verify) {
      out.println();
      out.println(unparseable == 0
          ? "verify：全部帧均可按包头/RunLogin 解析 ✅"
          : "verify：" + unparseable + " 帧无法解析 ⚠");
    }
    return new Summary(recording.count(Recording.Kind.CLIENT_FRAME),
        recording.count(Recording.Kind.SERVER_FRAME),
        recording.count(Recording.Kind.CLIENT_NOISE),
        recording.count(Recording.Kind.SERVER_NOISE),
        recording.count(Recording.Kind.MARKER), recording.durationMillis(), unparseable);
  }
}
