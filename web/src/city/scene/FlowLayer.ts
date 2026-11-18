// Event stream particles: glowing comets from trucks to the engine lanes, rendered with pooled additive sprites.
import { Container, Sprite } from 'pixi.js'
import { anomalyRate, ParticlePool, partitionForTruck, RateAccumulator, visualRate, type Particle, type ParticleKind } from '../cityLogic'
import type { FlowData } from '../cityTypes'
import { quadPoint, type Vec } from '../motion'
import { laneInlet } from './EngineCore'
import { glowScale, glowTexture } from './textures'

const COLORS: Record<ParticleKind, number> = { event: 0x5cf1f4, rejected: 0xff4d64, late: 0xb07cff, spark: 0xff6a7c }
const SIZES: Record<ParticleKind, number> = { event: 9, rejected: 7, late: 9, spark: 4 }
const TRAIL_SPACING = 0.026 // seconds between trail ghosts

export interface FlowSource { truckId: string; x: number; y: number }

export class FlowLayer {
  readonly root = new Container({ label: 'flow' })
  private readonly pool: ParticlePool
  private readonly sprites: Sprite[] = []
  private readonly trail: number
  private readonly events = new RateAccumulator()
  private readonly rejected = new RateAccumulator()
  private readonly late = new RateAccumulator()
  private cursor = 0
  private readonly scratch: Vec = { x: 0, y: 0 }

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

  /** sizeScale keeps particles visible when zoomed out (passed from the camera). */
  update(dt: number, flow: FlowData | null, sources: FlowSource[], active: boolean, sizeScale: number): void {
    if (active && sources.length > 0 && flow) {
      this.spawn('event', this.events.take(visualRate(flow.eventsPerSec, this.reducedMotion), dt), sources, 1.5)
      this.spawn('rejected', this.rejected.take(anomalyRate(flow.rejectedPerSec, this.reducedMotion), dt), sources, 1.3)
      this.spawn('late', this.late.take(anomalyRate(flow.latePerSec, this.reducedMotion), dt), sources, 2.1)
    }
    this.pool.advance(dt, (kind, lane, x, y) => {
      if (kind === 'rejected') {
        // Rejected events bounce off the inlet as a few small red sparks.
        for (let i = 0; i < (this.reducedMotion ? 2 : 6); i += 1) {
          const angle = Math.PI * (0.55 + Math.random() * 0.9), speed = 70 + Math.random() * 110
          this.pool.emitSpark(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed, 0.45 + Math.random() * 0.3)
        }
      }
      this.onArrive(lane, kind)
    })
    this.draw(sizeScale)
  }

  private spawn(kind: ParticleKind, count: number, sources: FlowSource[], life: number): void {
    for (let i = 0; i < count; i += 1) {
      const source = sources[this.cursor++ % sources.length]
      const lane = partitionForTruck(source.truckId)
      this.pool.emitCurve(kind, lane, source, laneInlet(lane), life * (0.9 + Math.random() * 0.2))
    }
  }

  private draw(sizeScale: number): void {
    let s = 0
    for (let i = 0; i < this.pool.count; i += 1) {
      const p = this.pool.items[i]
      const fadeIn = Math.min(1, p.age / 0.15), fadeOut = Math.min(1, (p.life - p.age) / 0.2)
      const alpha = Math.min(fadeIn, fadeOut)
      for (let k = 0; k < this.trail; k += 1) {
        const sprite = this.sprites[s++]
        const pos = k === 0 ? p : this.trailPoint(p, k)
        sprite.visible = true
        sprite.position.set(pos.x, pos.y)
        sprite.tint = COLORS[p.kind]
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
