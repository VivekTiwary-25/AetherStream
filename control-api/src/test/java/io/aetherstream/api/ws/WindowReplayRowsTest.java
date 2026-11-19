package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.api.ws.IncrementalResultRepository.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowReplayRowsTest {
    @Test
    void checkpointReplayDoesNotReemitWindowsEvenAfterTheTimestampOverlapExpires() {
        NewRowsTracker tracker = new NewRowsTracker(true);
        Instant start = Instant.parse("2026-11-19T01:00:00Z");
        Row first = window(start, "speedSink", "truck-1", 0);
        Row next = window(start.plusSeconds(120), "speedSink", "truck-1", 60_000);
        assertThat(tracker.fresh(List.of(first, next))).containsExactly(first, next);

        Instant replayTime = start.plusSeconds(300);
        Row replayed = window(replayTime, "speedSink", "truck-1", 0);
        Row replayedNext = window(replayTime, "speedSink", "truck-1", 60_000);
        assertThat(tracker.fresh(List.of(replayed, replayedNext))).isEmpty();
        assertThat(tracker.since()).isEqualTo(replayTime.minusSeconds(60));

        Row newerWindow = window(replayTime, "speedSink", "truck-1", 120_000);
        Row otherSink = window(replayTime, "deliverySink", "truck-1", 0);
        Row otherKey = window(replayTime, "speedSink", "truck-2", 0);
        // An unseen older window committing late must not be lost to a per-key window cursor.
        Row lateCommit = window(replayTime.minusSeconds(30), "speedSink", "truck-1", 30_000);
        assertThat(tracker.fresh(List.of(newerWindow, otherSink, otherKey, lateCommit, replayed)))
                .containsExactly(newerWindow, otherSink, otherKey, lateCommit);
        assertThat(tracker.fresh(List.of(replayed, newerWindow))).isEmpty();

        tracker.reset();
        assertThat(tracker.since()).isEqualTo(Instant.EPOCH);
        assertThat(tracker.fresh(List.of(first))).containsExactly(first);
    }

    private Row window(Instant writtenAt, String sinkId, String key, long windowStart) {
        return new Row(writtenAt, List.of("exec-1", sinkId, key, windowStart),
                Map.of("sinkId", sinkId, "key", key, "windowStart", windowStart,
                        "windowEnd", windowStart + 60_000, "values", Map.of("count", 1.0),
                        "eventCount", 1L));
    }
}
