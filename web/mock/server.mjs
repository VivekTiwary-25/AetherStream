// DEV ONLY. Synthetic control-api for UI work. Never deploy this.
// HTTP stack is node:http; WebSocket is the `ws` package. Shapes follow docs/contracts.

import http from 'node:http';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT ?? 18080);
const GRID = 2000;
const STREET = 200;
const DEPOT = { x: 1000, y: 1000 };
const SIM_START = 1795251600000;
const TIME_SCALE = 10;
const SEED = 42;
const QUEUE_CAP = 1000;
const PARTITIONS = 6;
const POSITION_INTERVAL = 2000;
const WINDOW_MS = 60000;
const BOOT_MS = Date.now();

const DEMO = {
  id: 'logistics-demo',
  version: 1,
  name: 'Truck city: speed, deliveries, stuck trucks',
  nodes: [
    {
      id: 'src',
      type: 'source',
      label: 'Truck events',
      position: { x: 0, y: 0 },
      config: { topic: 'truck-events', startFrom: 'latest' },
    },
    {
      id: 'pos',
      type: 'filter',
      config: { conditions: [{ field: 'type', op: '==', value: 'truck.position' }] },
    },
    { id: 'kb1', type: 'keyBy', config: { field: 'key' } },
    {
      id: 'speed1m',
      type: 'window',
      config: {
        kind: 'tumbling',
        sizeMs: 60000,
        outOfOrdernessMs: 5000,
        aggregations: [
          { name: 'avgSpeedKmh', fn: 'avg', field: 'payload.speedKmh' },
          { name: 'maxSpeedKmh', fn: 'max', field: 'payload.speedKmh' },
          { name: 'count', fn: 'count' },
        ],
      },
    },
    { id: 'speedSink', type: 'sink', config: { kind: 'postgres.windows' } },
    {
      id: 'stuck',
      type: 'rule',
      config: {
        ruleId: 'stuck-truck',
        condition: { field: 'avgSpeedKmh', op: '<', value: 1.0 },
        forWindows: 10,
        message: '{key} has not moved for {minutes} min',
      },
    },
    { id: 'alertSink', type: 'sink', config: { kind: 'postgres.alerts' } },
    {
      id: 'del',
      type: 'filter',
      config: { conditions: [{ field: 'type', op: '==', value: 'truck.delivery' }] },
    },
    { id: 'kb2', type: 'keyBy', config: { field: 'key' } },
    {
      id: 'del1m',
      type: 'window',
      config: {
        kind: 'tumbling',
        sizeMs: 60000,
        outOfOrdernessMs: 5000,
        aggregations: [
          { name: 'deliveries', fn: 'count' },
          { name: 'parcels', fn: 'sum', field: 'payload.parcels' },
        ],
      },
    },
    { id: 'delSink', type: 'sink', config: { kind: 'postgres.windows' } },
  ],
  edges: [
    { from: 'src', to: 'pos' },
    { from: 'pos', to: 'kb1' },
    { from: 'kb1', to: 'speed1m' },
    { from: 'speed1m', to: 'speedSink' },
    { from: 'speed1m', to: 'stuck' },
    { from: 'stuck', to: 'alertSink' },
    { from: 'src', to: 'del' },
    { from: 'del', to: 'kb2' },
    { from: 'kb2', to: 'del1m' },
    { from: 'del1m', to: 'delSink' },
  ],
};

const OUTPUT = {
  source: 'EVENT',
  filter: 'EVENT',
  map: 'EVENT',
  keyBy: 'KEYED',
  window: 'WINDOW_RESULT',
  rule: 'ALERT',
  sink: 'NONE',
};

