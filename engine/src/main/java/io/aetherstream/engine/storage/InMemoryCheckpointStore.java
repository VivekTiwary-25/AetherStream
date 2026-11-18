package io.aetherstream.engine.storage;

import io.aetherstream.common.Json;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory store for tests. Values are kept as JSON strings so serialisation is exercised like with S3. */
public class InMemoryCheckpointStore implements CheckpointStore {
    private final ConcurrentHashMap<String, String> objects = new ConcurrentHashMap<>();

    @Override
    public void putJson(String key, Object value) {
        try {
            objects.put(key, Json.MAPPER.writeValueAsString(value));
        } catch (IOException e) {
            throw new StorageException("cannot serialise " + key, e);
        }
    }

    @Override
    public <T> T getJson(String key, Class<T> type) {
        String json = objects.get(key);
        if (json == null) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new StorageException("cannot parse " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    public Set<String> keys() {
        return new TreeSet<>(objects.keySet());
    }
}
