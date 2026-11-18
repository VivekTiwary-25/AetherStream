package io.aetherstream.common;

public record SourcePosition(String topic, int partition, long offset) {}
