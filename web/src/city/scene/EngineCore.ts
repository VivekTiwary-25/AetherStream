// The stream engine landmark right of the city: worker health ring, 6 partition lanes, recovery scan.
import { Container, Graphics, Sprite, Text, type TextStyleOptions } from 'pixi.js'
import { workerColor, type EngineStatus } from '../cityModel'
import type { CityWorker, FlowData } from '../cityTypes'
import { damp } from '../motion'
import { glowScale, glowTexture } from './textures'

export const PANEL = { x: 2150, y: 250, w: 440, h: 1500 }
const HUB = { x: PANEL.x + PANEL.w / 2, y: PANEL.y + 270, r: 62, ring: 100 }
const LANES = { top: 850, height: 120, gap: 22, x: PANEL.x + 26, w: PANEL.w - 52 }
const PARTITIONS = 6
const MAX_WORKER_LABELS = 8

export function laneInlet(lane: number): { x: number; y: number } {
  return { x: PANEL.x - 4, y: LANES.top + lane * (LANES.height + LANES.gap) + LANES.height / 2 }
}

const font = (size: number, fill: number, weight: TextStyleOptions['fontWeight'] = '600'): TextStyleOptions => ({ fontFamily: 'Inter, system-ui, sans-serif', fontSize: size, fontWeight: weight, fill, letterSpacing: 1 })

export interface EngineFrame {
  seconds: number
  dt: number
  flow: FlowData | null
  workers: CityWorker[]
  status: EngineStatus
  checkpointAge: number | null
  reducedMotion: boolean
  /** Lanes whose worker is dead (index = partition). */
  stalled?: boolean[]
}

export class EngineCore {
  readonly root = new Container({ label: 'engine-core' })
  private readonly dynamic = new Graphics()
  private readonly glow = new Graphics({ blendMode: 'add' })
  private readonly coreGlow: Sprite
  private readonly sweep: Sprite
  private readonly dim = new Graphics()
  private readonly title = new Text({ text: 'STREAM ENGINE', resolution: 2, style: { ...font(30, 0xbfe3f2, '700'), letterSpacing: 6 } })
  private readonly rate = new Text({ text: '0.0 ev/s', resolution: 2, style: font(46, 0xeaf8ff, '700') })
  private readonly state = new Text({ text: '', resolution: 2, style: { ...font(28, 0x9fc0d2), wordWrap: true, wordWrapWidth: PANEL.w - 40, align: 'center' } })
  private readonly laneLabels: Text[] = []
  private readonly laneRates: Text[] = []
  private readonly workerLabels: Text[] = []
  private readonly levels = new Array<number>(PARTITIONS).fill(0)
  private readonly hits = new Array<number>(PARTITIONS).fill(0)
  private readonly rates = new Array<number>(PARTITIONS).fill(0)
  private activity = 0
  private flash = 0
  private dimLevel = 0

