# ADR 0006: Contracts-first parallel development

**Status:** Accepted

## Context

Parallel work across the Java runtime, simulator, API, and UI requires stable boundaries for shared formats and semantics. The contracts define the stack/layout, event envelope, pipeline semantics, worker protocol, and control API (`docs/contracts/README.md`).

## Decision

Treat `docs/contracts/` as the normative interface and read it before implementation. Java records in `:common` represent the shared contracts; implementers follow the written contract and report discrepancies rather than silently changing it (`docs/contracts/00-stack-and-layout.md`, `docs/contracts/README.md`).

## Consequences

Lanes can implement against shared field names, wire behaviour, and semantics with fewer cross-lane assumptions. Contract changes remain centrally coordinated; stale or incorrect contracts can block otherwise-valid implementation until reconciled.

## Alternatives considered

- Let each lane define its own interfaces: faster locally, with greater integration and compatibility risk.
- Change contracts alongside each implementation: obscures ownership and can create conflicting cross-lane edits.
