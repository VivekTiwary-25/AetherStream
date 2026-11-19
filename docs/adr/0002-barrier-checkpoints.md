# ADR 0002: Barrier checkpoints and manifest commit

**Status:** Accepted

## Context

A recoverable checkpoint must pair operator state with the next source offsets at one consistent point, despite the worker's source, operator, and sink stages being pipelined. A partial checkpoint must never be selected for restore (`docs/contracts/03-coordinator-worker-protocol.md`).

## Decision

Use aligned barriers: the source records next offsets and queues barriers behind prior records; the operator snapshots partition state at the barrier; the sink flushes prior output before uploading worker snapshots to MinIO. The coordinator waits for acknowledgements covering every partition, uploads the manifest, and only then marks the checkpoint `COMPLETED` (`engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java`, `engine/src/main/java/io/aetherstream/engine/coordinator/Coordinator.java`).

## Consequences

State and offsets share a consistent boundary, and recovery can ignore pending or aborted checkpoints. Checkpoint completion waits for the slowest required acknowledgement and manifest upload; a failed or timed-out checkpoint is unavailable for restore.

## Alternatives considered

- Marking completion before manifest upload: risks advertising a checkpoint whose restore metadata is missing.
- Independent state and offset snapshots: can resume with mismatched state and input position.
- Unaligned snapshots: not used by this baseline protocol.
