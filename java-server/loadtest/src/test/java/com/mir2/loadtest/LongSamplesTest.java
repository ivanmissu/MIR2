package com.mir2.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LongSamplesTest {
  @Test
  void emptySamplesReportNaNAndZeroCounts() {
    LongSamples samples = new LongSamples();
    assertEquals(0, samples.count());
    assertTrue(Double.isNaN(samples.percentileMillis(50)));
    assertEquals(Long.MAX_VALUE, samples.minNanos());
    assertEquals(Long.MIN_VALUE, samples.maxNanos());
  }

  @Test
  void nearestRankPercentilesMatchTheOrderStatistics() {
    LongSamples samples = new LongSamples();
    // 100 samples of 1..100 milliseconds.
    LongStream.rangeClosed(1, 100).forEach(value -> samples.add(value * 1_000_000L));
    assertEquals(100, samples.count());
    assertEquals(50.0, samples.percentileMillis(50));
    assertEquals(90.0, samples.percentileMillis(90));
    assertEquals(99.0, samples.percentileMillis(99));
    assertEquals(1.0, samples.percentileMillis(0));
    assertEquals(100.0, samples.percentileMillis(100));
    assertEquals(100.0, samples.percentileMillis(99.9));
  }

  @Test
  void singleSampleIsEveryPercentile() {
    LongSamples samples = new LongSamples();
    samples.add(2_500_000L);
    assertEquals(2.5, samples.percentileMillis(10));
    assertEquals(2.5, samples.percentileMillis(50));
    assertEquals(2.5, samples.percentileMillis(99));
  }
}
