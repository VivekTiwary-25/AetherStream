package io.aetherstream.engine.worker;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Source of raw records for one TaskRuntime. Used only from the runtime's source thread (KafkaConsumer is not
 * thread-safe), including {@link #close()}.
 */
public interface RecordSource extends AutoCloseable {
    /** Manually assigns the partitions and seeks each one to its start offset (missing = offset 0). */
    void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets);

    /** Returns the next records (possibly none), waiting at most timeout. */
    List<SourceRecord> poll(Duration timeout);

    /** Current end offset (next offset to be written) per assigned partition; used for lag. */
    Map<Integer, Long> endOffsets();

    @Override
    void close();

    record SourceRecord(int partition, long offset, String value) {}
}
