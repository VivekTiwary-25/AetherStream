package io.aetherstream.common.protocol;

public record AckResponse(boolean accepted, String reason) {}
