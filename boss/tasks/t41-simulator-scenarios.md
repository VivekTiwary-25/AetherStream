# T41: Simulator scenarios for a lively, repeatable demo

Lane 4 (simulator). The simulator is on main (simulator/src/main/java/io/aetherstream/simulator/**, contract 01).
Make the city feel alive and give the presenter repeatable scenarios, without breaking determinism or the event contract.

Files (edit/create only these):
- simulator/src/main/java/io/aetherstream/simulator/**
- simulator/src/test/java/io/aetherstream/simulator/**

1. Traffic realism (deterministic from the seed): per-street speed limits (main avenues every 600 m faster), slow-down when
   approaching corners (ease in/out), small random stops at intersections (traffic lights), trucks never overlap at exactly the
   same point (tiny lateral lane offset per direction). Events stay exactly contract 01 shapes.
2. Scenarios via `POST /scenario {"name": "..."}` (add to HttpApi; document in the README section below) - each deterministic:
   - `rush-hour`: speeds drop 40% in the centre for 10 sim-minutes (visible in the speed panel),
   - `breakdown`: a chosen truck (`truckId` optional, default truck-005) stops for 12 sim-minutes (fires the stuck rule),
   - `surge`: 6 extra trucks join from the depot over 1 sim-minute,
   - `reset`: back to the initial fleet and normal traffic.
   GET /state gains `"scenario": "none|rush-hour|..."` (additive field only).
3. Tests: determinism still holds with scenarios applied at the same sim times; rush-hour lowers average speed in the centre;
   breakdown produces >= 10 consecutive sim-minutes at speed 0 for that truck; reset restores the fleet size.
4. Add a short section at the top of SimulatorMain.java's Javadoc listing env vars, endpoints and scenarios.

Acceptance check: `ci-java` green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files".
- The acceptance check runs in GitHub Actions on your task branch. Write code carefully so it compiles first time.
- Do not commit secrets or build outputs. In your result, list what you built and anything left undone.
