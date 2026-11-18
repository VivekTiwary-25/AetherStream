package io.aetherstream.engine.coordinator;

/** Time source in epoch milliseconds (fake in tests). */
@FunctionalInterface
public interface Clock {
    Clock SYSTEM = System::currentTimeMillis;

    long nowMs();
}
