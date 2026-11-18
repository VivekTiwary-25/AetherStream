import { useEffect, useRef, useState } from 'react'
import './city.css'
import { CITY_SIZE } from './cityLayout'
import { CityModel, type EngineStatus } from './cityModel'
import { subscribeCity } from './citySocket'
import type { CityTruck } from './cityTypes'
import { partitionForTruck } from './cityLogic'
import { clamp } from './motion'
import type { CityScene } from './scene/CityScene'

interface Hud { status: EngineStatus; trucks: number; eventsPerSec: number; workersAlive: number; workers: number; backpressured: boolean; simTime: number }
interface Hover { truck: CityTruck; x: number; y: number }

const statusText: Record<CityTruck['status'], string> = { DRIVING: 'Driving', UNLOADING: 'Unloading', STOPPED: 'Stopped', IDLE: 'Idle' }

function readHud(model: CityModel): Hud {
  return {
    status: model.status(performance.now()),
    trucks: [...model.trucks.values()].filter((t) => t.removedAt === null).length,
    eventsPerSec: model.flow?.eventsPerSec ?? 0,
    workersAlive: model.workers.filter((w) => w.alive).length,
    workers: model.workers.length,
    backpressured: model.workers.some((w) => w.alive && w.backpressured),
    simTime: model.simTime,
  }
}

const simClock = (ms: number) => ms > 0 ? new Date(ms).toISOString().slice(11, 16) : '--:--'

