package io.aetherstream.api.ws;

import io.aetherstream.api.ws.IncrementalResultRepository.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Polls one minute behind the maximum written_at to include transactions committed out of order.
 * Retains timestamp/primary-key pairs throughout that inclusive overlap; reset on execution changes.
 */
public class NewRowsTracker {
    private static final Duration OVERLAP = Duration.ofMinutes(1);
    private Instant maximum = Instant.EPOCH;
    private final NavigableMap<Instant, Set<List<Object>>> keysByTimestamp = new TreeMap<>();

    public NewRowsTracker() {}

    public synchronized Instant since() {
        Instant lowerBound = maximum.minus(OVERLAP);
        return lowerBound.isBefore(Instant.EPOCH) ? Instant.EPOCH : lowerBound;
    }

    /**
     * Returns unseen timestamp/key pairs in input order. The starting boundary stays fixed for an
     * unordered batch; history is evicted only after the maximum and overlap boundary advance.
     */
    public synchronized List<Row> fresh(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Instant lowerBound = since();
        List<Row> fresh = new ArrayList<>();
        for (Row row : rows) {
            Instant timestamp = row.writtenAt();
            if (timestamp.isBefore(lowerBound)) {
                continue;
            }
            if (keysByTimestamp.computeIfAbsent(timestamp, ignored -> new HashSet<>()).add(row.key())) {
                fresh.add(row);
            }
            if (timestamp.isAfter(maximum)) {
                maximum = timestamp;
            }
        }
        keysByTimestamp.headMap(since(), false).clear();
        return fresh;
    }

    public synchronized void reset() {
        maximum = Instant.EPOCH;
        keysByTimestamp.clear();
    }
}
