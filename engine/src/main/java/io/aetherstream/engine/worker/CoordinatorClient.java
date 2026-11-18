package io.aetherstream.engine.worker;

import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.WorkerStatus;

/** Worker side of the coordinator protocol (contract 03 section 2). Methods throw on transport/HTTP errors. */
public interface CoordinatorClient {
    RegisterResponse register(RegisterRequest request) throws Exception;

    HeartbeatResponse heartbeat(String workerId, WorkerStatus status) throws Exception;

    AckResponse ack(CheckpointAck ack) throws Exception;
}
