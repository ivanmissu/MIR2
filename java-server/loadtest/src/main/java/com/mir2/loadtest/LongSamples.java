package com.mir2.loadtest;

import java.util.Arrays;

/**
 * Primitive, thread-safe latency sample store with nearest-rank percentiles.
 *
 * <p>The swarm only needs a few hundred thousand samples per action kind, so it stores every
 * value exactly instead of relying on a sampling reservoir; the report then works from
 * deterministic order statistics.
 */
final class LongSamples {
  private long[] values = new long[64];
  private int size;

  /** Adds one sample in nanoseconds. */
  synchronized void add(long nanos) {
    if (size == values.length) values = Arrays.copyOf(values, size * 2);
    values[size++] = nanos;
  }

  synchronized int count() {
    return size;
  }

  synchronized long sumNanos() {
    long sum = 0;
    for (int index = 0; index < size; index++) sum += values[index];
    return sum;
  }

  synchronized long minNanos() {
    if (size == 0) return Long.MAX_VALUE;
    long min = values[0];
    for (int index = 1; index < size; index++) min = Math.min(min, values[index]);
    return min;
  }

  synchronized long maxNanos() {
    long max = Long.MIN_VALUE;
    for (int index = 0; index < size; index++) max = Math.max(max, values[index]);
    return max;
  }

  /** Nearest-rank percentile in milliseconds; {@code NaN} when no samples exist. */
  synchronized double percentileMillis(double percentile) {
    if (size == 0) return Double.NaN;
    long[] sorted = Arrays.copyOf(values, size);
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(percentile / 100.0 * size);
    int index = Math.min(Math.max(rank, 1), size) - 1;
    return sorted[index] / 1_000_000.0;
  }
}
