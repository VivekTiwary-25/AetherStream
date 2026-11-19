// Plain-TS model of everything the city shows. The socket feeds it; the Pixi scene reads it every frame.
import { SampleClock, TruckTrack } from './motion'
import type { CityEnvelope, CityExecution, CityPoint, CitySnapshot, CityStop, CityTruck, CityWorker, ExecutionMessage, FlowData, TruckUpdate } from './cityTypes'

export interface TruckEntry {
  truck: CityTruck
  track: TruckTrack
  bornAt: number
  removedAt: number | null
}
export interface Delivery { stopId: string; x: number; y: number; at: number }

export type EngineMode = 'disconnected' | 'idle' | 'running' | 'backpressured' | 'checkpointing' | 'recovering' | 'stopped' | 'failed'
export interface EngineStatus { mode: EngineMode; label: string; color: string }

const STOP_RADIUS = 60
const FADE_OUT_MS = 600
const CHECKPOINT_FLASH_MS = 1600

export class CityModel {
  readonly trucks = new Map<string, TruckEntry>()
  stops: CityStop[] = []
  depot: CityPoint = { x: 1000, y: 1000 }
  workers: CityWorker[] = []
  execution: CityExecution | null = null
  flow: FlowData | null = null
  simTime = 0
  deliveries: Delivery[] = []
  lastCheckpoint: { id: number; at: number } | null = null
  /** False while the live WebSocket is down: the stream stops instead of replaying a stale rate. */
  connected = true
  private readonly clock = new SampleClock()
  private expected = new Map<string, CityPoint & { at: number }>()

  apply(message: CityEnvelope<unknown>, now: number): void {
    if (message.type === 'snapshot') {
      const snap = message.data as CitySnapshot
      if (snap.simulator) {
        this.stops = snap.simulator.stops ?? []
        this.depot = snap.simulator.depot ?? this.depot
        this.simTime = snap.simulator.simTime ?? 0
        this.ingest(snap.simulator.trucks ?? [], now, this.clock.stamp(now))
      }
      this.workers = snap.workers ?? []
      this.execution = snap.execution ?? null
    } else if (message.type === 'trucks') {
      const update = message.data as TruckUpdate
      this.simTime = update.simTime
      this.ingest(update.trucks ?? [], now, this.clock.stamp(now))
    } else if (message.type === 'workers') this.workers = (message.data as CityWorker[]) ?? []
    else if (message.type === 'flow') this.flow = message.data as FlowData
    else if (message.type === 'execution') {
      const exec = message.data as ExecutionMessage
      if (exec.event === 'CHECKPOINT_COMPLETED') this.lastCheckpoint = { id: exec.checkpointId ?? exec.lastCompletedCheckpoint, at: now }
      this.execution = exec
    }
  }

  /** A truck was just added at a drop point; let it glide from there to the street the simulator chose. */
  expectTruck(truckId: string, at: CityPoint, now: number): void {
    this.expected.set(truckId, { ...at, at: now })
    const entry = this.trucks.get(truckId)
    if (entry && entry.track.size <= 1) entry.track = trackFrom(at, now - 250, entry.truck, now)
  }

  /** `sampleTime` is the de-jittered time of this frame (SampleClock); `now` is wall time for fades. */
  ingest(trucks: CityTruck[], now: number, sampleTime = now): void {
    const seen = new Set<string>()
    for (const truck of trucks) {
      seen.add(truck.truckId)
      let entry = this.trucks.get(truck.truckId)
      if (!entry || entry.removedAt !== null) {
        const drop = this.expected.get(truck.truckId)
        const track = drop && now - drop.at < 5000 ? trackFrom(drop, sampleTime - 250, truck, sampleTime) : new TruckTrack()
        entry = { truck, track, bornAt: now, removedAt: null }
        this.trucks.set(truck.truckId, entry)
        this.expected.delete(truck.truckId)
      }
      if (entry.truck.status === 'UNLOADING' && truck.status !== 'UNLOADING') this.recordDelivery(truck, now)
      entry.truck = truck
      entry.track.push(sampleTime, truck.x, truck.y)
    }
    for (const [id, entry] of this.trucks) {
      if (!seen.has(id) && entry.removedAt === null) entry.removedAt = now
      if (entry.removedAt !== null && now - entry.removedAt > FADE_OUT_MS) this.trucks.delete(id)
    }
    this.deliveries = this.deliveries.filter((d) => now - d.at < 2000)
  }

