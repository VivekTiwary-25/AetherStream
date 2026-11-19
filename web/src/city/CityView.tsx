import { useEffect, useRef, useState } from 'react'
import './city.css'
import { useAetherSocket } from '../api/ws'
import { toCityEnvelope } from './citySocket'
import { CityModel, type EngineStatus } from './cityModel'
import type { CityPoint, CityTruck } from './cityTypes'
import { partitionForTruck } from './cityLogic'
import { checkDrop, dispatchTruck, NOTICE_MS, TRUCK_MIME, type Notice } from './dispatch'
import type { CityScene } from './scene/CityScene'

interface Hud { status: EngineStatus; trucks: number; eventsPerSec: number; workersAlive: number; workers: number; backpressured: boolean; simTime: number }
interface Hover { truck: CityTruck; x: number; y: number }
type DragState = 'valid' | 'invalid' | null

const statusText: Record<CityTruck['status'], string> = { DRIVING: 'Driving', UNLOADING: 'Unloading', STOPPED: 'Stopped', IDLE: 'Idle' }

function readHud(model: CityModel): Hud {
  return {
    status: model.status(performance.now()),
    trucks: model.activeTrucks(),
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
  const [notice, setNotice] = useState<Notice | null>(null)
  const [dragOver, setDragOver] = useState<DragState>(null)

  const { lastMessage, status: socketStatus } = useAetherSocket()

  useEffect(() => {
    const message = toCityEnvelope(lastMessage)
    if (message?.data != null) model.current.apply(message, performance.now())
  }, [lastMessage])

  useEffect(() => {
    // 'connecting' on first load is normal; only a dropped link counts as offline.
    model.current.connected = socketStatus !== 'disconnected'
    setHud(readHud(model.current))
  }, [socketStatus])

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
    }).catch(() => setNotice({ tone: 'error', text: '3D city unavailable in this browser' }))
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
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS[notice.tone])
    return () => window.clearTimeout(timer)
  }, [notice])

  const onPointerMove = (event: React.PointerEvent) => {
    const current = scene.current, bounds = section.current?.getBoundingClientRect()
    if (!current || !bounds || current.camera.dragging) { if (hover) setHover(null); return }
    const id = current.pick(event.clientX, event.clientY)
    current.hoveredId = id
    const truck = id ? model.current.trucks.get(id)?.truck : undefined
    setHover(truck ? { truck, x: Math.min(event.clientX - bounds.left + 16, bounds.width - 200), y: Math.min(event.clientY - bounds.top + 16, bounds.height - 120) } : null)
  }

  const isTruckDrag = (event: React.DragEvent) => event.dataTransfer.types.includes(TRUCK_MIME)
  const worldAt = (event: React.DragEvent) => scene.current?.clientToWorld(event.clientX, event.clientY) ?? null
  const onDragOver = (event: React.DragEvent) => {
    if (!isTruckDrag(event)) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
    const world = worldAt(event), { inside } = checkDrop(world)
    if (scene.current) { scene.current.dropTarget = world; scene.current.dropValid = inside }
    const next: DragState = inside ? 'valid' : 'invalid'
    if (dragOver !== next) setDragOver(next)
  }
  const clearDrag = () => {
    if (scene.current) { scene.current.dropTarget = null; scene.current.dropValid = true }
    setDragOver(null)
  }

  const dispatch = async (point: CityPoint) => {
    setNotice({ tone: 'pending', text: 'Dispatching truck…' })
    const result = await dispatchTruck(point)
    if (result.ok && result.truckId) model.current.expectTruck(result.truckId, point, performance.now())
    if (!result.ok) scene.current?.ripple(point, 'error')
    setNotice(result.notice)
  }
  const onDrop = (event: React.DragEvent) => {
    if (!isTruckDrag(event)) return
    event.preventDefault()
    const world = worldAt(event)
    clearDrag()
    const { point, inside } = checkDrop(world)
    if (!inside) {
      if (world) scene.current?.ripple(world, 'error')
      setNotice({ tone: 'error', text: 'Drop the truck on the city streets' })
      return
    }
    scene.current?.ripple(point)
    void dispatch(point)
  }
  /** Keyboard / click alternative to dragging: add a truck at the depot. */
  const onChipClick = () => {
    const depot = model.current.depot
    scene.current?.ripple(depot)
    void dispatch({ x: depot.x, y: depot.y })
  }

  const status = hud.status
  const sectionClass = `city${dragOver ? ` city--drop city--drop-${dragOver}` : ''}${status.mode === 'disconnected' ? ' city--offline' : ''}`
  return <section ref={section} className={sectionClass} aria-label="Live delivery city"
    onDragOver={onDragOver} onDragLeave={(e) => { if (!section.current?.contains(e.relatedTarget as Node | null)) clearDrag() }} onDrop={onDrop}>
    <div ref={host} className="city__canvas" onMouseDown={(e) => { if (e.detail > 1) e.preventDefault() }} onPointerMove={onPointerMove} onPointerLeave={() => { setHover(null); if (scene.current) scene.current.hoveredId = null }} />
    <div className="city__vignette" aria-hidden="true" />

    <div className="city__glass city__title">
      <strong>AETHER CITY</strong>
      <span>2 km grid · {hud.trucks} trucks · sim {simClock(hud.simTime)}</span>
    </div>

    <div className={`city__glass city__status city__status--${status.mode}`} role="status" title={status.label} style={{ color: status.color }}>
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

    {dragOver && <div className={`city__glass city__drophint city__drophint--${dragOver}`} aria-hidden="true">
      {dragOver === 'valid' ? 'Release to add a truck here' : 'Outside the city · drop on a street'}
    </div>}

    <div className="city__glass city__dock">
      <button type="button" className="city__chip" draggable aria-describedby="city-chip-hint"
        title="Drag onto a street to add a truck there, or click to add one at the depot"
        onDragStart={(e) => { e.dataTransfer.setData(TRUCK_MIME, 'new'); e.dataTransfer.effectAllowed = 'copy' }}
        onDragEnd={clearDrag}
        onClick={onChipClick}>
        <span aria-hidden="true" className="city__chip-grip" />
        <span aria-hidden="true" className="city__chip-icon" />Add truck
      </button>
      <span id="city-chip-hint" className="city__sr">Drag onto a street to add a truck there, or press to add one at the depot.</span>
      <span className={`city__flow${notice ? ` city__flow--${notice.tone}` : ''}`} role="status" aria-live="polite">
        {notice ? notice.text : <>{hud.eventsPerSec.toFixed(1)} events/s <small>· drag truck onto map</small></>}
      </span>
    </div>

    <div className="city__glass city__legend">
      <span><i className="city__key city__key--truck" style={{ background: '#4ff0f2' }} />Driving</span>
      <span><i className="city__key city__key--truck" style={{ background: '#ffb547' }} />Unloading</span>
      <span><i className="city__key city__key--truck" style={{ background: '#9aabbf' }} />Idle</span>
      <span><i className="city__key city__key--truck city__key--alert" style={{ background: '#ff4d64' }} />Stopped</span>
      <span className="city__legend-sep" />
      <span><i className="city__key city__key--packet" style={{ background: '#5cf1f4' }} />Event</span>
      <span><i className="city__key city__key--packet" style={{ background: '#ff4d64' }} />Rejected</span>
      <span><i className="city__key city__key--packet" style={{ background: '#b07cff' }} />Late</span>
      <span className="city__legend-sep" />
      <span>{hud.workersAlive}/{hud.workers} workers · {hud.backpressured ? 'backpressured' : hud.workersAlive < hud.workers ? 'worker down' : 'healthy'}</span>
    </div>
    <div className="city__glass city__hint" aria-hidden="true"><span className="city__hint-mouse">scroll to zoom · drag to pan · double-click to reset</span><span className="city__hint-touch">pinch to zoom · drag to pan · double-tap to reset</span></div>
  </section>
}
