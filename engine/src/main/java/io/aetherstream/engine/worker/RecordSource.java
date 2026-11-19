package io.aetherstream.engine.worker;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Source of raw records for one TaskRuntime. Except for {@link #wakeup()}, methods run only on its source thread,
 * including {@link #close()} (KafkaConsumer is not thread-safe).
 */
public interface RecordSource extends AutoCloseable {
    /** Manually assigns the partitions and seeks each one to its start offset (missing = offset 0). */
    void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets);

    /** Returns the next records (possibly none), waiting at most timeout. */
    List<SourceRecord> poll(Duration timeout);

    /** Consumer positions after the last poll, distinct from offsets admitted to the checkpoint queue. */
    Map<Integer, Long> nextOffsets();

    /** Latest cached end offsets per assigned partition. Must not wait for broker metadata; used for lag. */
    Map<Integer, Long> endOffsets();

    /** Thread-safe: unblocks a pending consumer operation during shutdown. */
    void wakeup();

    @Override
    void close();

    record SourceRecord(int partition, long offset, String value) {}
}
