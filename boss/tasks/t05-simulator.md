# T05: Truck-city simulator (Kafka producer + HTTP control API)

Lane 4 (simulator). Implement contract 01 sections 1, 4, 5 and 6 exactly. Plain Java 21, no framework: use
`com.sun.net.httpserver.HttpServer` for HTTP and `io.aetherstream.common.Json.MAPPER` for JSON.

Files (create only these):
- simulator/src/main/java/io/aetherstream/simulator/** (SimulatorMain plus any classes: City, Truck, Route, SimClock, EventFactory, HttpApi, ...)
- simulator/src/main/resources/logback.xml
- simulator/src/test/java/io/aetherstream/simulator/**

Design:
- Separate the pure simulation (no Kafka, no threads: `Simulation.step(long simNowMs)` returns the list of
  (key, envelope JSON string) to emit, plus `addTruck`, `removeTruck`, `lateEvents`, `duplicates`, `malformed` methods)
  from the runtime (`SimulatorMain`: ticker thread every 100 real ms advancing the sim clock by 100*timeScale ms,
  KafkaProducer with key = truckId, HTTP API). This makes it unit-testable and deterministic.
- City from the seed: 2000x2000 m, street grid every 200 m, depot (1000,1000), 24 stops on street intersections.
  Trucks follow Manhattan paths between intersections with smooth position interpolation, speed 20-50 km/h with seeded
  jitter, heading in degrees, status DRIVING/UNLOADING/IDLE/STOPPED; a delivery event when unloading finishes;
  the truck-007 stuck scenario from contract 01 section 5.
- Event JSON exactly as contract 01 section 1 (field names, eventId format `s<seed>-<truckId>-<seq 6 digits>`, schemaVersion 1).
- Env config as contract 01 section 6. If Kafka is unreachable at start, retry every 2 s and keep the HTTP API up
  (GET /health returns UP; /state works).
- HTTP endpoints exactly as contract 01 section 6 (JSON responses; 400 with `{"error","message"}` on bad input).

Tests:
- Determinism: two Simulations with seed 42 stepped identically for 30 sim-minutes produce byte-identical event lists.
- Every non-malformed event validates against the contract (fields, types, key == payload.truckId, eventTime increasing per truck).
- truck-007 reports speed 0 / STOPPED for at least 10 consecutive sim-minutes when stuckTruckDemo = true.
- Deliveries happen (at least 1 per truck per 20 sim-minutes) and positions stay within 0..2000.
- lateEvents(n, lateByMs) produce events with eventTime = now - lateByMs and eventIds ending in `-late-<n>`.

Acceptance check: `ci-java` workflow green on your branch (`./gradlew build` runs your tests).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Never open, read or change anything in swarm/.
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
