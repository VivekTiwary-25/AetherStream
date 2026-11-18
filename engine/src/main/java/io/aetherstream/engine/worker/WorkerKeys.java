package io.aetherstream.engine.worker;

/** S3 keys used by the worker (contract 03 section 4). */
public final class WorkerKeys {
    private WorkerKeys() {}

    public static String workerSnapshotKey(String executionId, long checkpointId, String workerId) {
        return "executions/" + executionId + "/checkpoints/" + checkpointId + "/worker-" + workerId + ".json";
    }

    public static String manifestKey(String executionId, long checkpointId) {
        return "executions/" + executionId + "/checkpoints/" + checkpointId + "/manifest.json";
    }
}
