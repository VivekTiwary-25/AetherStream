package io.aetherstream.engine.coordinator;

/** A restore request that cannot safely consume the saved operator state or source positions. */
public final class IncompatibleSavepointException extends IllegalStateException {
    public IncompatibleSavepointException(String message) {
        super(message);
    }
}
