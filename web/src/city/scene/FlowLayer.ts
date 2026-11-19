// Event stream particles: glowing comets from trucks to the engine lanes, rendered with pooled additive sprites.
// The spawn rate per lane follows the real per-partition event rate; the look follows the engine state.
import { Container, Sprite } from 'pixi.js'
import { anomalyRate, laneVisualRates, ParticlePool, RateAccumulator, STALLED_COLOR, type Particle, type ParticleKind, type StreamStyle } from '../cityLogic'
import type { FlowData } from '../cityTypes'
import { quadPoint, type Vec } from '../motion'
import { laneInlet } from './EngineCore'
import { glowScale, glowTexture } from './textures'

const COLORS: Record<ParticleKind, number> = { event: 0x5cf1f4, rejected: 0xff4d64, late: 0xb07cff, spark: 0xff6a7c }
const SIZES: Record<ParticleKind, number> = { event: 9, rejected: 7, late: 9, spark: 4 }
const TRAIL_SPACING = 0.026 // seconds between trail ghosts
export const PARTITIONS = 6
const INLETS: Vec[] = Array.from({ length: PARTITIONS }, (_, lane) => laneInlet(lane))

export interface FlowSource { truckId: string; lane: number; x: number; y: number }

export interface FlowFrame {
  flow: FlowData | null
  /** Live trucks grouped by their Kafka partition (index = lane). Arrays are reused by the caller. */
  lanes: FlowSource[][]
  /** All live trucks. */
  all: FlowSource[]
  style: StreamStyle
  /** Lanes whose worker is dead (index = lane). */
  stalled: boolean[]
  /** Keeps particles visible when zoomed out (from the camera). */
  sizeScale: number
  /** Overall opacity (fades packets when zoomed in to street level). */
  fade: number
}

export class FlowLayer {
  readonly root = new Container({ label: 'flow' })
  private readonly pool: ParticlePool
  private readonly sprites: Sprite[] = []
  private readonly trail: number
  private readonly laneRates = new Array<number>(PARTITIONS).fill(0)
  private readonly laneSpawns = Array.from({ length: PARTITIONS }, () => new RateAccumulator())
  private readonly laneCursors = new Array<number>(PARTITIONS).fill(0)
  private readonly rejected = new RateAccumulator()
  private readonly late = new RateAccumulator()
  private cursor = 0
  private readonly scratch: Vec = { x: 0, y: 0 }
  private stalled: boolean[] = []

  constructor(private readonly reducedMotion: boolean, private readonly onArrive: (lane: number, kind: ParticleKind) => void) {
    this.pool = new ParticlePool(reducedMotion ? 40 : 220)
    this.trail = reducedMotion ? 1 : 5
    const texture = glowTexture()
    for (let i = 0; i < this.pool.capacity * this.trail; i += 1) {
      const sprite = new Sprite({ texture, anchor: 0.5, blendMode: 'add' })
      sprite.visible = false
      this.sprites.push(sprite)
      this.root.addChild(sprite)
    }
  }

  update(dt: number, frame: FlowFrame): void {
    const { flow, style } = frame
    this.stalled = frame.stalled
    if (style.active && flow && frame.all.length > 0) {
      laneVisualRates(flow, this.reducedMotion, this.laneRates)
      for (let lane = 0; lane < PARTITIONS; lane += 1) {
        const count = this.laneSpawns[lane].take(this.laneRates[lane], dt)
        // Prefer a truck that really hashes to this lane; fall back to any truck so the rate stays honest.
        const pool = frame.lanes[lane]?.length ? frame.lanes[lane] : frame.all
        for (let i = 0; i < count; i += 1) {
          const source = pool[this.laneCursors[lane]++ % pool.length]
          this.pool.emitCurve('event', lane, source, INLETS[lane], 1.5 * style.travel * (0.9 + Math.random() * 0.2))
        }
      }
      this.spawnAnomaly('rejected', this.rejected.take(anomalyRate(flow.rejectedPerSec, this.reducedMotion), dt), frame.all, 1.3)
      this.spawnAnomaly('late', this.late.take(anomalyRate(flow.latePerSec, this.reducedMotion), dt), frame.all, 2.1 * style.travel)
    }
    this.pool.advance(dt, (kind, lane, x, y) => {
      // Events into a dead worker's lane pile up at the inlet: no lane flash, no sparks.
      if (this.stalled[lane]) return
      if (kind === 'rejected') {
        // Rejected events bounce off the inlet as a few small red sparks.
        for (let i = 0; i < (this.reducedMotion ? 2 : 6); i += 1) {
          const angle = Math.PI * (0.55 + Math.random() * 0.9), speed = 70 + Math.random() * 110
          this.pool.emitSpark(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed, 0.45 + Math.random() * 0.3)
        }
      }
      this.onArrive(lane, kind)
    })
    this.draw(frame)
  }

  private spawnAnomaly(kind: ParticleKind, count: number, sources: FlowSource[], life: number): void {
    for (let i = 0; i < count; i += 1) {
      const source = sources[this.cursor++ % sources.length]
      this.pool.emitCurve(kind, source.lane, source, INLETS[source.lane], life * (0.9 + Math.random() * 0.2))
    }
  }

  private draw(frame: FlowFrame): void {
    let s = 0
    const { style, sizeScale, fade } = frame
    for (let i = 0; i < this.pool.count; i += 1) {
      const p = this.pool.items[i]
      const fadeIn = Math.min(1, p.age / 0.15), fadeOut = Math.min(1, (p.life - p.age) / 0.2)
      const stalled = p.kind !== 'spark' && this.stalled[p.lane]
      const color = stalled ? STALLED_COLOR : p.kind === 'event' ? style.color : COLORS[p.kind]
      const alpha = Math.min(fadeIn, fadeOut) * fade * (stalled ? 0.5 : p.kind === 'event' ? style.alpha : 1)
      for (let k = 0; k < this.trail; k += 1) {
        const sprite = this.sprites[s++]
        const pos = k === 0 ? p : this.trailPoint(p, k)
        sprite.visible = true
        sprite.position.set(pos.x, pos.y)
        sprite.tint = color
        sprite.alpha = alpha * (1 - k / (this.trail + 0.5)) * (p.kind === 'spark' ? 1 - p.age / p.life : 0.95)
        sprite.scale.set(glowScale(SIZES[p.kind] * sizeScale * (1 - k * 0.16)))
      }
    }
    for (; s < this.sprites.length && this.sprites[s].visible; s += 1) this.sprites[s].visible = false
  }

  private trailPoint(p: Particle, k: number): Vec {
    if (p.kind === 'spark') {
      this.scratch.x = p.x - p.vx * k * 0.012; this.scratch.y = p.y - p.vy * k * 0.012
      return this.scratch
    }
    return quadPoint(p.from, p.control, p.to, this.pool.progress(p, k * TRAIL_SPACING), this.scratch)
  }
}
