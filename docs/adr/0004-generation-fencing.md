# ADR 0004: Execution generation as fencing token

**Status:** Accepted

## Context

A worker can remain alive after losing its assignment. Its delayed commands or sink writes must not corrupt the current execution after reassignment or recovery (`docs/contracts/03-coordinator-worker-protocol.md`).

## Decision

Increment the execution generation before each reassignment and include it in worker commands, checkpoint acknowledgements, and sink writes. The coordinator rejects stale-generation checkpoint acknowledgements; each sink transaction checks the database generation under a shared row lock before writing (`engine/src/main/java/io/aetherstream/engine/coordinator/Coordinator.java`, `engine/src/main/java/io/aetherstream/engine/worker/PostgresResultSink.java`, `infra/db/schema.sql`).

## Consequences

Old workers may continue running temporarily, but cannot commit sink output as current-generation work. Generation updates serialise against in-flight sink transactions. Every component handling execution commands and writes must preserve the generation value.

## Alternatives considered

- Trust worker cancellation alone: a partitioned or delayed worker may not receive cancellation in time.
- Use timestamps or worker identity as the fence: neither reliably orders old and new assignments.
