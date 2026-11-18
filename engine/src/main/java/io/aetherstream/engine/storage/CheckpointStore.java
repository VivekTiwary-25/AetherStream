package io.aetherstream.engine.storage;

/** Object store for checkpoint files (worker snapshots and manifests). JSON via io.aetherstream.common.Json.MAPPER. */
public interface CheckpointStore {
    /** Serialises value as JSON and stores it under key (overwrites). Throws StorageException on failure. */
    void putJson(String key, Object value);

    /** Reads and deserialises the JSON object under key, or returns null if it does not exist. */
    <T> T getJson(String key, Class<T> type);

    boolean exists(String key);
}