export function CityView() {
  const host = useRef<HTMLDivElement>(null)
  const section = useRef<HTMLElement>(null)
  const model = useRef(new CityModel())
  const scene = useRef<CityScene | null>(null)
  const [hud, setHud] = useState<Hud>(() => readHud(model.current))
  const [hover, setHover] = useState<Hover | null>(null)
  const [message, setMessage] = useState('')
  const [dragOver, setDragOver] = useState(false)

  useEffect(() => subscribeCity((msg) => model.current.apply(msg, performance.now())), [])

  useEffect(() => {
    const timer = window.setInterval(() => setHud(readHud(model.current)), 400)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    const element = host.current
    if (!element || !window.WebGLRenderingContext) return
    let active: CityScene | null = null
    let cancelled = false
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    // Pixi is loaded lazily so the dashboard shell paints before the renderer is ready.
    void import('./scene/CityScene').then(({ CityScene }) => {
      if (cancelled) return
      active = new CityScene(model.current, reduced)
      scene.current = active
      return active.init(element)
    }).catch(() => setMessage('3D city unavailable in this browser'))
    const visibility = () => active?.setPaused(document.hidden)
    document.addEventListener('visibilitychange', visibility)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', visibility)
      active?.destroy()
      scene.current = null
    }
  }, [])

  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => setMessage(''), 2200)
    return () => window.clearTimeout(timer)
  }, [message])

  const onPointerMove = (event: React.PointerEvent) => {
    const current = scene.current, bounds = section.current?.getBoundingClientRect()
    if (!current || !bounds || current.camera.dragging) { if (hover) setHover(null); return }
    const id = current.pick(event.clientX, event.clientY)
    current.hoveredId = id
    const truck = id ? model.current.trucks.get(id)?.truck : undefined
    setHover(truck ? { truck, x: Math.min(event.clientX - bounds.left + 16, bounds.width - 200), y: Math.min(event.clientY - bounds.top + 16, bounds.height - 120) } : null)
  }

  const isTruckDrag = (event: React.DragEvent) => event.dataTransfer.types.includes('text/city-truck')
  const dropPoint = (event: React.DragEvent) => {
    const p = scene.current?.clientToWorld(event.clientX, event.clientY) ?? { x: CITY_SIZE / 2, y: CITY_SIZE / 2 }
    return { x: clamp(p.x, 0, CITY_SIZE), y: clamp(p.y, 0, CITY_SIZE) }
  }
  const onDragOver = (event: React.DragEvent) => {
    if (!isTruckDrag(event)) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
    if (scene.current) scene.current.dropTarget = dropPoint(event)
    if (!dragOver) setDragOver(true)
  }
  const clearDrag = () => { if (scene.current) scene.current.dropTarget = null; setDragOver(false) }
  const onDrop = async (event: React.DragEvent) => {
    if (!isTruckDrag(event)) return
    event.preventDefault()
    const point = dropPoint(event)
    clearDrag()
    scene.current?.ripple(point)
    try {
      const response = await fetch('/api/simulator/trucks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ x: Math.round(point.x), y: Math.round(point.y) }) })
      if (!response.ok) throw new Error(String(response.status))
      const body = await response.json().catch(() => ({})) as { truckId?: string }
      if (body.truckId) model.current.expectTruck(body.truckId, point, performance.now())
      setMessage(body.truckId ? `${body.truckId} dispatched` : 'Truck dispatched')
    } catch {
      setMessage('Simulator unavailable')
    }
  }

  const status = hud.status
  return <section ref={section} className={`city${dragOver ? ' city--drop' : ''}`} aria-label="Live delivery city"
    onDragOver={onDragOver} onDragLeave={(e) => { if (!section.current?.contains(e.relatedTarget as Node | null)) clearDrag() }} onDrop={(e) => void onDrop(e)}>
    <div ref={host} className="city__canvas" onPointerMove={onPointerMove} onPointerLeave={() => { setHover(null); if (scene.current) scene.current.hoveredId = null }} />
    <div className="city__vignette" aria-hidden="true" />

    <div className="city__glass city__title">
      <strong>AETHER CITY</strong>
      <span>2 km grid · {hud.trucks} trucks · sim {simClock(hud.simTime)}</span>
    </div>

    <div className={`city__glass city__status city__status--${status.mode}`} role="status" style={{ color: status.color }}>
      <i className="city__dot" style={{ background: status.color, boxShadow: `0 0 10px ${status.color}` }} />{status.label}
    </div>

    {hover && <div className="city__glass city__tooltip" style={{ left: hover.x, top: hover.y }}>
      <strong>{hover.truck.truckId}</strong>
      <span className={`city__pill city__pill--${hover.truck.status.toLowerCase()}`}>{statusText[hover.truck.status] ?? hover.truck.status}</span>
      <dl>
        <dt>Speed</dt><dd>{hover.truck.speedKmh.toFixed(1)} km/h</dd>
        {hover.truck.routeId && <><dt>Route</dt><dd>{hover.truck.routeId}</dd></>}
        <dt>Partition</dt><dd>P{partitionForTruck(hover.truck.truckId)}</dd>
      </dl>
    </div>}

    <div className="city__glass city__dock">
      <button type="button" className="city__chip" draggable
        onDragStart={(e) => { e.dataTransfer.setData('text/city-truck', 'new'); e.dataTransfer.effectAllowed = 'copy' }}
        onDragEnd={clearDrag}
        onClick={() => setMessage('Drag the chip onto a street')}>
        <span aria-hidden="true" className="city__chip-icon" />Add truck
      </button>
      <span className="city__flow">{message || `${hud.eventsPerSec.toFixed(1)} events/s`}</span>
    </div>

    <div className="city__glass city__legend">
      <span><i style={{ background: '#4ff0f2' }} />Driving</span>
      <span><i style={{ background: '#ffb547' }} />Unloading</span>
      <span><i style={{ background: '#ff4d64' }} />Stopped</span>
      <span className="city__legend-sep" />
      <span><i style={{ background: '#ff4d64' }} />Rejected</span>
      <span><i style={{ background: '#b07cff' }} />Late</span>
      <span className="city__legend-sep" />
      <span>{hud.workersAlive}/{hud.workers} workers · {hud.backpressured ? 'backpressured' : 'healthy'}</span>
    </div>
    <div className="city__hint" aria-hidden="true">scroll to zoom · drag to pan · double-click to reset</div>
  </section>
}
