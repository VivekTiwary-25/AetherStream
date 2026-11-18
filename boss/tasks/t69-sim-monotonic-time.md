# T69 (URGENT): Simulator: simulation time must never go backwards across restarts

Lane 4 (simulator). Bug found on the live deployment: every CD deploy restarts the simulator container and its sim clock
restarts at `simStartEpochMs` (contract 01 section 4). The running execution is restored by the coordinator and has already
seen much later event times per partition, so EVERY new event after a deploy is LATE (dead letter) and the speed/delivery
panels go empty. Kafka keeps old records too.

Fix (keep contract 01 shapes; determinism of the pure `Simulation` class must stay):
1. In the runtime (SimulatorMain / SimClock), anchor sim time to the wall clock so it is monotonic across restarts:
   `simNow = simStartEpochMs + (System.currentTimeMillis() - anchorRealMs) * timeScale`, where `anchorRealMs` comes from env
   `SIM_ANCHOR_EPOCH_MS` (default: 1795219200000 = 2026-11-19T00:00:00Z... choose a fixed default instant and document it).
   If the computed simNow would be before the latest eventTime already in Kafka (read the last record of each partition at
   startup with a short-lived KafkaConsumer; skip if unreachable), start from max(latest)+positionIntervalSimMs instead.
   Changing timeScale at runtime (POST /control) must also keep time monotonic (re-anchor at the current simNow).
2. eventIds must stay unique across restarts: include a run id in the eventId sequence space, e.g. `s<seed>-<truckId>-<seq>`
   where seq continues from a start value derived from simNow (e.g. simNow/1000 * 10 + counter) - keep the format
   `s<seed>-<truckId>-<6+ digits>` (more digits are fine). Duplicate eventIds across restarts would be silently deduped by
   the engine, which is wrong.
3. Unit tests: restart simulation runtime twice with a fake wall clock -> eventTimes strictly increasing per truck across
   restarts; eventIds unique across restarts; pure Simulation determinism test still passes.

Files (edit/create only these):
- simulator/src/main/java/io/aetherstream/simulator/** and simulator/src/test/java/io/aetherstream/simulator/**

Acceptance check: `ci-java` green on your branch, and `ci-e2e` still green.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files".
- If you are running on vivek-pc: do NOT start Docker containers there (the live demo runs there).
- In your result, list what you changed and anything left undone.