function lcg(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(1664525, s) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function hashCode(str) {
  let h = SEED;
  for (const c of str) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h >>> 0;
}

function round1(n) {
  return Math.round(n * 10) / 10;
}

function clampGrid(v) {
  return Math.min(GRID, Math.max(0, v));
}

function snap(v) {
  return Math.round(v / STREET) * STREET;
}

function simOffset() {
  return (Date.now() - BOOT_MS) * TIME_SCALE;
}

function simNow() {
  return SIM_START + simOffset();
}

function pad(n) {
  return String(n).padStart(6, '0');
}

function intersections() {
  const pts = [];
  for (let x = 0; x <= GRID; x += STREET) {
    for (let y = 0; y <= GRID; y += STREET) {
      if (x === DEPOT.x && y === DEPOT.y) continue;
      pts.push({ x, y });
    }
  }
  return pts;
}

function buildStops() {
  const rng = lcg(SEED);
  const pts = intersections();
  for (let i = pts.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [pts[i], pts[j]] = [pts[j], pts[i]];
  }
  return pts.slice(0, 24).map((p, i) => ({
    stopId: `stop-${i + 1}`,
    x: p.x,
    y: p.y,
  }));
}

function nearestStreet(x, y) {
  x = clampGrid(Number.isFinite(x) ? x : DEPOT.x);
  y = clampGrid(Number.isFinite(y) ? y : DEPOT.y);
  const vx = clampGrid(snap(x));
  const hy = clampGrid(snap(y));
  if (Math.abs(x - vx) <= Math.abs(y - hy)) return { x: vx, y };
  return { x, y: hy };
}

function headingBetween(a, b) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  if (Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? 90 : 270;
  return dy >= 0 ? 0 : 180;
}

function buildPath(start, chosen) {
  const pts = [];
  const add = (x, y, stopId = null) => {
    const prev = pts[pts.length - 1];
    if (prev && prev.x === x && prev.y === y) {
      if (stopId) prev.stopId = stopId;
      return;
    }
    pts.push({ x, y, stopId });
  };
  add(start.x, start.y);
  const sx = clampGrid(snap(start.x));
  const sy = clampGrid(snap(start.y));
  const onV = Math.abs(start.x - sx) < 1e-6;
  const onH = Math.abs(start.y - sy) < 1e-6;
  if (!(onV && onH)) {
    if (onV) add(sx, sy);
    else if (onH) add(sx, start.y);
    else {
      add(sx, start.y);
      add(sx, sy);
    }
  }
  const to = (x, y, stopId = null) => {
    const last = pts[pts.length - 1];
    if (last.x !== x) add(x, last.y);
    add(x, y, stopId);
  };
  to(DEPOT.x, DEPOT.y);
  for (const s of chosen) to(s.x, s.y, s.stopId);
  to(DEPOT.x, DEPOT.y);
  if (pts.length < 2) add(DEPOT.x + STREET, DEPOT.y);
  return pts.map((p) => ({
    x: p.x,
    y: p.y,
    stop: Boolean(p.stopId),
    stopId: p.stopId,
  }));
}

function chooseStops(rng) {
  const pool = stops.slice();
  const n = 3 + Math.floor(rng() * 3);
  const chosen = [];
  for (let i = 0; i < n && pool.length; i++) {
    const j = Math.floor(rng() * pool.length);
    chosen.push(pool.splice(j, 1)[0]);
  }
  return chosen;
}

const stops = buildStops();
const trucks = [];
const stats = new Map();
const pipelineStore = new Map();
const executions = new Map();
const idempotency = new Map();
const checkpoints = new Map();
const windows = [];
const alerts = [];
const deadLetters = [];
const wsWindows = [];
const wsAlerts = [];
const wsDead = [];
const clients = new Set();

const workers = [
  { workerId: 'w-1', partitions: [0, 3] },
  { workerId: 'w-2', partitions: [1, 4] },
  { workerId: 'w-3', partitions: [2, 5] },
].map((w) => ({
  ...w,
  alive: true,
  state: 'RUNNING',
  lastHeartbeatMs: Date.now(),
}));

const partitionOffsets = [0, 0, 0, 0, 0, 0];
const partCounts = [0, 0, 0, 0, 0, 0];
let emittedTotal = 0;
let eventsPerSec = 40;
let rejectedCount = 0;
let lateCount = 0;
let flowMark = Date.now();
let byPartitionLive = { 0: 8, 1: 8, 2: 6, 3: 6, 4: 6, 5: 6 };
let rejectedPerSec = 0;
let latePerSec = 0;
let sinkDelayMs = 0;
let nextTruckNum = 1;
let execSeq = 0;
let dlSeq = 0;
let recoveryTimer = null;
let lastTick = Date.now();
let nextSample = 0;
let nextWindowEnd = WINDOW_MS;
let lastMinute = { deliveries: 0, avgSpeed: 0 };

function blankStats() {
  return { speedSum: 0, speedMax: 0, speedCount: 0, deliveries: 0, parcels: 0 };
}

function ensureStats(truckId) {
  let s = stats.get(truckId);
  if (!s) {
    s = blankStats();
    stats.set(truckId, s);
  }
  return s;
}

function createTruck(num, start) {
  const rng = lcg((SEED ^ num) >>> 0);
  const chosen = chooseStops(rng);
  const cruise = round1(20 + rng() * 30);
  const startPt = nearestStreet(start?.x ?? DEPOT.x, start?.y ?? DEPOT.y);
  const path = buildPath(startPt, chosen);
  // truck-007 is stopped from t=0 so ten 1-minute windows fire the stuck alert
  // at ~10 sim-minutes (task timing; contract 01 delays the stop by 5 sim-minutes).
  const stuck = num === 7;
  const truck = {
    truckId: `truck-${String(num).padStart(3, '0')}`,
    routeId: `r-${(num % 6) + 1}`,
    partition: num % PARTITIONS,
    cruiseKmh: cruise,
    speedKmh: stuck ? 0 : cruise,
    heading: path.length > 1 ? headingBetween(path[0], path[1]) : 0,
    status: stuck ? 'STOPPED' : 'DRIVING',
    stuck,
    x: path[0].x,
    y: path[0].y,
    path,
    pathIndex: 0,
    unloadUntil: 0,
    unloadStop: null,
    stuckWindows: 0,
    jitter: rng() * Math.PI * 2,
  };
  ensureStats(truck.truckId);
  return truck;
}

function jitterSpeed(truck) {
  const wave = 0.5 + 0.5 * Math.sin(simOffset() / 5000 + truck.jitter);
  return round1(Math.min(50, Math.max(20, truck.cruiseKmh * (0.85 + 0.3 * wave))));
}

function beginUnload(truck, point) {
  truck.status = 'UNLOADING';
  truck.speedKmh = 0;
  const rng = lcg(hashCode(`${truck.truckId}:${point.stopId}`));
  truck.unloadUntil = simOffset() + 30000 + Math.floor(rng() * 60001);
  truck.unloadStop = {
    stopId: point.stopId || 'stop-0',
    x: point.x,
    y: point.y,
    parcels: 1 + Math.floor(rng() * 4),
  };
}

function advanceTruck(truck, simDtMs) {
  if (truck.stuck) {
    truck.status = 'STOPPED';
    truck.speedKmh = 0;
    return;
  }
  if (truck.status === 'UNLOADING') {
    truck.speedKmh = 0;
    if (simOffset() < truck.unloadUntil) return;
    const drop = truck.unloadStop;
    if (drop) {
      const s = ensureStats(truck.truckId);
      s.deliveries += 1;
      s.parcels += drop.parcels;
      emittedTotal += 1;
      partitionOffsets[truck.partition] += 1;
    }
    truck.unloadStop = null;
    truck.status = 'DRIVING';
  }
  if (!truck.path || truck.path.length < 2) return;
  let left = (truck.cruiseKmh / 3600) * (simDtMs / 1000);
  truck.speedKmh = jitterSpeed(truck);
  let guard = 0;
  while (left > 1e-4 && guard++ < 64) {
    if (truck.pathIndex >= truck.path.length - 1) {
      truck.pathIndex = 0;
      truck.x = truck.path[0].x;
      truck.y = truck.path[0].y;
    }
    const target = truck.path[truck.pathIndex + 1];
    if (!target) break;
    const dx = target.x - truck.x;
    const dy = target.y - truck.y;
    const dist = Math.hypot(dx, dy);
    truck.heading = Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? 90 : 270) : (dy >= 0 ? 0 : 180);
    if (dist <= left || dist < 1e-6) {
      left -= Math.min(left, dist);
      truck.x = target.x;
      truck.y = target.y;
      truck.pathIndex += 1;
      if (target.stop) {
        beginUnload(truck, target);
        return;
      }
    } else {
      truck.x += (dx / dist) * left;
      truck.y += (dy / dist) * left;
      left = 0;
    }
  }
  truck.x = clampGrid(truck.x);
  truck.y = clampGrid(truck.y);
}

