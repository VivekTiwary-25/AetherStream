# ADR 0005: Deterministic single-process reference executor

**Status:** Accepted

## Context

A distributed engine needs a way to compare checkpoint-visible results against an independent execution path without defining operator behaviour twice. The engine and reference share `PartitionPipeline` semantics (`docs/contracts/02-pipeline-and-semantics.md`).

## Decision

Provide a deterministic, single-process reference executor that replays records partition-by-partition through the same compiler operator implementation. Compare runs over checkpoint-zero-inclusive and latest-completed-checkpoint-exclusive Kafka ranges (`reference/src/main/java/io/aetherstream/reference/ReferenceExecutor.java`, `reference/src/main/java/io/aetherstream/reference/KafkaRangeReader.java`, `reference/src/main/java/io/aetherstream/reference/ResultComparator.java`, `docs/contracts/04-control-api-rest-ws.md`).

## Consequences

Comparison checks the engine against a serial replay of shared semantics, including partition watermarks and checkpoint-visible rows. It is a correctness/reference path, not the distributed hot path; it cannot independently detect a defect shared by the common operator implementation.

## Alternatives considered

- Duplicate operator implementations: could catch shared implementation defects but would drift and make semantic agreement harder to maintain.
- Compare only aggregate counts: cheaper but cannot identify missing, differing, or extra checkpoint-visible results.
