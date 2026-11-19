# ADR 0001: Kafka as the event log

**Status:** Accepted

## Context

Truck events need a durable, partitioned input that can be consumed in key order and replayed after failure. The v1 event contract uses `truck-events` with six partitions, `truckId` as the Kafka key, and JSON envelope values (`docs/contracts/00-stack-and-layout.md`, `docs/contracts/01-event-envelope-and-simulator.md`).

## Decision

Use Apache Kafka 4.3.2 in a single-node KRaft deployment for the baseline. Configure the topic with six partitions and replication factor one; producers use `acks=all` and idempotence. The runtime reads Kafka partitions directly (`engine/src/main/java/io/aetherstream/engine/worker/KafkaRecordSource.java`).

## Consequences

Partitioning provides per-truck ordering and independently replayable offsets. The single broker keeps the local/demo system small but provides no broker redundancy; replication factor one does not protect against node loss.

## Alternatives considered

- A multi-broker Kafka cluster: stronger broker availability, beyond the single-node baseline.
- An in-memory queue: simpler, but cannot provide durable offsets or recovery replay.
