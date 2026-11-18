package io.aetherstream.common.protocol;

public record RegisterRequest(int protocolVersion, String workerId, String host) {}
