# R01: First-pass contract review of the merged ops runtime and coordinator (read-only, write a verdict)

You are a reviewer. Do NOT change any code. Read docs/contracts/ (all files), then review these merged files on main
against the contracts:
- compiler/src/main/java/io/aetherstream/compiler/ops/** (PartitionPipeline, EnvelopeValidator, Condition, MapOp, ...)
  against contract 01 section 2-3 and contract 02 section 2 and 4 (late check before watermark update, firing after update
  ordered by (windowEnd, key), eventId dedup per key-window, rule streak + alertId format + message, fan-out order,
  per-partition watermark, deterministic snapshot).
- engine/src/main/java/io/aetherstream/engine/coordinator/** against contract 03 (manifest written before COMPLETED, acks
  only for current generation, recovery uses latest COMPLETED only, generation bump before reassign, heartbeat timeout
  4000 ms, checkpoint 0 offsets, KILL/SET_SINK_DELAY, ClusterState shape).

Files (create only this):
- docs/reviews/R01-ops-coordinator.md

Write in that file: a verdict line `VERDICT: PASS` or `VERDICT: FAIL`, then a numbered list of concrete findings, each with
file:line, what the contract says, what the code does, and severity (BLOCKER = wrong results or broken recovery,
MAJOR, MINOR). Only real deviations; no style nits. Keep it under 80 lines.

## Rules
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create docs/reviews/R01-ops-coordinator.md.
