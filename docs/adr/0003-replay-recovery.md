# ADR 0003: Recovery by replay from checkpoint offsets

**Status:** Accepted

## Context

Worker failure can leave inputs after the last complete checkpoint unprocessed or processed without a durable consistent state. Kafka retains the source records, while MinIO stores snapshots and the manifest identifies each partition's next offset (`docs/contracts/03-coordinator-worker-protocol.md`).

## Decision

On recovery, restore only the latest `COMPLETED` checkpoint, restore each partition's operator snapshot, and seek Kafka to that checkpoint's `startOffsets`. Reassign all partitions after the coordinator changes generation (`engine/src/main/java/io/aetherstream/engine/coordinator/Coordinator.java`, `engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java`, `engine/src/main/java/io/aetherstream/engine/worker/KafkaRecordSource.java`).

## Consequences

Records since the checkpoint are replayed at least once; deterministic operators and Postgres upserts make checkpointed results effectively exactly-once under the declared contract. Replay cost grows with the distance from the last completed checkpoint. Other sinks are not covered by the exactly-once claim (`docs/contracts/03-coordinator-worker-protocol.md`).

## Alternatives considered

- Restore a pending checkpoint: unsafe because it may not cover every partition or have a committed manifest.
- Resume from current broker offsets without restored state: risks skipping input or double-counting state.
- Persist state after every record: finer recovery points, with greater write cost and coordination complexity.