function recordSamples() {
  for (const truck of trucks) {
    const speed = truck.stuck || truck.status !== 'DRIVING' ? 0 : truck.speedKmh;
    const s = ensureStats(truck.truckId);
    s.speedSum += speed;
    s.speedMax = Math.max(s.speedMax, speed);
    s.speedCount += 1;
    partitionOffsets[truck.partition] += 1;
    partCounts[truck.partition] += 1;
    emittedTotal += 1;
  }
}

function runningExecution() {
  for (const e of executions.values()) {
    if (e.state === 'RUNNING' || e.state === 'RECOVERING') return e;
  }
  return null;
}

function latestExecution() {
  let last = null;
  for (const e of executions.values()) last = e;
  return last;
}

function resultExecution() {
  return runningExecution() ?? latestExecution();
}

function pushCapped(arr, item, cap) {
  arr.push(item);
  if (arr.length > cap) arr.splice(0, arr.length - cap);
}

function addDeadLetter(row) {
  const stored = {
    eventId: row.eventId,
    kind: row.kind,
    reason: row.reason,
    partition: row.partition,
    offset: row.offset,
    eventTime: row.eventTime,
    executionId: row.executionId,
  };
  pushCapped(deadLetters, stored, 500);
  wsDead.push(publicDead(stored));
  if (row.kind === 'REJECTED') rejectedCount += 1;
  else if (row.kind === 'LATE') lateCount += 1;
}

function closeWindow(startOff, endOff) {
  const exec = resultExecution();
  const windowStart = SIM_START + startOff;
  const windowEnd = SIM_START + endOff;
  let speedSum = 0;
  let speedN = 0;
  let deliveries = 0;
  for (const truck of trucks) {
    const s = ensureStats(truck.truckId);
    const speedValues = { count: s.speedCount };
    if (s.speedCount > 0) {
      const avg = s.speedSum / s.speedCount;
      speedValues.avgSpeedKmh = round1(avg);
      speedValues.maxSpeedKmh = round1(s.speedMax);
      speedSum += avg;
      speedN += 1;
    }
    const speedRow = {
      sinkId: 'speedSink',
      key: truck.truckId,
      windowStart,
      windowEnd,
      values: speedValues,
      eventCount: s.speedCount,
      executionId: exec?.id ?? null,
    };
    pushCapped(windows, speedRow, 4000);
    wsWindows.push(publicWindow(speedRow));
    const delRow = {
      sinkId: 'delSink',
      key: truck.truckId,
      windowStart,
      windowEnd,
      values: { deliveries: s.deliveries, parcels: s.parcels },
      eventCount: s.deliveries,
      executionId: exec?.id ?? null,
    };
    pushCapped(windows, delRow, 4000);
    wsWindows.push(publicWindow(delRow));
    deliveries += s.deliveries;
    if (truck.stuck) {
      const avg = s.speedCount > 0 ? s.speedSum / s.speedCount : 999;
      truck.stuckWindows = avg < 1 ? truck.stuckWindows + 1 : 0;
      if (truck.stuckWindows === 10) {
        const alert = {
          alertId: `stuck|${truck.truckId}|${windowStart}`,
          key: truck.truckId,
          ruleId: 'stuck-truck',
          message: `${truck.truckId} has not moved for 10 min`,
          eventTime: windowEnd,
          executionId: exec?.id ?? null,
        };
        pushCapped(alerts, alert, 200);
        wsAlerts.push(publicAlert(alert));
      }
    }
    stats.set(truck.truckId, blankStats());
  }
  lastMinute = { deliveries, avgSpeed: speedN ? speedSum / speedN : 0 };
  const n = Math.round(endOff / WINDOW_MS);
  if (n % 3 === 0) {
    addDeadLetter({
      eventId: `s42-malformed-${pad(++dlSeq)}`,
      kind: 'REJECTED',
      reason: 'missing field payload.speedKmh',
      partition: n % PARTITIONS,
      offset: emittedTotal,
      eventTime: windowEnd - 1000,
      executionId: exec?.id ?? null,
    });
  }
  if (n % 5 === 0) {
    addDeadLetter({
      eventId: `s42-truck-003-${pad(++dlSeq)}-late-1`,
      kind: 'LATE',
      reason: `window [${windowStart - WINDOW_MS},${windowStart}) already closed (watermark ${windowEnd})`,
      partition: 2,
      offset: emittedTotal,
      eventTime: windowStart - 180000,
      executionId: exec?.id ?? null,
    });
  }
}

