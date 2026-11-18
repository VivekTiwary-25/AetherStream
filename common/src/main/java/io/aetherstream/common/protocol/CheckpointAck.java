package io.aetherstream.common.protocol;

import java.util.Map;

/** partitions: partition to next offset not yet processed. objectKey: S3 key of the worker snapshot file. */
public record CheckpointAck(int protocolVersion, String workerId, String executionId, long generation,
                            long checkpointId, Map<Integer, Long> partitions, String objectKey) {}