  constructor() {
    const body = new Graphics()
    // 2.5D slab: long shadow, side wall, top face, lit rim.
    for (const [o, a] of [[60, 0.1], [40, 0.12], [22, 0.16]] as const) body.roundRect(PANEL.x + o * 0.8, PANEL.y + o, PANEL.w, PANEL.h, 30).fill({ color: 0x000000, alpha: a })
    body.roundRect(PANEL.x, PANEL.y + 14, PANEL.w, PANEL.h, 28).fill(0x08121c)
    body.roundRect(PANEL.x, PANEL.y, PANEL.w, PANEL.h, 28).fill(0x0e1d2b).stroke({ color: 0x2f8ea3, width: 3, alpha: 0.55 })
    body.roundRect(PANEL.x + 8, PANEL.y + 8, PANEL.w - 16, PANEL.h - 16, 22).stroke({ color: 0xffffff, width: 1, alpha: 0.05 })
    // Hub socket and lane tracks.
    body.circle(HUB.x, HUB.y, HUB.ring + 16).fill(0x0a1621).stroke({ color: 0x1f4558, width: 2, alpha: 0.8 })
    for (let lane = 0; lane < PARTITIONS; lane += 1) {
      const y = LANES.top + lane * (LANES.height + LANES.gap)
      body.roundRect(LANES.x, y, LANES.w, LANES.height, 14).fill(0x0a1824).stroke({ color: 0x1e4152, width: 1.5, alpha: 0.9 })
      body.roundRect(LANES.x + 20, y + LANES.height - 32, LANES.w - 40, 12, 6).fill(0x102838)
      const inlet = laneInlet(lane)
      body.roundRect(inlet.x - 14, inlet.y - 13, 32, 26, 8).fill(0x123041).stroke({ color: 0x3fb7c9, width: 1.5, alpha: 0.6 })
      const label = new Text({ text: `P${lane}`, resolution: 2, style: font(32, 0x8fb7c9, '700') })
      label.position.set(LANES.x + 20, y + 18)
      const rate = new Text({ text: '0.0/s', resolution: 2, style: font(32, 0xa9cbdb) })
      rate.anchor.set(1, 0)
      rate.position.set(LANES.x + LANES.w - 20, y + 20)
      this.laneLabels.push(label); this.laneRates.push(rate)
    }
    this.coreGlow = new Sprite({ texture: glowTexture(), anchor: 0.5, blendMode: 'add', tint: 0x45e6e0 })
    this.coreGlow.position.set(HUB.x, HUB.y)
    this.sweep = new Sprite({ texture: glowTexture(), anchor: 0.5, blendMode: 'add', tint: 0xb48cff })
    this.sweep.visible = false
    this.title.anchor.set(0.5, 0); this.title.position.set(HUB.x, PANEL.y + 34)
    this.rate.anchor.set(0.5, 0); this.rate.position.set(HUB.x, HUB.y + HUB.ring + 68)
    this.state.anchor.set(0.5, 0); this.state.position.set(HUB.x, HUB.y + HUB.ring + 128)
    for (let i = 0; i < MAX_WORKER_LABELS; i += 1) {
      const t = new Text({ text: '', resolution: 2, style: font(24, 0xffffff) })
      t.anchor.set(0.5); t.visible = false
      this.workerLabels.push(t)
    }
    this.root.addChild(body, this.glow, this.coreGlow, this.dynamic, this.title, this.rate, this.state, ...this.laneLabels, ...this.laneRates, ...this.workerLabels, this.dim, this.sweep)
  }
  /** Keep engine labels legible while the world is zoomed out. */
  setLabelScale(scale: number): void {
    this.title.scale.set(scale)
    this.rate.scale.set(scale)
    this.state.scale.set(scale)
    for (const label of this.laneLabels) label.scale.set(scale)
    for (const rate of this.laneRates) rate.scale.set(scale)
    for (const label of this.workerLabels) label.scale.set(scale)
  }

  /** A particle reached lane `lane`: light it up briefly. */
  hit(lane: number, strength = 1): void {
    this.hits[lane] = Math.min(1.4, this.hits[lane] + 0.35 * strength)
    this.flash = Math.min(1, this.flash + 0.08 * strength)
  }