function tick() {
  const now = Date.now();
  const simDt = (now - lastTick) * TIME_SCALE;
  lastTick = now;
  const offset = simOffset();
  for (const truck of trucks) advanceTruck(truck, simDt);
  while (nextSample < nextWindowEnd && offset >= nextSample) {
    recordSamples();
    nextSample += POSITION_INTERVAL;
  }
  while (offset >= nextWindowEnd) {
    closeWindow(nextWindowEnd - WINDOW_MS, nextWindowEnd);
    nextWindowEnd += WINDOW_MS;
  }
  while (offset >= nextSample) {
    recordSamples();
    nextSample += POSITION_INTERVAL;
  }
}

function publicWindow(row) {
  return {
    sinkId: row.sinkId,
    key: row.key,
    windowStart: row.windowStart,
    windowEnd: row.windowEnd,
    values: row.values,
    eventCount: row.eventCount,
  };
}

function publicAlert(row) {
  return {
    alertId: row.alertId,
    key: row.key,
    ruleId: row.ruleId,
    message: row.message,
    eventTime: row.eventTime,
  };
}

function publicDead(row) {
  return {
    eventId: row.eventId,
    kind: row.kind,
    reason: row.reason,
    partition: row.partition,
    offset: row.offset,
    eventTime: row.eventTime,
  };
}

function truckState(t) {
  return {
    truckId: t.truckId,
    x: round1(t.x),
    y: round1(t.y),
    speedKmh: round1(t.speedKmh),
    heading: t.heading,
    status: t.status,
    routeId: t.routeId,
  };
}

function truckWs(t) {
  return {
    truckId: t.truckId,
    x: round1(t.x),
    y: round1(t.y),
    speedKmh: round1(t.speedKmh),
    heading: t.heading,
    status: t.status,
  };
}

function workerView(w) {
  const slow = sinkDelayMs > 0 && w.alive;
  const size = 2 + ((Math.floor(Date.now() / 1000) + w.partitions[0]) % 6);
  const share = w.partitions.length / PARTITIONS;
  return {
    workerId: w.workerId,
    alive: w.alive,
    state: w.state,
    partitions: w.partitions.slice(),
    sourceQueue: { size: slow ? QUEUE_CAP : size, capacity: QUEUE_CAP },
    sinkQueue: { size: slow ? QUEUE_CAP : Math.min(size, 3), capacity: QUEUE_CAP },
    backpressured: slow,
    recordsPerSec: slow ? 0.2 : round1(eventsPerSec * share),
    lastHeartbeatMs: w.alive ? Date.now() : w.lastHeartbeatMs,
  };
}

function executionInfo(exec) {
  if (!exec) return null;
  return {
    id: exec.id,
    pipelineId: exec.pipelineId,
    pipelineVersion: exec.pipelineVersion,
    state: exec.state,
    generation: exec.generation,
    restoredFromCheckpoint: exec.restoredFromCheckpoint,
    lastCompletedCheckpoint: exec.lastCompletedCheckpoint,
    createdAt: exec.createdAt,
  };
}

function sourceOffsets() {
  const out = {};
  for (let p = 0; p < PARTITIONS; p++) out[String(p)] = partitionOffsets[p];
  return out;
}

function makeCheckpoint(exec, id) {
  return {
    checkpointId: id,
    state: 'COMPLETED',
    generation: exec.generation,
    durationMs: id === 0 ? 0 : 28 + (id % 37),
    completedAt: new Date().toISOString(),
    sourceOffsets: id === 0 ? { 0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 } : sourceOffsets(),
  };
}

function simulatorState() {
  return {
    running: true,
    timeScale: TIME_SCALE,
    simTime: Math.round(simNow()),
    trucks: trucks.map(truckState),
    stops: stops.map((s) => ({ stopId: s.stopId, x: s.x, y: s.y })),
    depot: { x: DEPOT.x, y: DEPOT.y },
    emitted: emittedTotal,
  };
}

function summary() {
  const exec = resultExecution();
  const id = exec?.id ?? null;
  const mine = (rows) => rows.filter((r) => r.executionId === id);
  return {
    executionId: exec?.id ?? '',
    windowsWritten: mine(windows).length,
    alerts: mine(alerts).length,
    rejected: mine(deadLetters).filter((d) => d.kind === 'REJECTED').length,
    late: mine(deadLetters).filter((d) => d.kind === 'LATE').length,
    deliveriesLastMinute: lastMinute.deliveries,
    avgSpeedLastMinute: round1(lastMinute.avgSpeed),
    eventsPerSec: round1(eventsPerSec),
  };
}

function snapshot() {
  return {
    execution: executionInfo(runningExecution()),
    workers: workers.map(workerView),
    summary: summary(),
    simulator: simulatorState(),
  };
}

function flowData() {
  const now = Date.now();
  const dt = Math.max(0.001, (now - flowMark) / 1000);
  flowMark = now;
  const byPartition = {};
  let sum = 0;
  for (let i = 0; i < PARTITIONS; i++) {
    const rate = partCounts[i] / dt;
    byPartition[String(i)] = round1(rate);
    sum += rate;
    partCounts[i] = 0;
  }
  eventsPerSec = round1(sum);
  rejectedPerSec = round1(rejectedCount / dt);
  latePerSec = round1(lateCount / dt);
  rejectedCount = 0;
  lateCount = 0;
  byPartitionLive = byPartition;
  return { eventsPerSec, byPartition, rejectedPerSec, latePerSec };
}

