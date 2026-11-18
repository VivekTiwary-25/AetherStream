package io.aetherstream.api.ws;

import io.aetherstream.api.ws.IncrementalResultRepository.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks one execution's result stream using only its maximum timestamp and keys at that timestamp.
 * Poll inclusively from {@link #since()} and reset when switching executions or result streams.
 */
public class NewRowsTracker {
    private Instant since = Instant.EPOCH;
    private Set<List<Object>> keysAtSince = new HashSet<>();

    public NewRowsTracker() {}

    public synchronized Instant since() {
        return since;
    }

    /**
     * Returns unseen timestamp/key pairs in input order, including new keys at the current boundary.
     * The starting boundary stays fixed while processing an unordered batch; older history is discarded.
     */
    public synchronized List<Row> fresh(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Instant, Set<List<Object>>> keysByTimestamp = new HashMap<>();
        keysByTimestamp.put(since, keysAtSince);
        Instant maximum = since;
        List<Row> fresh = new ArrayList<>();
        for (Row row : rows) {
            Instant timestamp = row.writtenAt();
            if (timestamp.isBefore(since)) {
                continue;
            }
            if (keysByTimestamp.computeIfAbsent(timestamp, ignored -> new HashSet<>()).add(row.key())) {
                fresh.add(row);
            }
            if (timestamp.isAfter(maximum)) {
                maximum = timestamp;
            }
        }
        since = maximum;
        keysAtSince = keysByTimestamp.get(maximum);
        return fresh;
    }

    public synchronized void reset() {
        since = Instant.EPOCH;
        keysAtSince.clear();
    }
}