  update(frame: EngineFrame): void {
    const { dt, flow, workers, status, seconds } = frame
    const recovering = status.mode === 'recovering'
    const halted = status.mode === 'stopped' || status.mode === 'failed' || status.mode === 'idle'
    const rates = this.rates
    let peak = 1
    for (let p = 0; p < PARTITIONS; p += 1) { rates[p] = flow?.byPartition?.[String(p)] ?? 0; peak = Math.max(peak, rates[p]) }
    this.activity = damp(this.activity, Math.min(1, (flow?.eventsPerSec ?? 0) / 150), 3, dt)
    this.flash = damp(this.flash, 0, 4, dt)
    this.dimLevel = damp(this.dimLevel, recovering ? 1 : halted ? 0.6 : 0, 5, dt)
    const g = this.dynamic.clear(), glow = this.glow.clear()

    for (let lane = 0; lane < PARTITIONS; lane += 1) {
      this.levels[lane] = damp(this.levels[lane], rates[lane] / peak, 4, dt)
      this.hits[lane] = damp(this.hits[lane], 0, 5, dt)
      const level = this.levels[lane], heat = Math.min(1, level * 0.7 + this.hits[lane] * 0.6)
      const y = LANES.top + lane * (LANES.height + LANES.gap)
      const barW = (LANES.w - 40) * Math.min(1, level)
      if (barW > 1) g.roundRect(LANES.x + 20, y + LANES.height - 32, barW, 12, 6).fill({ color: 0x4fe7ea, alpha: 0.55 + heat * 0.45 })
      if (heat > 0.01) {
        glow.roundRect(LANES.x, y, LANES.w, LANES.height, 14).fill({ color: 0x2fc9d6, alpha: 0.05 + heat * 0.16 })
        glow.roundRect(LANES.x - 2, y - 2, LANES.w + 4, LANES.height + 4, 16).stroke({ color: 0x6af2f5, width: 2, alpha: heat * 0.7 })
        const inlet = laneInlet(lane)
        glow.circle(inlet.x + 2, inlet.y, 9 + heat * 6).fill({ color: 0x6af2f5, alpha: 0.25 + heat * 0.5 })
      }
      const stalled = frame.stalled?.[lane] ?? false
      // Dead worker: a muted red outline and "stalled" until recovery hands the partition to a live worker.
      if (stalled) g.roundRect(LANES.x, y, LANES.w, LANES.height, 14).stroke({ color: 0xff5e6c, width: 2, alpha: 0.45 })
      setText(this.laneRates[lane], stalled ? 'stalled' : `${rates[lane].toFixed(1)}/s`)
      setFill(this.laneRates[lane], stalled ? 0xff8b97 : 0xa9cbdb)
      setFill(this.laneLabels[lane], heat > 0.3 ? 0xdffcff : 0x8fb7c9)
    }

    // Core disc brightens with throughput; a checkpoint briefly flashes an outer ring.
    g.circle(HUB.x, HUB.y, HUB.r).fill(recovering ? 0x2a2340 : 0x123a46).stroke({ color: recovering ? 0xa58cff : 0x52e3e0, width: 3, alpha: 0.8 })
    g.circle(HUB.x, HUB.y, HUB.r * 0.55).fill({ color: recovering ? 0x7d68c4 : 0x46e2d0, alpha: 0.35 + this.activity * 0.5 })
    this.coreGlow.tint = recovering ? 0x9d7dff : 0x45e6e0
    this.coreGlow.alpha = 0.25 + this.activity * 0.5 + this.flash
    this.coreGlow.scale.set(glowScale(HUB.r * 2.4 + this.flash * 30))
    if (frame.checkpointAge !== null && frame.checkpointAge < 1.6) {
      const t = frame.checkpointAge / 1.6
      glow.circle(HUB.x, HUB.y, HUB.ring + 10 + t * 50).stroke({ color: 0x7fdcff, width: 3, alpha: (1 - t) * 0.8 })
    }

    // Worker health ring: one arc per worker with a label outside it.
    const shown = Math.min(workers.length, MAX_WORKER_LABELS)
    const slice = (Math.PI * 2) / Math.max(shown, 1)
    if (shown === 0) g.circle(HUB.x, HUB.y, HUB.ring).stroke({ color: 0x2a3d4c, width: 10 })
    for (let i = 0; i < shown; i += 1) {
      const worker = workers[i]
      const start = -Math.PI / 2 + i * slice + 0.06, end = start + slice - 0.12
      const color = workerColor(worker)
      g.moveTo(HUB.x + Math.cos(start) * HUB.ring, HUB.y + Math.sin(start) * HUB.ring).arc(HUB.x, HUB.y, HUB.ring, start, end).stroke({ color, width: 10, cap: 'round' })
      glow.moveTo(HUB.x + Math.cos(start) * HUB.ring, HUB.y + Math.sin(start) * HUB.ring).arc(HUB.x, HUB.y, HUB.ring, start, end).stroke({ color, width: 24, alpha: 0.14, cap: 'round' })
      const mid = (start + end) / 2, label = this.workerLabels[i]
      label.visible = true
      label.position.set(HUB.x + Math.cos(mid) * (HUB.ring + 62), HUB.y + Math.sin(mid) * (HUB.ring + 40))
      setText(label, shortWorker(worker.workerId) + (worker.alive ? '' : ' ✕'))
      setFill(label, color)
    }
    for (let i = shown; i < MAX_WORKER_LABELS; i += 1) this.workerLabels[i].visible = false

    setText(this.rate, `${(flow?.eventsPerSec ?? 0).toFixed(1)} ev/s`)
    setText(this.state, status.label)
    setFill(this.state, Number.parseInt(status.color.slice(1), 16))

    // Recovery: dim the slab and scan it top to bottom.
    this.dim.clear()
    if (this.dimLevel > 0.01) this.dim.roundRect(PANEL.x, PANEL.y + 120, PANEL.w, PANEL.h - 120, 28).fill({ color: recovering ? 0x0d0820 : 0x05090f, alpha: this.dimLevel * 0.5 })
    this.sweep.visible = recovering
    if (recovering) {
      const t = frame.reducedMotion ? 0.5 : (seconds % 2.4) / 2.4
      this.sweep.position.set(HUB.x, PANEL.y + 130 + t * (PANEL.h - 170))
      this.sweep.scale.set(glowScale(PANEL.w * 0.75), glowScale(36))
      this.sweep.alpha = 0.9
      this.dim.rect(PANEL.x + 10, this.sweep.y - 1, PANEL.w - 20, 2).fill({ color: 0xd9c8ff, alpha: 0.8 })
    }
  }
}

function setText(text: Text, value: string): void {
  if (text.text !== value) text.text = value
}

function setFill(text: Text, color: number): void {
  if (text.style.fill !== color) text.style.fill = color
}

/** "worker-7f3a9c" -> "7F3A9"; keeps labels short around the ring. */
export function shortWorker(id: string): string {
  const tail = id.replace(/^worker[-_]?/i, '')
  return tail.length <= 6 ? tail.toUpperCase() : tail.slice(0, 5).toUpperCase()
}