function broadcast(type, data) {
  const msg = JSON.stringify({ type, ts: Date.now(), data });
  for (const ws of clients) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

function emitExecution(exec, event, extra = {}) {
  if (!exec) return;
  const data = { ...executionInfo(exec), event };
  if (extra.checkpointId != null) data.checkpointId = extra.checkpointId;
  if (extra.workerId != null) data.workerId = extra.workerId;
  broadcast('execution', data);
}
function clearRecovery() {
  clearTimeout(recoveryTimer);
  recoveryTimer = null;
}

function resetWindowStats() {
  for (const t of trucks) {
    stats.set(t.truckId, blankStats());
    t.stuckWindows = 0;
  }
}

function storePipeline(def) {
  const id = String(def.id);
  const list = pipelineStore.get(id) ?? [];
  const version = list.length + 1;
  const createdAt = new Date().toISOString();
  const definition = {
    id,
    version,
    name: String(def.name ?? id),
    nodes: structuredClone(def.nodes ?? []),
    edges: structuredClone(def.edges ?? []),
  };
  list.push({ version, name: definition.name, createdAt, definition });
  pipelineStore.set(id, list);
  return { id, version, name: definition.name, createdAt };
}

function startExecution(pipelineId, key) {
  const list = pipelineStore.get(pipelineId);
  if (!list) return { error: { status: 404, error: 'NOT_FOUND', message: `Unknown pipeline ${pipelineId}` } };
  if (!key || typeof key !== 'string') {
    return { error: { status: 400, error: 'BAD_REQUEST', message: 'idempotencyKey is required' } };
  }
  const existingId = idempotency.get(key);
  if (existingId && executions.has(existingId)) {
    return { body: executionInfo(executions.get(existingId)) };
  }
  clearRecovery();
  for (const e of executions.values()) {
    if (e.state === 'RUNNING' || e.state === 'RECOVERING') {
      e.state = 'STOPPED';
      emitExecution(e, 'STOPPED');
    }
  }
  const latest = list[list.length - 1];
  const exec = {
    id: `exec-mock-${++execSeq}`,
    pipelineId,
    pipelineVersion: latest.version,
    state: 'RUNNING',
    generation: 1,
    restoredFromCheckpoint: null,
    lastCompletedCheckpoint: 0,
    createdAt: new Date().toISOString(),
  };
  executions.set(exec.id, exec);
  idempotency.set(key, exec.id);
  checkpoints.set(exec.id, [makeCheckpoint(exec, 0)]);
  resetWindowStats();
  for (const w of workers) {
    w.alive = true;
    w.state = 'RUNNING';
    w.lastHeartbeatMs = Date.now();
  }
  emitExecution(exec, 'STARTED');
  return { body: executionInfo(exec) };
}

function tickCheckpoint() {
  const exec = runningExecution();
  if (!exec || exec.state !== 'RUNNING') return;
  exec.lastCompletedCheckpoint += 1;
  const cp = makeCheckpoint(exec, exec.lastCompletedCheckpoint);
  const list = checkpoints.get(exec.id) ?? [];
  pushCapped(list, cp, 200);
  checkpoints.set(exec.id, list);
  emitExecution(exec, 'CHECKPOINT_COMPLETED', { checkpointId: cp.checkpointId });
}

function killWorker(workerId) {
  const exec = runningExecution();
  if (!exec) return { error: { status: 409, error: 'NO_EXECUTION', message: 'No running execution' } };
  let target = null;
  if (workerId) {
    target = workers.find((w) => w.workerId === workerId) ?? null;
    if (!target) return { error: { status: 404, error: 'NOT_FOUND', message: `Unknown worker ${workerId}` } };
  } else {
    const pool = workers.filter((w) => w.alive);
    const ranked = (pool.length ? pool : workers).slice().sort((a, b) => {
      if (b.partitions.length !== a.partitions.length) return b.partitions.length - a.partitions.length;
      return a.workerId < b.workerId ? -1 : 1;
    });
    target = ranked[0];
  }
  clearRecovery();
  target.alive = false;
  target.lastHeartbeatMs = Date.now();
  const cp = exec.lastCompletedCheckpoint;
  exec.state = 'RECOVERING';
  exec.restoredFromCheckpoint = cp;
  exec.generation += 1;
  for (const w of workers) {
    if (w !== target && w.alive) w.state = 'RESTORING';
  }
  emitExecution(exec, 'WORKER_DEAD', { workerId: target.workerId, checkpointId: cp });
  emitExecution(exec, 'RECOVERING', { workerId: target.workerId, checkpointId: cp });
  const execId = exec.id;
  const generation = exec.generation;
  const killedId = target.workerId;
  recoveryTimer = setTimeout(() => {
    recoveryTimer = null;
    if (exec.id !== execId || exec.generation !== generation || exec.state !== 'RECOVERING') return;
    for (const w of workers) {
      w.alive = true;
      w.state = 'RUNNING';
      w.lastHeartbeatMs = Date.now();
    }
    exec.state = 'RUNNING';
    emitExecution(exec, 'RECOVERED', { workerId: killedId, checkpointId: cp });
  }, 4000);
  return { body: { workerId: target.workerId } };
}

function clampCount(value, fallback) {
  if (value == null) return fallback;
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return fallback;
  return Math.min(1000, Math.floor(n));
}

function injectLate(count, lateByMs) {
  const n = clampCount(count, 20);
  const late = Number.isFinite(Number(lateByMs)) ? Number(lateByMs) : 180000;
  const exec = resultExecution();
  const now = Math.round(simNow());
  for (let i = 0; i < n; i++) {
    const truck = trucks[i % trucks.length];
    const end = Math.floor(simOffset() / WINDOW_MS) * WINDOW_MS;
    const start = end - WINDOW_MS;
    addDeadLetter({
      eventId: `s42-${truck.truckId}-${pad(++dlSeq)}-late-${i + 1}`,
      kind: 'LATE',
      reason: `window [${SIM_START + start},${SIM_START + end}) already closed (watermark ${now})`,
      partition: i % PARTITIONS,
      offset: ++partitionOffsets[i % PARTITIONS],
      eventTime: now - late,
      executionId: exec?.id ?? null,
    });
  }
  return { sent: n };
}

function injectMalformed(count) {
  const n = clampCount(count, 5);
  const exec = resultExecution();
  for (let i = 0; i < n; i++) {
    addDeadLetter({
      eventId: `s42-malformed-${pad(++dlSeq)}`,
      kind: 'REJECTED',
      reason: i % 2 === 0 ? 'missing field payload.speedKmh' : 'payload.speedKmh must be >= 0',
      partition: i % PARTITIONS,
      offset: ++partitionOffsets[i % PARTITIONS],
      eventTime: Math.round(simNow()),
      executionId: exec?.id ?? null,
    });
  }
  return { sent: n };
}

function injectDuplicates(count) {
  const n = clampCount(count, 20);
  emittedTotal += n;
  return { sent: n };
}

function addTruck(x, y) {
  const pt = nearestStreet(Number(x), Number(y));
  const truck = createTruck(nextTruckNum++, pt);
  trucks.push(truck);
  return { truckId: truck.truckId };
}

function hasKeyByUpstream(nodeId, nodes, edges) {
  const typeOf = new Map(nodes.map((n) => [n.id, n.type]));
  const incoming = new Map();
  for (const e of edges) {
    if (!incoming.has(e.to)) incoming.set(e.to, []);
    incoming.get(e.to).push(e.from);
  }
  const seen = new Set();
  const stack = [...(incoming.get(nodeId) ?? [])];
  while (stack.length) {
    const id = stack.pop();
    if (seen.has(id)) continue;
    seen.add(id);
    if (typeOf.get(id) === 'keyBy') return true;
    for (const src of incoming.get(id) ?? []) stack.push(src);
  }
  return false;
}

function buildPlan(def) {
  const nodes = def.nodes ?? [];
  const edges = def.edges ?? [];
  const incoming = new Map(nodes.map((n) => [n.id, []]));
  const outgoing = new Map(nodes.map((n) => [n.id, []]));
  for (const e of edges) {
    if (outgoing.has(e.from)) outgoing.get(e.from).push(e.to);
    if (incoming.has(e.to)) incoming.get(e.to).push(e.from);
  }
  const indeg = new Map(nodes.map((n) => [n.id, (incoming.get(n.id) ?? []).length]));
  const ready = nodes.map((n) => n.id).filter((id) => indeg.get(id) === 0);
  const order = [];
  const byId = new Map(nodes.map((n) => [n.id, n]));
  while (ready.length) {
    ready.sort();
    const id = ready.shift();
    order.push(id);
    for (const to of outgoing.get(id) ?? []) {
      indeg.set(to, indeg.get(to) - 1);
      if (indeg.get(to) === 0) ready.push(to);
    }
  }
  for (const n of nodes) if (!order.includes(n.id)) order.push(n.id);
  const source = nodes.find((n) => n.type === 'source');
  return {
    pipelineId: def.id,
    pipelineVersion: def.version,
    topic: source?.config?.topic ?? 'truck-events',
    partitions: PARTITIONS,
    startFrom: source?.config?.startFrom ?? 'latest',
    operators: order.map((id) => {
      const n = byId.get(id);
      return {
        id: n.id,
        type: n.type,
        config: n.config ?? {},
        inputs: (incoming.get(id) ?? []).slice().sort(),
        outputType: OUTPUT[n.type] ?? 'NONE',
      };
    }),
  };
}

function validatePipeline(def) {
  const errors = [];
  if (!def || typeof def !== 'object' || Array.isArray(def) || !Array.isArray(def.nodes)) {
    errors.push({ nodeId: null, code: 'NO_SOURCE', message: 'Pipeline has no source. Add a source node.' });
    errors.push({ nodeId: null, code: 'NO_SINK', message: 'Pipeline has no sink. Add a sink node.' });
    return { valid: false, errors, plan: null };
  }
  const nodes = def.nodes;
  const edges = Array.isArray(def.edges) ? def.edges : [];
  if (!nodes.some((n) => n && n.type === 'source')) {
    errors.push({ nodeId: null, code: 'NO_SOURCE', message: 'Pipeline has no source. Add a source node.' });
  }
  if (!nodes.some((n) => n && n.type === 'sink')) {
    errors.push({ nodeId: null, code: 'NO_SINK', message: 'Pipeline has no sink. Add a sink node.' });
  }
  for (const n of nodes) {
    if (n && n.type === 'window' && !hasKeyByUpstream(n.id, nodes, edges)) {
      errors.push({
        nodeId: n.id ?? null,
        code: 'TYPE_MISMATCH',
        message: `Window '${n.id}' needs a Key-by before it`,
      });
    }
  }
  const valid = errors.length === 0;
  return { valid, errors, plan: valid ? buildPlan(def) : null };
}

function limitOf(q, fallback) {
  if (!q.has('limit')) return fallback;
  const n = Number(q.get('limit'));
  if (!Number.isFinite(n) || n < 0) return fallback;
  return Math.floor(n);
}

function newest(rows, limit) {
  return rows.slice(-limit).reverse();
}

function send(res, status, body) {
  const headers = {
    'X-Mock': '1',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
  if (status === 204) {
    res.writeHead(204, headers);
    res.end();
    return;
  }
  headers['Content-Type'] = 'application/json; charset=utf-8';
  res.writeHead(status, headers);
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 1_000_000) {
        reject(Object.assign(new Error('body too large'), { status: 413, code: 'BAD_REQUEST' }));
        req.destroy();
      } else {
        chunks.push(c);
      }
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(Object.assign(new Error('invalid JSON'), { status: 400, code: 'BAD_REQUEST' }));
      }
    });
    req.on('error', reject);
  });
}

