// Owns the Pixi application: layers, camera input, and the per-frame update that reads the CityModel.
import { Application, Container, Graphics, Sprite, Text } from 'pixi.js'
import { CameraRig, type Screen } from '../camera'
import { buildCityLayout, WORLD_HEIGHT, WORLD_WIDTH } from '../cityLayout'
import type { CityModel } from '../cityModel'
import type { CityPoint } from '../cityTypes'
import { clamp, dampAngle, easeOutBack, easeOutCubic, headingToRotation, INTERPOLATION_DELAY_MS, type TrackPose } from '../motion'
import { drawStaticCity } from './drawCity'
import { EngineCore } from './EngineCore'
import { FlowLayer, type FlowSource } from './FlowLayer'
import { glowScale, glowTexture, releaseTextures } from './textures'
import { TRUCK_LENGTH, TruckSprite } from './TruckSprite'

const FADE_IN_MS = 750
const FADE_OUT_MS = 600
const MIN_TRUCK_PX = 26
const PICK_RADIUS_PX = 24

interface RenderedTruck { sprite: TruckSprite; x: number; y: number; rotation: number | null }
interface Ripple { x: number; y: number; at: number }

export class CityScene {
  readonly camera = new CameraRig(WORLD_WIDTH, WORLD_HEIGHT)
  hoveredId: string | null = null
  /** World point under a dragged "add truck" chip, or null. */
  dropTarget: CityPoint | null = null
  private readonly app = new Application()
  private readonly world = new Container()
  private readonly stopsLayer = new Graphics()
  private readonly depotLayer = new Container()
  private readonly beacon: Sprite
  private readonly fx = new Graphics({ blendMode: 'add' })
  private readonly truckLayer = new Container()
  private readonly engine = new EngineCore()
  private readonly flow: FlowLayer
  private readonly trucks = new Map<string, RenderedTruck>()
  private readonly ripples: Ripple[] = []
  private readonly pose: TrackPose = { x: 0, y: 0, vx: 0, vy: 0 }
  private stopsKey = ''
  private depotKey = ''
  private ready = false
  private disposed = false
  private cleanupInput: (() => void) | null = null

  constructor(private readonly model: CityModel, private readonly reducedMotion: boolean) {
    this.flow = new FlowLayer(reducedMotion, (lane, kind) => this.engine.hit(lane, kind === 'event' ? 1 : 0.5))
    this.beacon = new Sprite({ texture: glowTexture(), anchor: 0.5, blendMode: 'add', tint: 0x52e8ff })
  }

  get screen(): Screen { return { width: this.app.screen.width, height: this.app.screen.height } }

  async init(host: HTMLElement): Promise<void> {
    await this.app.init({ background: 0x050b13, antialias: true, autoDensity: true, resolution: Math.min(window.devicePixelRatio || 1, 2), preference: 'webgl', resizeTo: host })
    if (this.disposed) { this.app.destroy(true, { children: true }); return }
    this.ready = true
    const canvas = this.app.canvas
    canvas.style.cssText = 'display:block;width:100%;height:100%;touch-action:none;cursor:grab'
    host.appendChild(canvas)
    this.world.addChild(drawStaticCity(buildCityLayout()), this.stopsLayer, this.depotLayer, this.engine.root, this.truckLayer, this.flow.root, this.fx)
    this.app.stage.addChild(this.world)
    this.cleanupInput = this.bindInput(canvas)
    this.app.ticker.add((ticker) => this.frame(Math.min(ticker.deltaMS / 1000, 0.05)))
  }

  destroy(): void {
    this.disposed = true
    this.cleanupInput?.()
    if (this.ready) {
      this.app.destroy(true, { children: true })
      releaseTextures()
    }
  }

  clientToWorld(clientX: number, clientY: number): CityPoint {
    const rect = this.app.canvas.getBoundingClientRect()
    return this.camera.screenToWorld(clientX - rect.left, clientY - rect.top, this.screen)
  }

