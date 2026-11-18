package io.aetherstream.common.protocol;

import java.util.List;

public record HeartbeatResponse(List<Command> commands) {}
