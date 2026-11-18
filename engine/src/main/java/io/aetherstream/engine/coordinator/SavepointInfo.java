package io.aetherstream.engine.coordinator;

import java.util.List;

/** Durable savepoint.created audit detail. State and offsets remain in checkpoints and the S3 manifest. */
public record SavepointInfo(String savepointId, String executionId, long checkpointId, String pipelineId,
                            int pipelineVersion, int protocolVersion, List<String> operatorIds, String createdAt) {
    public String manifestKey() {
        return "savepoints/" + savepointId + "/manifest.json";
    }
}
