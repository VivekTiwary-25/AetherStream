// Starts server.mjs on a free port and checks every contract 04 REST route plus /ws.
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const dir = path.dirname(fileURLToPath(import.meta.url));
const EXEC_KEYS = [
  'id',
  'pipelineId',
  'pipelineVersion',
  'state',
  'generation',
  'restoredFromCheckpoint',
  'lastCompletedCheckpoint',
  'createdAt',
];

function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close((err) => (err ? reject(err) : resolve(port)));
    });
    s.on('error', reject);
  });
}

function fail(msg) {
  const err = new Error(msg);
  err.smoke = true;
  throw err;
}

function need(obj, keys, label) {
  if (obj == null || typeof obj !== 'object') fail(`${label} is not an object`);
  for (const k of keys) {
    if (!Object.prototype.hasOwnProperty.call(obj, k)) fail(`${label} missing ${k}`);
  }
}

function noMockFields(value, label) {
  if (Array.isArray(value)) {
    value.forEach((v, i) => noMockFields(v, `${label}[${i}]`));
    return;
  }
  if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      if (k.toLowerCase().includes('mock')) fail(`${label} has field ${k}`);
      noMockFields(v, `${label}.${k}`);
    }
  }
}

const childRef = { proc: null };

async function req(port, method, p, body) {
  const res = await fetch(`http://127.0.0.1:${port}${p}`, {
    method,
    headers: body !== undefined ? { 'content-type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.headers.get('x-mock') !== '1') fail(`${method} ${p} missing X-Mock: 1`);
  const text = await res.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      fail(`${method} ${p} returned non-JSON`);
    }
  }
  noMockFields(json, `${method} ${p}`);
  return { status: res.status, json };
}

function expectStatus(got, want, label) {
  if (got !== want) fail(`${label} status ${got}, want ${want}`);
}

async function waitHealth(port, child, log) {
  const start = Date.now();
  while (Date.now() - start < 5000) {
    if (child.exitCode != null) fail(`server exited ${child.exitCode}\n${log.value}`);
    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/health`);
      if (res.status === 200) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 40));
  }
  fail(`server did not become healthy\n${log.value}`);
}

function checkWs(port) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    const types = [];
    const timer = setTimeout(() => {
      ws.close();
      reject(new Error(`ws missing snapshot/trucks/workers within 3s, got ${types.join(',')}`));
    }, 3000);
    ws.on('message', (buf) => {
      let msg;
      try {
        msg = JSON.parse(buf.toString());
      } catch {
        clearTimeout(timer);
        ws.close();
        reject(new Error('ws message was not JSON'));
        return;
      }
      types.push(msg.type);
      if (types[0] !== 'snapshot') {
        clearTimeout(timer);
        ws.close();
        reject(new Error(`first ws message was ${types[0]}, want snapshot`));
        return;
      }
      if (msg.type === 'snapshot') {
        try {
          need(msg, ['type', 'ts', 'data'], 'ws snapshot');
          need(msg.data, ['execution', 'workers', 'summary', 'simulator'], 'snapshot data');
          noMockFields(msg, 'ws snapshot');
        } catch (err) {
          clearTimeout(timer);
          ws.close();
          reject(err);
          return;
        }
      }
      if (types.includes('snapshot') && types.includes('trucks') && types.includes('workers')) {
        clearTimeout(timer);
        ws.close();
        resolve();
      }
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

const validPipeline = {
  id: 'smoke-pipe',
  version: 1,
  name: 'smoke',
  nodes: [
    { id: 'src', type: 'source', config: { topic: 'truck-events', startFrom: 'latest' } },
    { id: 'kb', type: 'keyBy', config: { field: 'key' } },
    {
      id: 'win',
      type: 'window',
      config: { kind: 'tumbling', sizeMs: 60000, aggregations: [{ name: 'count', fn: 'count' }] },
    },
    { id: 'sink', type: 'sink', config: { kind: 'postgres.windows' } },
  ],
  edges: [
    { from: 'src', to: 'kb' },
    { from: 'kb', to: 'win' },
    { from: 'win', to: 'sink' },
  ],
};

const noSource = {
  id: 'bad-source',
  version: 1,
  name: 'bad',
  nodes: [{ id: 'kb', type: 'keyBy', config: { field: 'key' } }],
  edges: [],
};

const windowWithoutKey = {
  id: 'bad-window',
  version: 1,
  name: 'bad window',
  nodes: [
    { id: 'src', type: 'source', config: { topic: 'truck-events' } },
    {
      id: 'win',
      type: 'window',
      config: { kind: 'tumbling', sizeMs: 60000, aggregations: [{ name: 'count', fn: 'count' }] },
    },
    { id: 'sink', type: 'sink', config: { kind: 'postgres.windows' } },
  ],
  edges: [
    { from: 'src', to: 'win' },
    { from: 'win', to: 'sink' },
  ],
};

async function main() {
  const port = await freePort();
  const log = { value: '' };
  const child = spawn(process.execPath, ['server.mjs'], {
    cwd: dir,
    env: { ...process.env, PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  childRef.proc = child;
  child.stdout.on('data', (d) => {
    log.value += d;
  });
  child.stderr.on('data', (d) => {
    log.value += d;
  });
  await waitHealth(port, child, log);
  await checkWs(port);

  const health = await req(port, 'GET', '/api/health');
  expectStatus(health.status, 200, 'health');
  if (health.json.status !== 'UP') fail('health status');

  const ready = await req(port, 'GET', '/api/ready');
  expectStatus(ready.status, 200, 'ready');
  need(ready.json, ['status', 'checks'], 'ready');
  need(ready.json.checks, ['postgres', 'kafka', 'coordinator', 'simulator'], 'ready.checks');

  const version = await req(port, 'GET', '/api/version');
  expectStatus(version.status, 200, 'version');
  if (version.json.commit !== 'mock' || version.json.builtAt !== '') fail('version body');

  const validated = await req(port, 'POST', '/api/pipelines/validate', validPipeline);
  expectStatus(validated.status, 200, 'validate');
  need(validated.json, ['valid', 'errors', 'plan'], 'validate');
  if (validated.json.valid !== true) fail('expected valid pipeline');
  need(validated.json.plan, ['pipelineId', 'pipelineVersion', 'topic', 'partitions', 'startFrom', 'operators'], 'plan');
  if (!Array.isArray(validated.json.plan.operators) || validated.json.plan.operators.length !== 4) {
    fail('plan operators');
  }
  need(validated.json.plan.operators[0], ['id', 'type', 'config', 'inputs', 'outputType'], 'operator');

  const badWindow = await req(port, 'POST', '/api/pipelines/validate', windowWithoutKey);
  expectStatus(badWindow.status, 200, 'validate window');
  if (badWindow.json.valid !== false || badWindow.json.plan !== null) fail('invalid window should not plan');
  const mismatch = badWindow.json.errors.find((e) => e.code === 'TYPE_MISMATCH');
  if (!mismatch || mismatch.nodeId !== 'win') fail('window without keyBy must be TYPE_MISMATCH');
  need(mismatch, ['nodeId', 'code', 'message'], 'validate error');

  const badSource = await req(port, 'POST', '/api/pipelines/validate', noSource);
  if (!badSource.json.errors.some((e) => e.code === 'NO_SOURCE' && e.nodeId === null)) fail('NO_SOURCE');
  if (!badSource.json.errors.some((e) => e.code === 'NO_SINK')) fail('NO_SINK');

  const rejected = await req(port, 'POST', '/api/pipelines', noSource);
  expectStatus(rejected.status, 400, 'invalid pipeline');
  need(rejected.json, ['error', 'message', 'errors'], 'invalid pipeline');
  if (rejected.json.error !== 'INVALID_PIPELINE') fail('INVALID_PIPELINE');

  const demo = await req(port, 'GET', '/api/pipelines/demo');
  expectStatus(demo.status, 200, 'demo');
  need(demo.json, ['id', 'version', 'name', 'nodes', 'edges'], 'demo');
  if (demo.json.id !== 'logistics-demo' || !Array.isArray(demo.json.nodes) || !Array.isArray(demo.json.edges)) {
    fail('demo pipeline shape');
  }

  const listed = await req(port, 'GET', '/api/pipelines');
  expectStatus(listed.status, 200, 'pipelines');
  if (!Array.isArray(listed.json) || listed.json.length < 1) fail('pipeline list');
  need(listed.json[0], ['id', 'version', 'name', 'createdAt'], 'pipeline summary');

  const got = await req(port, 'GET', '/api/pipelines/logistics-demo');
  expectStatus(got.status, 200, 'get pipeline');
  need(got.json, ['id', 'nodes', 'edges'], 'pipeline');

  const missingPipe = await req(port, 'GET', '/api/pipelines/does-not-exist');
  expectStatus(missingPipe.status, 404, 'missing pipeline');
  need(missingPipe.json, ['error', 'message'], '404');

  const created = await req(port, 'POST', '/api/pipelines', validPipeline);
  expectStatus(created.status, 200, 'create pipeline');
  need(created.json, ['id', 'version', 'name', 'createdAt'], 'created pipeline');

  const current = await req(port, 'GET', '/api/executions/current');
  expectStatus(current.status, 200, 'current');
  need(current.json, EXEC_KEYS, 'current');
  if (current.json.state !== 'RUNNING') fail('boot execution should be RUNNING');

  const started = await req(port, 'POST', '/api/pipelines/logistics-demo/executions', {
    idempotencyKey: 'smoke-key-1',
  });
  expectStatus(started.status, 200, 'start execution');
  need(started.json, EXEC_KEYS, 'started');
  const again = await req(port, 'POST', '/api/pipelines/logistics-demo/executions', {
    idempotencyKey: 'smoke-key-1',
  });
  if (again.json.id !== started.json.id) fail('idempotencyKey must return the same execution');

  const byId = await req(port, 'GET', `/api/executions/${started.json.id}`);
  expectStatus(byId.status, 200, 'get execution');
  need(byId.json, EXEC_KEYS, 'execution');

  const missingExec = await req(port, 'GET', '/api/executions/missing');
  expectStatus(missingExec.status, 404, 'missing execution');

  const cps = await req(port, 'GET', `/api/executions/${started.json.id}/checkpoints?limit=20`);
  expectStatus(cps.status, 200, 'checkpoints');
  if (!Array.isArray(cps.json) || cps.json.length < 1) fail('expected checkpoint 0');
  need(cps.json[0], ['checkpointId', 'state', 'generation', 'durationMs', 'completedAt', 'sourceOffsets'], 'checkpoint');

  const workerRes = await req(port, 'GET', '/api/workers');
  expectStatus(workerRes.status, 200, 'workers');
  if (!Array.isArray(workerRes.json) || workerRes.json.length !== 3) fail('expected 3 workers');
  for (const w of workerRes.json) {
    need(w, ['workerId', 'alive', 'state', 'partitions', 'sourceQueue', 'sinkQueue', 'backpressured', 'recordsPerSec', 'lastHeartbeatMs'], 'worker');
    need(w.sourceQueue, ['size', 'capacity'], 'sourceQueue');
    need(w.sinkQueue, ['size', 'capacity'], 'sinkQueue');
    if (w.sourceQueue.capacity !== 1000 || w.sinkQueue.capacity !== 1000) fail('queue capacity');
  }

  const wins = await req(port, 'GET', '/api/results/windows?sinkId=speedSink&limit=200');
  expectStatus(wins.status, 200, 'windows');
  if (!Array.isArray(wins.json)) fail('windows array');
  for (const row of wins.json) {
    need(row, ['sinkId', 'key', 'windowStart', 'windowEnd', 'values', 'eventCount'], 'window');
  }

  const alertRes = await req(port, 'GET', '/api/results/alerts?limit=50');
  expectStatus(alertRes.status, 200, 'alerts');
  if (!Array.isArray(alertRes.json)) fail('alerts array');
  for (const row of alertRes.json) need(row, ['alertId', 'key', 'ruleId', 'message', 'eventTime'], 'alert');

  const deadLate = await req(port, 'GET', '/api/results/dead-letters?kind=LATE&limit=50');
  const deadRej = await req(port, 'GET', '/api/results/dead-letters?kind=REJECTED&limit=50');
  expectStatus(deadLate.status, 200, 'late letters');
  expectStatus(deadRej.status, 200, 'rejected letters');
  if (!Array.isArray(deadLate.json) || !Array.isArray(deadRej.json)) fail('dead letter arrays');

  const summary = await req(port, 'GET', '/api/results/summary');
  expectStatus(summary.status, 200, 'summary');
  need(summary.json, ['executionId', 'windowsWritten', 'alerts', 'rejected', 'late', 'deliveriesLastMinute', 'avgSpeedLastMinute', 'eventsPerSec'], 'summary');

  const sim = await req(port, 'GET', '/api/simulator/state');
  expectStatus(sim.status, 200, 'simulator');
  need(sim.json, ['running', 'timeScale', 'simTime', 'trucks', 'stops', 'depot', 'emitted'], 'simulator');
  need(sim.json.depot, ['x', 'y'], 'depot');
  if (sim.json.depot.x !== 1000 || sim.json.depot.y !== 1000) fail('depot');
  if (sim.json.timeScale !== 10) fail('timeScale');
  if (sim.json.trucks.length !== 8) fail(`expected 8 trucks, got ${sim.json.trucks.length}`);
  if (sim.json.stops.length !== 24) fail(`expected 24 stops, got ${sim.json.stops.length}`);
  need(sim.json.trucks[0], ['truckId', 'x', 'y', 'speedKmh', 'heading', 'status', 'routeId'], 'truck');
  need(sim.json.stops[0], ['stopId', 'x', 'y'], 'stop');
  const stuck = sim.json.trucks.find((t) => t.truckId === 'truck-007');
  if (!stuck || stuck.status !== 'STOPPED' || stuck.speedKmh !== 0) fail('truck-007 should be stopped');

  const added = await req(port, 'POST', '/api/simulator/trucks', { x: 500, y: 700 });
  expectStatus(added.status, 200, 'add truck');
  need(added.json, ['truckId'], 'add truck');
  if (!/^truck-\d{3}$/.test(added.json.truckId)) fail('truckId shape');
  const sim2 = await req(port, 'GET', '/api/simulator/state');
  if (sim2.json.trucks.length !== 9) fail('truck was not added');
  const newbie = sim2.json.trucks.find((t) => t.truckId === added.json.truckId);
  if (!newbie) fail('new truck missing from state');
  if (newbie.x % 200 !== 0 && newbie.y % 200 !== 0) fail('new truck is not on a street');

  const dup = await req(port, 'POST', '/api/chaos/duplicates', { count: 4 });
  expectStatus(dup.status, 200, 'duplicates');
  if (dup.json.sent !== 4) fail('duplicates sent');

  const lateBefore = (await req(port, 'GET', '/api/results/dead-letters?kind=LATE&limit=50')).json.length;
  const late = await req(port, 'POST', '/api/chaos/late-events', { count: 3, lateByMs: 180000 });
  expectStatus(late.status, 200, 'late-events');
  if (late.json.sent !== 3) fail('late sent');
  const lateAfter = await req(port, 'GET', '/api/results/dead-letters?kind=LATE&limit=50');
  if (lateAfter.json.length < lateBefore + 3) fail('late-events did not add LATE dead letters');
  need(lateAfter.json[0], ['eventId', 'kind', 'reason', 'partition', 'offset', 'eventTime'], 'dead letter');
  if (lateAfter.json.some((d) => d.kind !== 'LATE')) fail('LATE filter');

  const rejBefore = (await req(port, 'GET', '/api/results/dead-letters?kind=REJECTED&limit=50')).json.length;
  const mal = await req(port, 'POST', '/api/chaos/malformed', { count: 2 });
  expectStatus(mal.status, 200, 'malformed');
  if (mal.json.sent !== 2) fail('malformed sent');
  const rejAfter = await req(port, 'GET', '/api/results/dead-letters?kind=REJECTED&limit=50');
  if (rejAfter.json.length < rejBefore + 2) fail('malformed did not add REJECTED dead letters');

  const slow = await req(port, 'POST', '/api/chaos/slow-sink', { delayMs: 50 });
  expectStatus(slow.status, 200, 'slow-sink');
  if (slow.json.delayMs !== 50) fail('delayMs');
  const pressured = await req(port, 'GET', '/api/workers');
  if (!pressured.json.every((w) => w.alive && w.backpressured && w.sourceQueue.size === w.sourceQueue.capacity && w.sinkQueue.size === w.sinkQueue.capacity)) {
    fail('slow-sink should fill queues and set backpressured');
  }
  const cleared = await req(port, 'POST', '/api/chaos/slow-sink', { delayMs: 0 });
  if (cleared.json.delayMs !== 0) fail('delay 0');
  const drained = await req(port, 'GET', '/api/workers');
  if (drained.json.some((w) => w.backpressured)) fail('delay 0 should clear backpressure');

  const beforeKill = await req(port, 'GET', '/api/executions/current');
  const killed = await req(port, 'POST', '/api/chaos/kill-worker', { workerId: null });
  expectStatus(killed.status, 200, 'kill-worker');
  need(killed.json, ['workerId'], 'kill-worker');
  const recovering = await req(port, 'GET', '/api/executions/current');
  if (recovering.json.state !== 'RECOVERING') fail('kill-worker should set RECOVERING');
  if (recovering.json.restoredFromCheckpoint !== beforeKill.json.lastCompletedCheckpoint) {
    fail('restoredFromCheckpoint must be the last checkpoint');
  }
  if (recovering.json.generation !== beforeKill.json.generation + 1) fail('generation should increase on recovery');
  const deadWorkers = await req(port, 'GET', '/api/workers');
  const dead = deadWorkers.json.find((w) => w.workerId === killed.json.workerId);
  if (!dead || dead.alive !== false) fail('killed worker should be marked dead');

  const recovered = await waitUntil(async () => {
    const cur = await req(port, 'GET', '/api/executions/current');
    return cur.json && cur.json.state === 'RUNNING' && cur.json.generation === beforeKill.json.generation + 1;
  }, 6000);
  if (!recovered) fail('execution did not return to RUNNING within ~4s');
  const revived = await req(port, 'GET', '/api/workers');
  const back = revived.json.find((w) => w.workerId === killed.json.workerId);
  if (!back || back.alive !== true) fail('worker should be alive after recovery');

  const compared = await req(port, 'POST', '/api/compare', {});
  expectStatus(compared.status, 200, 'compare');
  need(compared.json, ['checkpointId', 'referenceResults', 'engineResults', 'matched', 'mismatches', 'recordsReplayed', 'durationMs', 'match'], 'compare');
  if (compared.json.match !== true || compared.json.mismatches.length !== 0) fail('compare should match');
  if (compared.json.referenceResults !== compared.json.engineResults || compared.json.matched !== compared.json.engineResults) {
    fail('compare counts');
  }

  const unknownStart = await req(port, 'POST', '/api/pipelines/missing/executions', { idempotencyKey: 'x' });
  expectStatus(unknownStart.status, 404, 'start missing pipeline');

  const stopped = await req(port, 'POST', `/api/executions/${started.json.id}/stop`);
  expectStatus(stopped.status, 200, 'stop');
  need(stopped.json, EXEC_KEYS, 'stopped');
  if (stopped.json.state !== 'STOPPED') fail('stop should set STOPPED');
  const afterStop = await req(port, 'GET', '/api/executions/current');
  expectStatus(afterStop.status, 204, 'current after stop');
  if (afterStop.json != null) fail('204 must have an empty body');

  console.log('mock smoke ok');
}

function waitUntil(fn, ms) {
  const start = Date.now();
  return (async () => {
    while (Date.now() - start < ms) {
      if (await fn()) return true;
      await new Promise((r) => setTimeout(r, 100));
    }
    return false;
  })();
}

main()
  .then(() => {
    childRef.proc?.kill();
    process.exit(0);
  })
  .catch((err) => {
    console.error('FAIL:', err.message);
    childRef.proc?.kill();
    process.exit(1);
  });
