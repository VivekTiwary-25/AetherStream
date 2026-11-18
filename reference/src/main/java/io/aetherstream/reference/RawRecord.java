package io.aetherstream.reference;

/** One Kafka value at its original source position; value may be null for a tombstone. */
public record RawRecord(int partition, long offset, String value) {}
