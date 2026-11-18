# Contract 01: event envelope + simulator events + simulator HTTP API

## 1. Kafka record (wire format)
Topic `truck-events`, 6 partitions. Kafka key = `key` field (UTF-8). Kafka value = UTF-8 JSON object:

```json
{
  "eventId": "s42-truck-007-000153",
  "schemaVersion": 1,
  "type": "truck.position",
  "eventTime": 1795251600000,
  "key": "truck-007",
  "payload": { "truckId": "truck-007", "x": 412.5, "y": 980.0, "speedKmh": 31.4, "heading": 90.0, "routeId": "r-3", "status": "DRIVING" }
}
```
| Field | Type | Rule |
|---|---|---|
| eventId | string, non-empty | globally unique per logical event. Simulator: `s<seed>-<truckId>-<seq 6 digits>`. Duplicates (same eventId) may be re-sent on purpose (chaos). |
| schemaVersion | int | must be `1` |
| type | string | `truck.position` or `truck.delivery` |
| eventTime | long | epoch milliseconds in **simulation time** (see 4) |
| key | string | equals `payload.truckId` |
| payload | object | per type, below |

`truck.position` payload: `truckId` string, `x` number (0..2000 metres), `y` number (0..2000), `speedKmh` number >= 0,
`heading` number degrees 0..360, `routeId` string, `status` one of `DRIVING|UNLOADING|IDLE|STOPPED`.

`truck.delivery` payload: `truckId` string, `stopId` string (e.g. `stop-12`), `x` number, `y` number, `parcels` int >= 1.

## 2. Engine envelope (in-memory, Java record `io.aetherstream.common.Envelope`)
The engine source wraps each Kafka record:
```
Envelope(String eventId, int schemaVersion, String type, long eventTime, String key,
         JsonNode payload, SourcePosition source)
SourcePosition(String topic, int partition, long offset)
```
A record is **REJECTED** (goes to `dead_letters` with kind `REJECTED`) if: the value is not valid JSON; any envelope
field is missing or has the wrong type; `schemaVersion != 1`; `type` unknown; `key` != `payload.truckId`; or a payload field
violates its rule above. Rejected records use `eventId` if present and a string, else `p<partition>-o<offset>`.
Reason strings are short and human-readable, e.g. `"missing field payload.speedKmh"`, `"unparseable JSON"`.
Validation lives in `io.aetherstream.compiler.ops.EnvelopeValidator` (shared by engine and reference).

## 3. Deduplication policy (declared)
Within a keyed window, an `eventId` counts at most once (window state keeps the set of eventIds it has absorbed).
A duplicate of an event whose window already fired is LATE-or-dropped by the normal watermark rules (contract 02 §4).

## 4. Simulation time
`eventTime = simStartEpochMs + (realElapsedMs * timeScale)`. Defaults: `simStartEpochMs = 1795251600000`
(2026-11-21T09:00:00Z, the expo day), `timeScale = 10` (1 simulated minute = 6 real seconds). Position events every
`positionIntervalSimMs = 2000` simulated ms per truck. Within one truck, eventTime is strictly increasing except for
injected late events.

## 5. Simulator behaviour
- City: 2000 x 2000 m grid; depot at (1000,1000); 24 delivery stops at fixed coordinates derived from `seed`.
- Starts with `initialTrucks = 8` trucks (`truck-001`..). Each truck loops: depot -> 3–5 stops -> depot, driving along
  grid streets (Manhattan paths), speed 20–50 km/h with seeded jitter, UNLOADING 30–90 sim-seconds at each stop (speed 0),
  emitting a `truck.delivery` event when unloading finishes.
- `truck-007` is the "stuck truck" scenario when `stuckTruckDemo=true` (default): after 5 sim-minutes it stops
  (status `STOPPED`, speed 0) for 15 sim-minutes while still emitting position events, so the stuck-truck rule fires.
- Determinism: given the same seed and the same sequence of API calls, each truck's event sequence (payloads, eventTime
  offsets, eventIds) is identical. Use `java.util.SplittableRandom(seed ^ truckIndex)` per truck.
- Kafka producer: `acks=all`, `enable.idempotence=true`, key = truckId.
- About 1 in 400 records is deliberately malformed when `malformedRate` > 0 (default 0.0025): e.g. missing `speedKmh` or
  negative speed. These must keep `key` and partitioning correct.

## 6. Simulator HTTP API (port 8070, JSON)
| Method/path | Body | Response |
|---|---|---|
| GET /health | - | `{"status":"UP"}` |
| GET /state | - | `{"running":true,"timeScale":10,"simTime":1795251660000,"trucks":[{"truckId","x","y","speedKmh","heading","status","routeId"}],"stops":[{"stopId","x","y"}],"depot":{"x":1000,"y":1000},"emitted":12345}` |
| POST /trucks | `{"x":500,"y":700}` (optional) | `{"truckId":"truck-009"}` — adds a truck at the nearest street point; it joins the loop |
| DELETE /trucks/{truckId} | - | 204 |
| POST /chaos/late | `{"count":20,"lateByMs":180000}` | `{"sent":20}` — re-emits position events for random trucks with eventTime = current simTime - lateByMs, fresh eventIds with suffix `-late-<n>` |
| POST /chaos/duplicates | `{"count":20}` | `{"sent":20}` — re-sends the last N records verbatim (same eventId) |
| POST /chaos/malformed | `{"count":5}` | `{"sent":5}` |
| POST /control | `{"running":false}` or `{"timeScale":20}` | current state summary |

Simulator config via env: `SIM_SEED` (42), `SIM_TIME_SCALE` (10), `SIM_INITIAL_TRUCKS` (8), `SIM_MALFORMED_RATE` (0.0025),
`SIM_STUCK_TRUCK_DEMO` (true), plus `KAFKA_BOOTSTRAP`, `KAFKA_TOPIC`.
