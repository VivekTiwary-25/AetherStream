# Fake-data audit B: Logs & Recovery, Recovery History, Truck Breakdown

Scope: `web/src/glorious/recovery/**`. Server sources: `control-api/.../ControlApiController.java`
(`/api/workers` = coordinator `state.workers`, `/api/recovery/history`) and `control-api/.../recovery/RecoveryHistory.java`
(the server log of the last 200 real coordinator audit facts and accepted kill requests; also sent as WS `recovery` frames and in `snapshot.recoveryHistory`).
Check: `npx vitest run src/glorious/recovery` from `web/` → 4 files, 25 tests passed.

| Item | Source | Verdict |
|---|---|---|
| Worker card list | `/api/workers` (dashboard store) ∪ `workerId` of current-execution history entries | OK |
| Worker records/s, queues, backpressure | `WorkerInfo.recordsPerSec / sourceQueue / sinkQueue / backpressured`; missing → "Unavailable" | OK |
| Worker partition chips | `WorkerInfo.partitions`; non-array → "Partition data unavailable", `[]` → "No assigned partitions" | OK |
| Last heartbeat "N s ago" | `WorkerInfo.lastHeartbeatMs` (server ms) vs. browser clock; missing → "Unavailable" | OK (clock skew between server and browser is possible) |
| State **Dead** | `WorkerInfo.alive === false`, or a server `WORKER_DEAD` / `WORKER_RESTARTED` event (held ≥3 s, explained in the UI). `KILL_REQUESTED` never counts as death | OK |
| State Dead for a worker missing from `/api/workers` | Used to say Dead *forever* after any `WORKER_RESTARTED`, even though a restart is not a death | **FIXED**: only `WORKER_DEAD` keeps it Dead; otherwise "Unavailable" after the 3 s hold (model.test.ts) |
| State **Recovering** | `WorkerInfo.state === 'RESTORING'` or execution `state === 'RECOVERING'` (`/api/executions/current` / WS `execution`) | OK |
| State **Rejoined** | server `RECOVERED` event (or `WORKER_RESTARTED`) after the loss, shown for 10 s | OK |
| "Processing paused for N s" banner | Used to count up from the `RECOVERING` event `at` forever, including while disconnected or after the run had stopped recovering (a missed `RECOVERED` gave an endless made-up number) | **FIXED**: the counter shows only while connected *and* the execution state is `RECOVERING`; otherwise "Recovery started <event time>; its end has not been recorded yet" (LogsRecovery.test.tsx) |
| Partition ownership grid P0–P5 | Owners come from live `WorkerInfo.partitions`; the list of partition IDs was hardcoded `[0..5]` | **FIXED / QUESTION**: kept 0–5 (matches `--partitions 6` in docker-compose.yml and infra/kubernetes/kafka.yaml) and also added any other partition the server reports. QUESTION: should the server expose the topic partition count so the UI needs no constant? |
| Reassignment arrow `old → new` | `partitionChanges` from server `REBALANCED` (coordinator `oldAssignment` vs `assignment`) | OK |
| Completed checkpoints table | `getCheckpoints(executionId)` filtered to `state === 'COMPLETED'`; missing time/offsets → "Not recorded" | OK |
| Checkpoint duration | Showed "Unavailable ms" when `durationMs` was missing | **FIXED**: "Not recorded" |
| "Current run restarted from checkpoint #X · generation G" | `ExecutionInfo.restoredFromCheckpoint / generation` | OK |
| History event time | `entry.at` = coordinator `detail.atMs`, else audit row time (server event timestamp, not receipt time). `observedAt` (browser time) is only used for the 3 s Dead hold, never shown | OK |
| History "Restored from checkpoint #X" | `entry.restoredFromCheckpoint` (server); null → "Checkpoint used: not recorded" | OK |
| History pause length | `entry.durationMs` (coordinator RECOVERING→RUNNING), else matching RECOVERING/RECOVERED `at` pair; else "Pause length unknown" | OK |
| History worker / actor / reason | server `workerId` (when missing from the audit fact, the server fills it in from earlier events in the same execution), `actor`, `reason` | OK |
| `/api/recovery/history` 404/500 | alert "Recovery history unavailable: Request failed (HTTP 404)" and "Recovery history could not be loaded."; no rows | OK (test added) |
| `/api/recovery/history` returns `[]` | "No recovery activity yet." | OK |
| Truck Breakdown truck list / status | `getSimulatorState().trucks` + WS `snapshot.simulator.trucks` / `trucks`; empty → "No trucks reported by the simulator.", no made-up option | OK |
| "Resumes after about 15 simulated minutes" | simulator `Simulation.applyScenario`: `scenarioEnd = now + 900_000` | OK |
| Breakdown result | Only the server's accept/error response; no made-up "stopped" state | OK |
