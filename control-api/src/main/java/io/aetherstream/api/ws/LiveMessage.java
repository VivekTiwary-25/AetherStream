package io.aetherstream.api.ws;

/** Contract 04's server-to-browser envelope; ts is wall-clock epoch milliseconds. */
public record LiveMessage(String type, long ts, Object data) {}
