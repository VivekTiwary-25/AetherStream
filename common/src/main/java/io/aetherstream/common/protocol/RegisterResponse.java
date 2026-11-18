package io.aetherstream.common.protocol;

public record RegisterResponse(String workerId, long heartbeatIntervalMs, long heartbeatTimeoutMs) {}