function seedDeadLetters() {
  const exec = resultExecution();
  addDeadLetter({
    eventId: 's42-truck-002-000001',
    kind: 'REJECTED',
    reason: 'missing field payload.speedKmh',
    partition: 1,
    offset: 1,
    eventTime: SIM_START,
    executionId: exec?.id ?? null,
  });
  addDeadLetter({
    eventId: 's42-truck-004-000008-late-1',
    kind: 'LATE',
    reason: `window [${SIM_START},${SIM_START + WINDOW_MS}) already closed (watermark ${SIM_START + WINDOW_MS})`,
    partition: 3,
    offset: 8,
    eventTime: SIM_START - 180000,
    executionId: exec?.id ?? null,
  });
  wsDead.length = 0;
}

function boot() {
  for (let n = 1; n <= 8; n++) trucks.push(createTruck(n, DEPOT));
  nextTruckNum = 9;
  storePipeline(DEMO);
  startExecution('logistics-demo', 'boot');
  seedDeadLetters();
}

async function handle(req, res) {
  if (req.method === 'OPTIONS') return send(res, 204);
  const url = new URL(req.url ?? '/', 'http://127.0.0.1');
  const path = url.pathname;
  const q = url.searchParams;
  const method = req.method ?? 'GET';

  if (method === 'GET' && path === '/api/health') return send(res, 200, { status: 'UP' });
  if (method === 'GET' && path === '/api/ready') {
    return send(res, 200, {
      status: 'UP',
      checks: { postgres: 'UP', kafka: 'UP', coordinator: 'UP', simulator: 'UP' },
    });
  }
  if (method === 'GET' && path === '/api/version') {
    return send(res, 200, { commit: 'mock', builtAt: '' });
  }
  if (method === 'GET' && path === '/api/pipelines/demo') return send(res, 200, structuredClone(DEMO));
  if (method === 'GET' && path === '/api/pipelines') return send(res, 200, listPipelines());
  if (method === 'GET' && path === '/api/workers') return send(res, 200, workers.map(workerView));
  if (method === 'GET' && path === '/api/simulator/state') return send(res, 200, simulatorState());
  if (method === 'GET' && path === '/api/results/summary') return send(res, 200, summary());
  if (method === 'GET' && path === '/api/executions/current') {
    const info = executionInfo(runningExecution());
    if (!info) return send(res, 204);
    return send(res, 200, info);
  }

  if (method === 'GET' && path === '/api/results/windows') {
    const exec = resultExecution();
    let rows = windows.filter((w) => w.executionId === (exec?.id ?? null));
    const sinkId = q.get('sinkId');
    if (sinkId) rows = rows.filter((w) => w.sinkId === sinkId);
    rows = rows.slice().sort((a, b) => b.windowEnd - a.windowEnd || (a.key < b.key ? -1 : 1));
    return send(res, 200, rows.slice(0, limitOf(q, 200)).map(publicWindow));
  }
  if (method === 'GET' && path === '/api/results/alerts') {
    const exec = resultExecution();
    const rows = alerts
      .filter((a) => a.executionId === (exec?.id ?? null))
      .slice()
      .sort((a, b) => b.eventTime - a.eventTime);
    return send(res, 200, rows.slice(0, limitOf(q, 50)).map(publicAlert));
  }
  if (method === 'GET' && path === '/api/results/dead-letters') {
    const exec = resultExecution();
    let rows = deadLetters.filter((d) => d.executionId === (exec?.id ?? null));
    const kind = q.get('kind');
    if (kind) rows = rows.filter((d) => d.kind === kind);
    rows = rows.slice().reverse();
    return send(res, 200, rows.slice(0, limitOf(q, 50)).map(publicDead));
  }

  const cpMatch = path.match(/^\/api\/executions\/([^/]+)\/checkpoints$/);
  if (method === 'GET' && cpMatch) {
    const id = decodeURIComponent(cpMatch[1]);
    const list = checkpoints.get(id);
    if (!list) return send(res, 404, { error: 'NOT_FOUND', message: `Unknown execution ${id}` });
    return send(res, 200, newest(list, limitOf(q, 20)));
  }
  const stopMatch = path.match(/^\/api\/executions\/([^/]+)\/stop$/);
  if (method === 'POST' && stopMatch) {
    const id = decodeURIComponent(stopMatch[1]);
    const exec = executions.get(id);
    if (!exec) return send(res, 404, { error: 'NOT_FOUND', message: `Unknown execution ${id}` });
    clearRecovery();
    if (exec.state === 'RUNNING' || exec.state === 'RECOVERING') {
      exec.state = 'STOPPED';
      emitExecution(exec, 'STOPPED');
    }
    return send(res, 200, executionInfo(exec));
  }
  const execMatch = path.match(/^\/api\/executions\/([^/]+)$/);
  if (method === 'GET' && execMatch) {
    const id = decodeURIComponent(execMatch[1]);
    const exec = executions.get(id);
    if (!exec) return send(res, 404, { error: 'NOT_FOUND', message: `Unknown execution ${id}` });
    return send(res, 200, executionInfo(exec));
  }
  const pipeMatch = path.match(/^\/api\/pipelines\/([^/]+)$/);
  if (method === 'GET' && pipeMatch) {
    const id = decodeURIComponent(pipeMatch[1]);
    const list = pipelineStore.get(id);
    if (!list) return send(res, 404, { error: 'NOT_FOUND', message: `Unknown pipeline ${id}` });
    return send(res, 200, structuredClone(list[list.length - 1].definition));
  }
  const startMatch = path.match(/^\/api\/pipelines\/([^/]+)\/executions$/);
  if (method === 'POST' && startMatch) {
    const id = decodeURIComponent(startMatch[1]);
    const body = await readBody(req);
    const data = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
    const started = startExecution(id, data.idempotencyKey);
    if (started.error) return send(res, started.error.status, started.error);
    return send(res, 200, started.body);
  }

  if (method === 'POST' && path === '/api/pipelines/validate') {
    const body = await readBody(req);
    return send(res, 200, validatePipeline(body));
  }
  if (method === 'POST' && path === '/api/pipelines') {
    const body = await readBody(req);
    const checked = validatePipeline(body);
    if (!checked.valid) {
      return send(res, 400, {
        error: 'INVALID_PIPELINE',
        message: 'Pipeline is invalid. Fix the errors and submit again.',
        errors: checked.errors,
      });
    }
    if (!body.id || typeof body.id !== 'string') {
      return send(res, 400, { error: 'BAD_REQUEST', message: 'Pipeline id is required' });
    }
    return send(res, 200, storePipeline(body));
  }
  if (method === 'POST' && path === '/api/chaos/late-events') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    return send(res, 200, injectLate(data.count, data.lateByMs));
  }
  if (method === 'POST' && path === '/api/chaos/duplicates') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    return send(res, 200, injectDuplicates(data.count));
  }
  if (method === 'POST' && path === '/api/chaos/malformed') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    return send(res, 200, injectMalformed(data.count));
  }
  if (method === 'POST' && path === '/api/chaos/slow-sink') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    const delay = Number(data.delayMs);
    sinkDelayMs = Number.isFinite(delay) && delay > 0 ? delay : 0;
    return send(res, 200, { delayMs: sinkDelayMs });
  }
  if (method === 'POST' && path === '/api/chaos/kill-worker') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    const killed = killWorker(data.workerId || null);
    if (killed.error) return send(res, killed.error.status, killed.error);
    return send(res, 200, killed.body);
  }
  if (method === 'POST' && path === '/api/simulator/trucks') {
    const body = await readBody(req);
    const data = body && typeof body === 'object' ? body : {};
    return send(res, 200, addTruck(data.x, data.y));
  }
  if (method === 'POST' && path === '/api/compare') {
    await readBody(req);
    const exec = resultExecution();
    const n = windows.filter((w) => w.executionId === (exec?.id ?? null)).length;
    return send(res, 200, {
      checkpointId: exec?.lastCompletedCheckpoint ?? 0,
      referenceResults: n,
      engineResults: n,
      matched: n,
      mismatches: [],
      recordsReplayed: partitionOffsets.reduce((a, b) => a + b, 0),
      durationMs: 15,
      match: true,
    });
  }

  return send(res, 404, { error: 'NOT_FOUND', message: `No route ${method} ${path}` });
}

