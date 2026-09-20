package com.mir2.wiretool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pairs the recorded server→client frames with the frames actually observed during a replay
 * and classifies every pair. Indexes are ordinals among server frames (0-based, noise and
 * client frames not counted), which is what {@code --skip-server-frames} refers to.
 *
 * <ul>
 *   <li>{@link Status#IDENTICAL} — byte-for-byte equal;</li>
 *   <li>{@link Status#CONTENT} — both present, bytes differ ({@code firstDiffOffset} locates
 *       the first mismatch, or the common length when one frame is a prefix of the other);</li>
 *   <li>{@link Status#MISSING} — recorded but never observed;</li>
 *   <li>{@link Status#EXTRA} — observed but not present in the recording;</li>
 *   <li>{@link Status#SKIPPED} — explicitly excluded via the skip list (known-volatile frames
 *       such as certifications or tick counters until real Delphi goldens replace them).</li>
 * </ul>
 *
 * Verdicts: byte-level mode fails on any CONTENT/MISSING/EXTRA; structural mode ignores
 * CONTENT (same conversation shape, volatile fields allowed to drift) but still fails on
 * MISSING/EXTRA.
 */
public final class ReplayDiff {
  public enum Status { IDENTICAL, CONTENT, MISSING, EXTRA, SKIPPED }

  public record Row(int index, Status status, int expectedLength, int actualLength,
      int firstDiffOffset, String annotation) {
    public Row {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(annotation, "annotation");
    }
  }

  public record Result(List<Row> rows, int skipped, int serverNoise) {
    public Result {
      rows = List.copyOf(rows);
    }

    public long count(Status status) {
      return rows.stream().filter(row -> row.status() == status).count();
    }

    public boolean passed(boolean structuralOnly) {
      if (count(Status.MISSING) > 0 || count(Status.EXTRA) > 0) return false;
      return structuralOnly || count(Status.CONTENT) == 0;
    }
  }

  private ReplayDiff() {}

  public static Result compare(List<byte[]> expected, List<byte[]> actual, Set<Integer> skip,
      long serverNoise) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(skip, "skip");
    List<Row> rows = new ArrayList<>();
    int pairs = Math.max(expected.size(), actual.size());
    int skipped = 0;
    for (int index = 0; index < pairs; index++) {
      byte[] expectedFrame = index < expected.size() ? expected.get(index) : null;
      byte[] actualFrame = index < actual.size() ? actual.get(index) : null;
      if (expectedFrame != null && skip.contains(index)) {
        rows.add(new Row(index, Status.SKIPPED, expectedFrame.length,
            actualFrame == null ? -1 : actualFrame.length, -1,
            FrameDescriber.describe(Recording.Kind.SERVER_FRAME, expectedFrame)));
        skipped++;
        continue;
      }
      if (expectedFrame != null && actualFrame == null) {
        rows.add(new Row(index, Status.MISSING, expectedFrame.length, -1, -1,
            FrameDescriber.describe(Recording.Kind.SERVER_FRAME, expectedFrame)));
      } else if (expectedFrame == null && actualFrame != null) {
        rows.add(new Row(index, Status.EXTRA, -1, actualFrame.length, -1,
            FrameDescriber.describe(Recording.Kind.SERVER_FRAME, actualFrame)));
      } else if (Arrays.equals(expectedFrame, actualFrame)) {
        rows.add(new Row(index, Status.IDENTICAL, expectedFrame.length, actualFrame.length, -1,
            FrameDescriber.describe(Recording.Kind.SERVER_FRAME, expectedFrame)));
      } else {
        rows.add(new Row(index, Status.CONTENT, expectedFrame.length, actualFrame.length,
            firstDiff(expectedFrame, actualFrame),
            FrameDescriber.describe(Recording.Kind.SERVER_FRAME, expectedFrame)));
      }
    }
    return new Result(rows, skipped, (int) serverNoise);
  }

  /** First differing offset; if one frame prefixes the other, the common prefix length. */
  static int firstDiff(byte[] left, byte[] right) {
    int common = Math.min(left.length, right.length);
    for (int index = 0; index < common; index++) {
      if (left[index] != right[index]) return index;
    }
    return common;
  }
}
