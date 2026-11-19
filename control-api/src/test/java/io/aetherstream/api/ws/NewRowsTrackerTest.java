package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.api.ws.IncrementalResultRepository.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewRowsTrackerTest {
    private final NewRowsTracker tracker = new NewRowsTracker();
    private final Instant firstTime = Instant.parse("2026-11-19T01:00:00.123456Z");

    @Test
    void startsAtEpochAndEmptyBatchesLeaveCursorAlone() {
        assertThat(tracker.since()).isEqualTo(Instant.EPOCH);
        assertThat(tracker.fresh(List.of())).isEmpty();
        assertThat(tracker.since()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void emitsNewKeysAtTheSameTimestampOnce() {
        Row first = row(firstTime, "first");
        Row second = row(firstTime, "second");
        Row third = row(firstTime, "third");

        assertThat(tracker.fresh(List.of(first, first, second))).containsExactly(first, second);
        assertThat(tracker.since()).isEqualTo(firstTime.minusSeconds(60));
        assertThat(tracker.fresh(List.of(second, third, first, third))).containsExactly(third);
        assertThat(tracker.fresh(List.of(first, second, third))).isEmpty();
        assertThat(tracker.since()).isEqualTo(firstTime.minusSeconds(60));
    }

    @Test
    void advancesToTheMaximumAndSuppressesOverlappingRows() {
        Row first = row(firstTime, "first");
        Row second = row(firstTime.plusSeconds(1), "second");
        Row third = row(firstTime.plusSeconds(1), "third");

        assertThat(tracker.fresh(List.of(first))).containsExactly(first);
        assertThat(tracker.fresh(List.of(first, second))).containsExactly(second);
        assertThat(tracker.since()).isEqualTo(second.writtenAt().minusSeconds(60));
        assertThat(tracker.fresh(List.of(first, second, third))).containsExactly(third);
        assertThat(tracker.fresh(List.of())).isEmpty();
        assertThat(tracker.since()).isEqualTo(second.writtenAt().minusSeconds(60));
    }

    @Test
    void handlesUnorderedBatchesAndDuplicatesAwayFromTheMaximum() {
        Row boundary = row(firstTime, "boundary");
        Row sameBoundary = row(firstTime, "new-boundary");
        Row middle = row(firstTime.plusSeconds(1), "middle");
        Row newest = row(firstTime.plusSeconds(2), "newest");
        Row sameNewest = row(firstTime.plusSeconds(2), "new-at-maximum");
        tracker.fresh(List.of(boundary));

        assertThat(tracker.fresh(List.of(newest, middle, middle, sameBoundary, boundary, newest)))
                .containsExactly(newest, middle, sameBoundary);
        assertThat(tracker.since()).isEqualTo(newest.writtenAt().minusSeconds(60));
        assertThat(tracker.fresh(List.of(newest, middle, sameNewest, sameNewest)))
                .containsExactly(sameNewest);
    }

    @Test
    void deliversRowsCommittedBehindTheCursorOnceWithoutMovingTheBoundaryBackwards() {
        Row newest = row(firstTime, "newest");
        Row lateCommit = row(firstTime.minusSeconds(30), "late");
        Row boundaryCommit = row(firstTime.minusSeconds(60), "boundary");
        tracker.fresh(List.of(newest));

        assertThat(tracker.fresh(List.of(newest, lateCommit, boundaryCommit, lateCommit)))
                .containsExactly(lateCommit, boundaryCommit);
        assertThat(tracker.since()).isEqualTo(firstTime.minusSeconds(60));
        assertThat(tracker.fresh(List.of(lateCommit, boundaryCommit, newest))).isEmpty();
    }

    @Test
    void advancingTheOverlapRetainsDeduplicationAtItsInclusiveBoundary() {
        Row first = row(firstTime, "first");
        Row next = row(firstTime.plusSeconds(60), "next");
        tracker.fresh(List.of(first));

        assertThat(tracker.fresh(List.of(first, next))).containsExactly(next);
        assertThat(tracker.since()).isEqualTo(firstTime);
        assertThat(tracker.fresh(List.of(first, next))).isEmpty();
        Row beyond = row(firstTime.plusSeconds(61), "beyond");
        assertThat(tracker.fresh(List.of(beyond))).containsExactly(beyond);
        assertThat(tracker.fresh(List.of(first, next, beyond))).isEmpty();
    }

    @Test
    void treatsAChangedTimestampForTheSamePrimaryKeyAsFresh() {
        Row first = row(firstTime, "same-key");
        Row updated = row(firstTime.plusNanos(1), "same-key");

        assertThat(tracker.fresh(List.of(first, updated, first, updated))).containsExactly(first, updated);
        assertThat(tracker.since()).isEqualTo(updated.writtenAt().minusSeconds(60));
        assertThat(tracker.fresh(List.of(updated))).isEmpty();
    }

    @Test
    void distinguishesEveryComponentOfACompositePrimaryKey() {
        Row first = window("exec-1", "sink-1", "truck", 1L);
        Row anotherExecution = window("exec-2", "sink-1", "truck", 1L);
        Row anotherSink = window("exec-1", "sink-2", "truck", 1L);
        Row anotherKey = window("exec-1", "sink-1", "other-truck", 1L);
        Row anotherWindow = window("exec-1", "sink-1", "truck", 2L);

        assertThat(tracker.fresh(List.of(first, anotherExecution, anotherSink, anotherKey, anotherWindow, first)))
                .containsExactly(first, anotherExecution, anotherSink, anotherKey, anotherWindow);
    }

    @Test
    void resetClearsTheBoundaryAndDeduplicationKeys() {
        Row first = row(firstTime, "first");
        Row later = row(firstTime.plusSeconds(1), "later");
        tracker.fresh(List.of(first, later));

        tracker.reset();

        assertThat(tracker.since()).isEqualTo(Instant.EPOCH);
        assertThat(tracker.fresh(List.of(first, later))).containsExactly(first, later);
        assertThat(tracker.fresh(List.of(later))).isEmpty();
    }

    private Row row(Instant timestamp, String key) {
        return new Row(timestamp, List.of("exec-1", key), Map.of("key", key));
    }

    private Row window(String executionId, String sinkId, String key, long windowStart) {
        return new Row(firstTime, List.of(executionId, sinkId, key, windowStart), Map.of("key", key));
    }
}
