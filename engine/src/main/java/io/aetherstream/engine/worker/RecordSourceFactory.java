package io.aetherstream.engine.worker;

/** Creates a fresh RecordSource for a topic (one per TaskRuntime). */
@FunctionalInterface
public interface RecordSourceFactory {
    RecordSource create(String topic);
}