  /** Truck under the pointer (screen-space radius so it works at every zoom). */
  pick(clientX: number, clientY: number): string | null {
    if (!this.ready) return null
    const point = this.clientToWorld(clientX, clientY)
    const radius = PICK_RADIUS_PX / this.camera.transform(this.screen).scale
    let best: string | null = null, bestDistance = radius
    for (const [id, truck] of this.trucks) {
      const d = Math.hypot(truck.x - point.x, truck.y - point.y)
      if (d < bestDistance) { best = id; bestDistance = d }
    }
    return best
  }

  ripple(at: CityPoint): void { this.ripples.push({ ...at, at: performance.now() }) }

  private bindInput(canvas: HTMLCanvasElement): () => void {
    let last = { x: 0, y: 0, t: 0 }
    const local = (e: { clientX: number; clientY: number }) => { const r = canvas.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top } }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      const p = local(e)
      this.camera.wheel(p.x, p.y, e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1))
    }
    const down = (e: PointerEvent) => {
      if (e.button !== 0) return
      canvas.setPointerCapture(e.pointerId)
      canvas.style.cursor = 'grabbing'
      this.camera.beginDrag()
      last = { x: e.clientX, y: e.clientY, t: performance.now() }
    }
    const move = (e: PointerEvent) => {
      if (!this.camera.dragging) return
      const now = performance.now()
      this.camera.dragBy(e.clientX - last.x, e.clientY - last.y, (now - last.t) / 1000)
      last = { x: e.clientX, y: e.clientY, t: now }
    }
    const up = () => {
      if (!this.camera.dragging) return
      canvas.style.cursor = 'grab'
      this.camera.endDrag((performance.now() - last.t) / 1000)
    }
    const dbl = () => this.camera.reset()
    canvas.addEventListener('wheel', wheel, { passive: false })
    canvas.addEventListener('pointerdown', down)
    canvas.addEventListener('pointermove', move)
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('pointercancel', up)
    canvas.addEventListener('dblclick', dbl)
    return () => {
      canvas.removeEventListener('wheel', wheel)
      canvas.removeEventListener('pointerdown', down)
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('pointercancel', up)
      canvas.removeEventListener('dblclick', dbl)
    }
  }

  setPaused(paused: boolean): void {
    if (!this.ready) return
    if (paused) this.app.ticker.stop()
    else this.app.ticker.start()
  }

  private frame(dt: number): void {
    const now = performance.now(), seconds = now / 1000
    const screen = this.screen
    this.camera.update(dt, screen)
    const view = this.camera.transform(screen)
    this.world.scale.set(view.scale)
    this.world.position.set(view.x, view.y)

    this.syncStatics()
    const sources = this.updateTrucks(dt, now, seconds, view.scale)
    const status = this.model.status(now)
    this.flow.update(dt, this.model.flow, sources, status.mode !== 'idle', clamp(1 / view.scale, 1.2, 4))
    const checkpoint = this.model.lastCheckpoint
    this.engine.update({ seconds, dt, flow: this.model.flow, workers: this.model.workers, status, checkpointAge: checkpoint ? (now - checkpoint.at) / 1000 : null, reducedMotion: this.reducedMotion })
    this.drawEffects(now, seconds)
  }

  private updateTrucks(dt: number, now: number, seconds: number, viewScale: number): FlowSource[] {
    const renderTime = now - INTERPOLATION_DELAY_MS
    // Keep trucks legible when zoomed out: never smaller than MIN_TRUCK_PX on screen.
    const baseScale = clamp(MIN_TRUCK_PX / (TRUCK_LENGTH * viewScale), 1, 3.4)
    const sources: FlowSource[] = []
    for (const [id, entry] of this.model.trucks) {
      const pose = entry.track.poseAt(renderTime, this.pose)
      if (!pose) continue
      let rendered = this.trucks.get(id)
      if (!rendered) {
        rendered = { sprite: new TruckSprite(id), x: pose.x, y: pose.y, rotation: null }
        this.trucks.set(id, rendered)
        this.truckLayer.addChild(rendered.sprite.root)
      }
      rendered.x = pose.x; rendered.y = pose.y
      // Face the direction of travel; hold the last heading while stopped.
      const speed = Math.hypot(pose.vx, pose.vy)
      const target = speed > 0.6 ? Math.atan2(pose.vy, pose.vx) : rendered.rotation ?? headingToRotation(entry.truck.heading)
      rendered.rotation = rendered.rotation === null ? target : dampAngle(rendered.rotation, target, 9, dt)
      const born = clamp((now - entry.bornAt) / FADE_IN_MS, 0, 1)
      const gone = entry.removedAt === null ? 1 : clamp(1 - (now - entry.removedAt) / FADE_OUT_MS, 0, 1)
      rendered.sprite.update(pose.x, pose.y, rendered.rotation, entry.truck.status, seconds, {
        alpha: easeOutCubic(born) * gone,
        scale: baseScale * (0.6 + 0.4 * easeOutBack(born)),
        hovered: this.hoveredId === id,
        reducedMotion: this.reducedMotion,
      })
      if (entry.removedAt === null) sources.push({ truckId: id, x: pose.x, y: pose.y })
    }
    for (const [id, rendered] of this.trucks) {
      if (this.model.trucks.has(id)) continue
      rendered.sprite.destroy()
      this.trucks.delete(id)
    }
    return sources
  }

  /** Stops and depot only change when a snapshot says so; redraw them then. */
  private syncStatics(): void {
    const stopsKey = this.model.stops.map((s) => `${s.stopId}:${s.x}:${s.y}`).join('|')
    if (stopsKey !== this.stopsKey) {
      this.stopsKey = stopsKey
      const g = this.stopsLayer.clear()
      for (const stop of this.model.stops) {
        g.roundRect(stop.x - 15 + 2, stop.y - 15 + 3, 30, 30, 8).fill({ color: 0x000000, alpha: 0.35 })
        g.roundRect(stop.x - 15, stop.y - 15, 30, 30, 8).fill(0x0f2c36).stroke({ color: 0x53c9d0, width: 2, alpha: 0.75 })
        g.circle(stop.x, stop.y, 5).fill({ color: 0xa6f3f3, alpha: 0.9 })
      }
    }
    const depot = this.model.depot
    const depotKey = `${depot.x}:${depot.y}`
    if (depotKey !== this.depotKey) {
      this.depotKey = depotKey
      this.depotLayer.removeChildren().forEach((child) => { if (child !== this.beacon) child.destroy({ children: true }) })
      this.depotLayer.addChild(drawDepot(depot), this.beacon)
      const label = new Text({ text: 'DEPOT', resolution: 3, style: { fontFamily: 'Inter, system-ui, sans-serif', fontSize: 15, fontWeight: '700', fill: 0xbdf3f8, letterSpacing: 4 } })
      label.anchor.set(0.5, 0)
      label.position.set(depot.x, depot.y + 64)
      this.depotLayer.addChild(label)
      this.beacon.position.set(depot.x, depot.y - 22)
    }
  }

  private drawEffects(now: number, seconds: number): void {
    const g = this.fx.clear()
    const depot = this.model.depot
    // Depot beacon: soft glow plus a slow outward pulse ring.
    this.beacon.scale.set(glowScale(46))
    this.beacon.alpha = this.reducedMotion ? 0.55 : 0.5 + 0.18 * Math.sin(seconds * 2.2)
    if (!this.reducedMotion) {
      const t = (seconds % 2.6) / 2.6
      g.circle(depot.x, depot.y - 22, 20 + easeOutCubic(t) * 70).stroke({ color: 0x52e8ff, width: 2.5, alpha: 0.5 * (1 - t) })
    }
    // Stops: amber glow while a truck unloads there, bloom when a delivery completes.
    for (const stop of this.model.stops) {
      for (const entry of this.model.trucks.values()) {
        if (entry.truck.status === 'UNLOADING' && Math.hypot(entry.truck.x - stop.x, entry.truck.y - stop.y) < 60) {
          g.roundRect(stop.x - 18, stop.y - 18, 36, 36, 10).stroke({ color: 0xffb547, width: 2.5, alpha: 0.75 })
          break
        }
      }
    }
    for (const d of this.model.deliveries) {
      const t = (now - d.at) / 1300
      if (t < 0 || t >= 1) continue
      const e = easeOutCubic(t)
      g.circle(d.x, d.y, 16 + e * 30).fill({ color: 0x7ff7e4, alpha: 0.28 * (1 - t) })
      g.circle(d.x, d.y, 16 + e * 62).stroke({ color: 0x9cfbe9, width: 3, alpha: 0.85 * (1 - t) })
    }
    // Drop ripples for newly dispatched trucks: three staggered rings.
    for (let i = this.ripples.length - 1; i >= 0; i -= 1) {
      const r = this.ripples[i], age = (now - r.at) / 1000
      if (age > 1.4) { this.ripples.splice(i, 1); continue }
      for (let k = 0; k < 3; k += 1) {
        const t = clamp((age - k * 0.16) / 0.95, 0, 1)
        if (t <= 0 || t >= 1) continue
        g.circle(r.x, r.y, 12 + easeOutCubic(t) * 110).stroke({ color: 0x6af2f5, width: 4 - k, alpha: 0.9 * (1 - t) })
      }
      if (age < 0.5) g.circle(r.x, r.y, 26 * (1 - age * 2)).fill({ color: 0xbffcff, alpha: 0.5 * (1 - age * 2) })
    }
    // Reticle while the "add truck" chip is dragged over the map.
    if (this.dropTarget) {
      const { x, y } = this.dropTarget, spin = this.reducedMotion ? 0 : seconds * 1.4
      for (let k = 0; k < 4; k += 1) {
        const a = spin + k * Math.PI / 2
        g.moveTo(x + Math.cos(a) * 34, y + Math.sin(a) * 34).arc(x, y, 34, a, a + 0.9)
      }
      g.stroke({ color: 0x6af2f5, width: 3, alpha: 0.9 })
      g.circle(x, y, 6).fill({ color: 0x6af2f5, alpha: 0.8 })
    }
  }
}

