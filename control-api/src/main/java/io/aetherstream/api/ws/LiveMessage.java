package io.aetherstream.api.ws;

/** Contract 04's envelope; ts is epoch milliseconds, seq is process-wide publication order. */
public record LiveMessage(String type, long ts, Object data, long seq) {}
