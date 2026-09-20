package com.mir2.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotReportTest {
  @TempDir
  Path tempDir;

  @Test
  void cleanRunPassesAndWritesMarkdownAndCsv() throws Exception {
    BotMetrics metrics = new BotMetrics();
    metrics.count(BotMetrics.Key.BOTS_LAUNCHED, 2);
    metrics.count(BotMetrics.Key.BOTS_ENTERED, 2);
    metrics.count(BotMetrics.Key.GAME_ENTRIES, 5);
    metrics.count(BotMetrics.Key.RELOGS_COMPLETED, 3);
    metrics.sent(BotMetrics.ActionKind.WALK);
    metrics.sent(BotMetrics.ActionKind.WALK);
    metrics.acknowledged(BotMetrics.ActionKind.WALK, true, 3_000_000L);
    metrics.acknowledged(BotMetrics.ActionKind.WALK, false, 4_000_000L);
    metrics.sent(BotMetrics.ActionKind.PICKUP);
    metrics.received(com.mir2.protocol.ProtocolConstants.SM_WALK);
    BotSwarm.Spec spec = new BotSwarm.Spec(2, Duration.ofSeconds(1), Duration.ZERO,
        "127.0.0.1", 7000, 0, 0, "MIR2", "bot", "pw", Duration.ofMillis(1),
        Duration.ofMillis(2), Duration.ofSeconds(1), 1L);
    BotSwarm.Result result = new BotSwarm.Result(spec, metrics.snapshot(), Instant.now(),
        Instant.now(), false, List.of("note-for-report"));

    assertTrue(BotReport.verdictPassed(result));
    Path markdown = BotReport.writeAll(tempDir.resolve("reports"), result);

    String text = Files.readString(markdown);
    assertTrue(text.contains("## 结论：PASS"));
    assertTrue(text.contains("note-for-report"));
    assertTrue(text.contains("| walk | 2 | 1 | 1 | 0 |"));
    assertTrue(text.contains("SM_WALK"));

    List<String> csv = Files.readAllLines(tempDir.resolve("reports").resolve("report.csv"));
    assertEquals("section,kind,metric,value", csv.getFirst());
    assertTrue(csv.contains("summary,verdict,pass,1"));
    assertTrue(csv.contains("session,game_entries,count,5"));
    assertTrue(csv.contains("action,walk,sent,2"));
    assertTrue(csv.contains("action,walk,good,1"));
    assertTrue(csv.contains("received,SM_WALK,count,1"));
  }

  @Test
  void errorsOrMissingEntriesFailTheVerdict() {
    BotMetrics clean = new BotMetrics();
    clean.count(BotMetrics.Key.BOTS_ENTERED, 2);
    clean.count(BotMetrics.Key.GAME_ENTRIES, 2);
    BotMetrics.Snapshot cleanSnapshot = clean.snapshot();

    BotMetrics withError = new BotMetrics();
    withError.count(BotMetrics.Key.BOTS_ENTERED, 2);
    withError.count(BotMetrics.Key.GAME_ENTRIES, 2);
    withError.count(BotMetrics.Key.ACK_TIMEOUTS);
    BotMetrics.Snapshot errorSnapshot = withError.snapshot();

    BotMetrics missingEntry = new BotMetrics();
    missingEntry.count(BotMetrics.Key.BOTS_ENTERED, 1);
    missingEntry.count(BotMetrics.Key.GAME_ENTRIES, 1);
    BotMetrics.Snapshot missingSnapshot = missingEntry.snapshot();

    BotSwarm.Spec spec = new BotSwarm.Spec(2, Duration.ofSeconds(1), Duration.ZERO,
        "127.0.0.1", 7000, 0, 0, "MIR2", "bot", "pw", Duration.ofMillis(1),
        Duration.ofMillis(2), Duration.ofSeconds(1), 1L);
    Instant now = Instant.now();
    assertTrue(BotReport.verdictPassed(
        new BotSwarm.Result(spec, cleanSnapshot, now, now, false, List.of())));
    assertFalse(BotReport.verdictPassed(
        new BotSwarm.Result(spec, errorSnapshot, now, now, false, List.of())));
    assertFalse(BotReport.verdictPassed(
        new BotSwarm.Result(spec, missingSnapshot, now, now, false, List.of())));
  }
}