function drawDepot(depot: CityPoint): Graphics {
  const g = new Graphics()
  const size = 112, lift = 22, x = depot.x - size / 2, y = depot.y - size / 2
  for (const [len, alpha] of [[90, 0.1], [62, 0.13], [36, 0.18]] as const) {
    g.poly([x, y, x + size, y, x + size + len * 0.9, y + len, x + size + len * 0.9, y + size + len, x + len * 0.9, y + size + len, x, y + size]).fill({ color: 0x000000, alpha })
  }
  g.roundRect(x, y, size, size, 16).fill(0x0a1a26)
  g.roundRect(x, y - lift, size, size, 16).fill(0x15384a).stroke({ color: 0x55e2e8, width: 3, alpha: 0.85 })
  g.roundRect(x + 10, y - lift + 10, size - 20, size - 20, 10).fill({ color: 0x1b4559, alpha: 1 }).stroke({ color: 0x7ff0f4, width: 1, alpha: 0.25 })
  g.moveTo(x + 6, y - lift + size - 8).lineTo(x + 4, y - lift + 4).lineTo(x + size - 8, y - lift + 4).stroke({ color: 0xbaf8fb, width: 2, alpha: 0.5 })
  // Loading bays on the facade.
  for (let k = 0; k < 4; k += 1) g.roundRect(x + 14 + k * 23, y + size - lift + 4, 16, lift - 8, 2).fill({ color: 0x6fe3ea, alpha: 0.35 })
  // Roof beacon housing.
  g.circle(depot.x, depot.y - lift, 15).fill(0x0d2632).stroke({ color: 0x7ff0f4, width: 2, alpha: 0.8 })
  g.circle(depot.x, depot.y - lift, 6).fill(0xc8fbff)
  return g
}