function listPipelines() {
  const out = [];
  for (const [id, list] of pipelineStore) {
    const last = list[list.length - 1];
    out.push({ id, version: last.version, name: last.name, createdAt: last.createdAt });
  }
  return out;
}

if (!Number.isInteger(PORT) || PORT < 0 || PORT > 65535) {
  console.error('invalid PORT');
  process.exit(1);
}

boot();
setInterval(tick, 100);
setInterval(() => broadcast('trucks', { simTime: Math.round(simNow()), trucks: trucks.map(truckWs) }), 250);
setInterval(() => broadcast('flow', flowData()), 1000);
setInterval(() => broadcast('workers', workers.map(workerView)), 1000);
setInterval(() => {
  if (wsWindows.length) broadcast('windows', wsWindows.splice(0));
  if (wsAlerts.length) broadcast('alerts', wsAlerts.splice(0));
  if (wsDead.length) broadcast('deadLetters', wsDead.splice(0));
}, 1000);
setInterval(tickCheckpoint, 10000);

const server = http.createServer((req, res) => {
  handle(req, res).catch((err) => {
    if (res.headersSent) return;
    const status = err.status || 500;
    send(res, status, {
      error: err.code || (status === 400 ? 'BAD_REQUEST' : 'INTERNAL'),
      message: err.message || 'Internal error',
    });
  });
});

const wss = new WebSocketServer({ server, path: '/ws' });
wss.on('headers', (headers) => {
  headers.push('X-Mock: 1');
});
wss.on('connection', (ws) => {
  ws.send(JSON.stringify({ type: 'snapshot', ts: Date.now(), data: snapshot() }));
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
  ws.on('error', () => clients.delete(ws));
});

server.on('error', (err) => {
  console.error(err.message);
  process.exit(1);
});
server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock listening on ${server.address().port}`);
});
