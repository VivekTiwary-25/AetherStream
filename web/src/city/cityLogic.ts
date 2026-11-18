// Pure logic for the event-flow visual: Kafka partition lanes, particle spawning and a fixed-size particle pool.
import { quadPoint, type Vec } from './motion'

/** Kafka's murmur2 (org.apache.kafka.common.utils.Utils.murmur2), so lanes match the real topic partitions. */
export function murmur2(bytes: Uint8Array): number {
  const m = 0x5bd1e995, r = 24
  const length = bytes.length
  let h = 0x9747b28c ^ length
  const whole = length >> 2
  for (let i = 0; i < whole; i += 1) {
    const i4 = i * 4
    let k = bytes[i4] | (bytes[i4 + 1] << 8) | (bytes[i4 + 2] << 16) | (bytes[i4 + 3] << 24)
    k = Math.imul(k, m)
    k ^= k >>> r
    k = Math.imul(k, m)
    h = Math.imul(h, m)
    h ^= k
  }
  const tail = length & ~3, rest = length % 4
  if (rest >= 3) h ^= bytes[tail + 2] << 16
  if (rest >= 2) h ^= bytes[tail + 1] << 8
  if (rest >= 1) { h ^= bytes[tail]; h = Math.imul(h, m) }
  h ^= h >>> 13
  h = Math.imul(h, m)
  h ^= h >>> 15
  return h | 0
}

const encoder = new TextEncoder()
/** Partition Kafka's default partitioner picks for key = truckId (topic has 6 partitions). */
export function partitionForTruck(truckId: string, partitions = 6): number {
  return (murmur2(encoder.encode(truckId)) & 0x7fffffff) % partitions
}

/** How many particles per second to show for a real event rate. Sub-linear so busy streams stay readable. */
export function visualRate(eventsPerSec: number, reducedMotion: boolean): number {
  if (!(eventsPerSec > 0)) return 0
  const rate = 3 + Math.sqrt(eventsPerSec) * 4
  return Math.min(reducedMotion ? 8 : 60, rate)
}

/** Rejected / late events are rare; make any non-zero rate visible but bounded. */
export function anomalyRate(perSec: number, reducedMotion: boolean): number {
  if (!(perSec > 0)) return 0
  return Math.min(reducedMotion ? 1 : 8, 0.8 + perSec * 3)
}

/** Turns a continuous rate into whole spawns per frame without losing the remainder. */
export class RateAccumulator {
  private carry = 0
  take(ratePerSec: number, dtSeconds: number): number {
    this.carry += Math.max(0, ratePerSec) * dtSeconds
    const whole = Math.floor(this.carry)
    this.carry -= whole
    return whole
  }
}

/** Control point for the arc from a truck to an engine lane inlet: lifts the curve above both ends. */
export function flowControl(from: Vec, to: Vec): Vec {
  const dx = to.x - from.x
  return { x: from.x + dx * 0.6, y: Math.min(from.y, to.y) - 90 - Math.abs(dx) * 0.12 }
}

export type ParticleKind = 'event' | 'rejected' | 'late' | 'spark'

export interface Particle {
  kind: ParticleKind
  lane: number
  age: number
  life: number
  from: Vec
  control: Vec
  to: Vec
  x: number
  y: number
  vx: number
  vy: number
}

const blank = (): Particle => ({ kind: 'event', lane: 0, age: 0, life: 1, from: { x: 0, y: 0 }, control: { x: 0, y: 0 }, to: { x: 0, y: 0 }, x: 0, y: 0, vx: 0, vy: 0 })

/**
 * Fixed-capacity pool. Particle objects are allocated once and reused (no garbage per frame).
 * Live particles are items[0 .. count). When full, new emits are dropped (the cap).
 */
export class ParticlePool {
  readonly items: Particle[]
  count = 0

  constructor(readonly capacity = 240) {
    this.items = Array.from({ length: capacity }, blank)
  }

  emitCurve(kind: ParticleKind, lane: number, from: Vec, to: Vec, life: number): Particle | undefined {
    if (this.count >= this.capacity) return undefined
    const p = this.items[this.count++]
    const control = flowControl(from, to)
    p.kind = kind; p.lane = lane; p.age = 0; p.life = life
    p.from.x = from.x; p.from.y = from.y; p.to.x = to.x; p.to.y = to.y; p.control.x = control.x; p.control.y = control.y
    p.x = from.x; p.y = from.y; p.vx = 0; p.vy = 0
    return p
  }

  emitSpark(x: number, y: number, vx: number, vy: number, life: number): Particle | undefined {
    if (this.count >= this.capacity) return undefined
    const p = this.items[this.count++]
    p.kind = 'spark'; p.lane = 0; p.age = 0; p.life = life
    p.x = x; p.y = y; p.vx = vx; p.vy = vy
    return p
  }

  /** Moves everything; calls onArrive(kind, lane, x, y) for curve particles that reach their target. */
  advance(dtSeconds: number, onArrive?: (kind: ParticleKind, lane: number, x: number, y: number) => void): void {
    for (let i = 0; i < this.count; i += 1) {
      const p = this.items[i]
      p.age += dtSeconds
      if (p.age >= p.life) {
        const { kind, lane } = p, x = p.to.x, y = p.to.y
        // Swap-remove: move the last live particle into this slot and keep the dead object for reuse.
        this.items[i] = this.items[this.count - 1]
        this.items[this.count - 1] = p
        this.count -= 1
        i -= 1
        if (kind !== 'spark') onArrive?.(kind, lane, x, y)
        continue
      }
      if (p.kind === 'spark') {
        p.x += p.vx * dtSeconds; p.y += p.vy * dtSeconds
        const drag = Math.exp(-3.5 * dtSeconds)
        p.vx *= drag; p.vy *= drag
      } else {
        quadPoint(p.from, p.control, p.to, this.progress(p), p)
      }
    }
  }

  /** Curve parameter for a particle; accelerates slightly as it nears the engine. */
  progress(p: Particle, ageOffset = 0): number {
    const t = Math.max(0, Math.min(1, (p.age - ageOffset) / p.life))
    return t * (0.75 + 0.25 * t)
  }
}