  private recordDelivery(truck: CityTruck, now: number): void {
    const stop = nearestStop(this.stops, truck, STOP_RADIUS)
    if (stop) this.deliveries.push({ stopId: stop.stopId, x: stop.x, y: stop.y, at: now })
  }

  /** Live trucks (not fading out). */
  activeTrucks(): number {
    let n = 0
    for (const entry of this.trucks.values()) if (entry.removedAt === null) n += 1
    return n
  }

  status(now: number): EngineStatus {
    if (!this.connected) return { mode: 'disconnected', label: 'Live link lost · reconnecting', color: '#ffbd69' }
    return engineStatus(this.execution, this.workers, this.lastCheckpoint && now - this.lastCheckpoint.at < CHECKPOINT_FLASH_MS ? this.lastCheckpoint.id : null)
  }
}

function trackFrom(start: CityPoint, startTime: number, truck: CityTruck, now: number): TruckTrack {
  // No corner snapping: the glide from a drop point is a straight line onto the nearest street.
  const track = new TruckTrack(8, Number.POSITIVE_INFINITY, 0)
  track.push(startTime - 250, start.x, start.y)
  track.push(startTime, start.x, start.y)
  if (now > startTime) track.push(now, truck.x, truck.y)
  return track
}

export function nearestStop(stops: CityStop[], point: CityPoint, maxDistance: number): CityStop | undefined {
  let best: CityStop | undefined, bestDistance = maxDistance
  for (const stop of stops) {
    const d = Math.hypot(stop.x - point.x, stop.y - point.y)
    if (d <= bestDistance) { best = stop; bestDistance = d }
  }
  return best
}

/** The UI states from contract 04, each with text and colour. */
export function engineStatus(execution: CityExecution | null, workers: CityWorker[], recentCheckpoint: number | null): EngineStatus {
  if (!execution) return { mode: 'idle', label: 'Awaiting execution', color: '#8aa9bb' }
  if (execution.state === 'FAILED') return { mode: 'failed', label: 'Failed', color: '#ff6b7d' }
  if (execution.state === 'RECOVERING') return { mode: 'recovering', label: `Recovering from checkpoint #${execution.restoredFromCheckpoint ?? execution.lastCompletedCheckpoint}`, color: '#b896ff' }
  if (execution.state === 'STOPPED') return { mode: 'stopped', label: 'Stopped', color: '#9aabbf' }
  if (execution.state === 'CREATED') return { mode: 'stopped', label: 'Paused · starting', color: '#9aabbf' }
  if (workers.some((w) => w.alive && w.backpressured)) return { mode: 'backpressured', label: 'Backpressured', color: '#ffbd69' }
  if (recentCheckpoint !== null) return { mode: 'checkpointing', label: `Checkpoint #${recentCheckpoint} saved`, color: '#5fd8ff' }
  return { mode: 'running', label: 'Running', color: '#6ee7a8' }
}

/** Partitions owned by a dead worker: their lane in the engine is stalled until recovery reassigns them. */
export function stalledLanes(workers: CityWorker[], out: boolean[]): boolean[] {
  out.fill(false)
  for (const worker of workers) {
    if (worker.alive) continue
    for (const p of worker.partitions) if (p >= 0 && p < out.length) out[p] = true
  }
  // A live, running worker that already owns the partition wins (recovery reassigned it).
  for (const worker of workers) {
    if (!worker.alive || worker.state !== 'RUNNING') continue
    for (const p of worker.partitions) if (p >= 0 && p < out.length) out[p] = false
  }
  return out
}

export function workerColor(worker: CityWorker): number {
  if (!worker.alive) return 0xff5e6c
  if (worker.backpressured || worker.state === 'RESTORING') return 0xffbd5c
  return 0x5ee6a0
}
