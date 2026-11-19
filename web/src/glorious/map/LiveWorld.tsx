import { useEffect, useRef, useState } from 'react'
import * as maplibregl from 'maplibre-gl'
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import { Protocol } from 'pmtiles'
import { useDashboard } from '../../app/state/store'
import { partitionForTruck } from '../../city/cityLogic'
import { GlassSurface } from '../shell/GlassSurface'
import { advanceDistance, facingLeft, prepareRoute, sampleRoute, truckLabel, type PreparedRoute, type RouteFeature } from './mapLogic'
import { useLiveFleet } from './useLiveFleet'
import 'maplibre-gl/dist/maplibre-gl.css'
import './LiveWorld.css'

type Landmark = { name: string; lon: number; lat: number; source: string }
type Motion = { route: PreparedRoute; distance: number; point: { lon: number; lat: number; dx: number; dy: number } }
const INITIAL_BOUNDS: [number, number, number, number] = [76.55, 12.23, 76.75, 12.42]
let protocolUsers = 0
const protocol = new Protocol()
// Pin the emitted local worker URL; Vite's dependency prebundle relocates MapLibre's default relative URL.
maplibregl.setWorkerUrl(mapWorkerUrl)
const stopped = (status: string) => ['STOPPED', 'IDLE', 'UNLOADING'].includes(status.toUpperCase())
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

export function LiveWorld() {
  const fleet = useLiveFleet()
  const dashboard = useDashboard()
  const live = useRef(fleet)
  const map = useRef<maplibregl.Map | null>(null)
  const container = useRef<HTMLDivElement>(null)
  const markers = useRef(new Map<string, HTMLButtonElement>())
  const motions = useRef(new Map<string, Motion>())
  const card = useRef<HTMLDivElement>(null)
  const selectedRef = useRef<string | null>(null)
  const actionButton = useRef<HTMLButtonElement>(null)
  const longPress = useRef<number | undefined>(undefined)
  const pressOrigin = useRef({ x: 0, y: 0, opened: false })
  const pendingRequest = useRef<AbortController | null>(null)
  const [ready, setReady] = useState(false)
  const [routes, setRoutes] = useState<PreparedRoute[]>([])
  const [landmarks, setLandmarks] = useState<Landmark[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [contextMenu, setContextMenu] = useState(false)
  const [query, setQuery] = useState('')
  const [mapError, setMapError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const trucks = fleet.trucks

  useEffect(() => {
    live.current = fleet
  }, [fleet])
  useEffect(() => { selectedRef.current = selected }, [selected])
  useEffect(() => {
    if (!contextMenu) return
    const frame = requestAnimationFrame(() => {
      if (actionButton.current && !actionButton.current.disabled) actionButton.current.focus()
      else card.current?.focus()
    })
    return () => cancelAnimationFrame(frame)
  }, [contextMenu, selected])

  useEffect(() => {
    if (!container.current) return
    if (protocolUsers++ === 0) maplibregl.addProtocol('pmtiles', protocol.tile)
    const instance = new maplibregl.Map({
      container: container.current,
      style: '/glorious/map/style.json',
      bounds: INITIAL_BOUNDS,
      fitBoundsOptions: { padding: 70 },
      attributionControl: { compact: true },
      maxPitch: 0,
      dragRotate: false,
      touchPitch: false,
    })
    map.current = instance
    instance.touchZoomRotate.disableRotation()
    instance.on('load', () => setReady(true))
    instance.on('error', (event) => setMapError(event.error.message || 'Offline map could not be loaded.'))
    const resize = new ResizeObserver(() => instance.resize())
    resize.observe(container.current)
    const controller = new AbortController()
    async function loadData() {
      try {
        const [routeResponse, landmarkResponse] = await Promise.all([
          fetch('/glorious/map/routes.geojson', { signal: controller.signal }),
          fetch('/glorious/map/landmarks.json', { signal: controller.signal }),
        ])
        if (!routeResponse.ok || !landmarkResponse.ok) throw new Error('Offline route or landmark data could not be loaded.')
        const routeData = await routeResponse.json() as { features: RouteFeature[] }
        const landmarkData = await landmarkResponse.json() as Landmark[]
        if (controller.signal.aborted) return
        setRoutes(routeData.features.slice().sort((a, b) => a.properties.routeIndex - b.properties.routeIndex).map(prepareRoute))
        setLandmarks(landmarkData)
      } catch (error) {
        if (!controller.signal.aborted) setMapError(error instanceof Error ? error.message : 'Offline data unavailable.')
      }
    }
    void loadData()
    return () => {
      controller.abort()
      resize.disconnect()
      instance.remove()
      map.current = null
      if (--protocolUsers === 0) maplibregl.removeProtocol('pmtiles')
    }
  }, [])

  useEffect(() => {
    const instance = map.current
    if (!ready || !instance || !routes.length) return
    instance.addSource('fleet-display-routes', { type: 'geojson', data: { type: 'FeatureCollection', features: routes.map((route) => route.feature) } })
    instance.addLayer({
      id: 'fleet-display-routes', type: 'line', source: 'fleet-display-routes',
      paint: { 'line-color': '#c09b50', 'line-width': 3, 'line-opacity': 0.42 },
    })
    return () => {
      if (map.current === instance && instance.getStyle()) {
        if (instance.getLayer('fleet-display-routes')) instance.removeLayer('fleet-display-routes')
        if (instance.getSource('fleet-display-routes')) instance.removeSource('fleet-display-routes')
      }
    }
  }, [ready, routes])

  useEffect(() => {
    if (!routes.length) return
    let frame = 0
    let previous = 0
    const scratch = new maplibregl.LngLat(0, 0)
    function animate(now: number) {
      const elapsed = previous ? now - previous : 0
      previous = now
      const instance = map.current
      const current = live.current
      for (let index = 0; index < current.trucks.length; index++) {
        const truck = current.trucks[index]
        const route = routes[index % routes.length]
        let motion = motions.current.get(truck.truckId)
        if (!motion || motion.route !== route) {
          motion = { route, distance: 0, point: { lon: 0, lat: 0, dx: 0, dy: 0 } }
          motions.current.set(truck.truckId, motion)
        }
        // Speed is real km/h, integrated over wall-clock elapsed time × the simulator's real timeScale.
        // Geographic path is a display mapping; speed, status, routeId, partition and worker are real.
        if (current.fresh && current.running) {
          motion.distance = advanceDistance(motion.distance, truck.speedKmh, elapsed, current.timeScale, truck.status, route.totalMeters)
        }
        sampleRoute(route, motion.distance, motion.point)
        if (!instance) continue
        scratch.lng = motion.point.lon
        scratch.lat = motion.point.lat
        const position = instance.project(scratch)
        const length = Math.hypot(motion.point.dx, motion.point.dy) || 1
        const offset = (index - 2.5) * 5
        const x = position.x - motion.point.dy / length * offset
        const y = position.y - motion.point.dx / length * offset
        const marker = markers.current.get(truck.truckId)
        if (marker) {
          marker.style.transform = `translate(${x}px, ${y}px) translate(-50%, -50%)`
          marker.style.setProperty('--truck-facing', facingLeft(motion.point.dx) ? '-1' : '1')
          marker.style.visibility = 'visible'
        }
        if (selectedRef.current === truck.truckId && card.current) {
          const width = instance.getContainer().clientWidth
          const height = instance.getContainer().clientHeight
          const cardWidth = card.current.offsetWidth
          const cardHeight = card.current.offsetHeight
          const left = Math.max(12, Math.min(x + 36, width - cardWidth - 12))
          const top = Math.max(84, Math.min(y - 70, height - cardHeight - 35))
          card.current.style.transform = `translate(${left}px, ${top}px)`
          card.current.style.visibility = 'visible'
        }
      }
      frame = requestAnimationFrame(animate)
    }
    // Do not extrapolate an unobserved speed/status across a background-tab suspension.
    const resetClock = () => { previous = 0 }
    document.addEventListener('visibilitychange', resetClock)
    frame = requestAnimationFrame(animate)
    return () => {
      cancelAnimationFrame(frame)
      document.removeEventListener('visibilitychange', resetClock)
    }
  }, [routes])

  useEffect(() => () => {
    window.clearTimeout(longPress.current)
    pendingRequest.current?.abort()
  }, [])

  function openTruck(id: string, menu = false) {
    selectedRef.current = id
    setSelected(id)
    setContextMenu(menu)
    setActionError(null)
    setNotice(null)
  }

  function closeCard() {
    if (selected) markers.current.get(selected)?.focus()
    selectedRef.current = null
    setSelected(null)
    setContextMenu(false)
  }

  function flyTo(coordinates: [number, number]) {
    const instance = map.current
    if (!instance) return
    if (reducedMotion()) instance.jumpTo({ center: coordinates, zoom: 14 })
    else instance.flyTo({ center: coordinates, zoom: 14, duration: 900 })
  }

  async function breakdown() {
    const truck = live.current.trucks.find((item) => item.truckId === selectedRef.current)
    if (!truck || truck.status.toUpperCase() === 'STOPPED' || pendingRequest.current) return
    const controller = new AbortController()
    pendingRequest.current = controller
    setPending(truck.truckId)
    setActionError(null)
    setNotice(null)
    try {
      const response = await fetch('/api/simulator/scenario', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'breakdown', truckId: truck.truckId }), signal: controller.signal,
      })
      if (!response.ok) throw new Error(`Breakdown request failed (${response.status}).`)
      if (selectedRef.current === truck.truckId) setNotice('Breakdown requested. Waiting for simulator telemetry.')
    } catch (error) {
      if (!controller.signal.aborted && selectedRef.current === truck.truckId) setActionError(error instanceof Error ? error.message : 'Breakdown request failed.')
    } finally {
      pendingRequest.current = null
      if (!controller.signal.aborted) setPending(null)
    }
  }

  const activeIndex = trucks.findIndex((truck) => truck.truckId === selected)
  const active = trucks[activeIndex]
  const route = activeIndex >= 0 && routes.length ? routes[activeIndex % routes.length].feature.properties : null
  const partition = active ? partitionForTruck(active.truckId) : null
  // Wn is a display abbreviation of the live worker-n ID, never an assumed assignment.
  const owner = dashboard.workers.find((worker) => worker.alive && worker.partitions.includes(partition ?? -1))
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const foundTrucks = normalizedQuery ? trucks.filter((truck) => `${truck.truckId} ${truckLabel(truck.truckId)}`.toLocaleLowerCase().includes(normalizedQuery)) : []
  const foundLandmarks = normalizedQuery ? landmarks.filter((landmark) => landmark.name.toLocaleLowerCase().includes(normalizedQuery)).slice(0, 6) : []

  return <section className="gl-live-world" aria-label="Live World map" onKeyDown={(event) => {
    if (event.key === 'Escape') { event.preventDefault(); closeCard(); setQuery('') }
  }}>
    <div ref={container} className="gl-live-world__map" />
    <header className="gl-live-world__heading"><h1>Live World</h1><p>Real-time fleet across Mysuru</p></header>
    <div className="gl-live-world__search">
      <GlassSurface strong>
        <label className="gl-sr-only" htmlFor="gl-map-search">Search trucks and Mysuru landmarks</label>
        <input id="gl-map-search" type="search" placeholder="Search trucks or landmarks" value={query} onChange={(event) => setQuery(event.target.value)} autoComplete="off" aria-controls="gl-map-results" />
        {normalizedQuery && <ul id="gl-map-results" className="gl-live-world__results">
          {foundTrucks.map((truck) => <li key={truck.truckId}><button onClick={() => {
            const motion = motions.current.get(truck.truckId)
            if (motion) flyTo([motion.point.lon, motion.point.lat])
            openTruck(truck.truckId)
            setQuery('')
            requestAnimationFrame(() => card.current?.focus())
          }}>{truckLabel(truck.truckId)} <small>{truck.truckId}</small></button></li>)}
          {foundLandmarks.map((landmark) => <li key={landmark.source}><button onClick={() => { flyTo([landmark.lon, landmark.lat]); setQuery('') }}>{landmark.name}<small>Landmark</small></button></li>)}
          {!foundTrucks.length && !foundLandmarks.length && <li className="gl-live-world__empty">No matching trucks or landmarks</li>}
        </ul>}
      </GlassSurface>
    </div>
    <div className="gl-live-world__controls"><GlassSurface strong>
      <button aria-label="Recenter Mysuru map" onClick={() => map.current?.fitBounds(INITIAL_BOUNDS, { padding: 70, duration: reducedMotion() ? 0 : 700 })}>⌖</button>
      <button aria-label="Zoom in" onClick={() => map.current?.zoomIn({ duration: reducedMotion() ? 0 : 250 })}>+</button>
      <button aria-label="Zoom out" onClick={() => map.current?.zoomOut({ duration: reducedMotion() ? 0 : 250 })}>−</button>
    </GlassSurface></div>
    <div className="gl-live-world__markers">
      {trucks.map((truck) => {
        const recentAlert = dashboard.simTime !== null && dashboard.alerts.some((alert) => alert.key === truck.truckId && alert.eventTime <= dashboard.simTime! && dashboard.simTime! - alert.eventTime <= 60_000)
        const tone = stopped(truck.status) ? 'grey' : recentAlert || truck.speedKmh < 10 ? 'amber' : 'green'
        return <button key={truck.truckId} ref={(element) => { if (element) markers.current.set(truck.truckId, element); else markers.current.delete(truck.truckId) }}
          className={`gl-map-truck gl-map-truck--${tone}`} aria-label={`${truckLabel(truck.truckId)}, ${truck.status}, ${truck.speedKmh.toFixed(1)} kilometers per hour`}
          aria-expanded={selected === truck.truckId} aria-controls={selected === truck.truckId ? 'gl-truck-card' : undefined}
          onMouseEnter={() => { if (!contextMenu) openTruck(truck.truckId) }}
          onFocus={() => { if (selectedRef.current !== truck.truckId) openTruck(truck.truckId) }}
          onClick={() => { if (!pressOrigin.current.opened) openTruck(truck.truckId) }}
          onContextMenu={(event) => { event.preventDefault(); openTruck(truck.truckId, true) }}
          onKeyDown={(event) => { if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) { event.preventDefault(); openTruck(truck.truckId, true) } }}
          onPointerDown={(event) => {
            pressOrigin.current = { x: event.clientX, y: event.clientY, opened: false }
            window.clearTimeout(longPress.current)
            if (event.pointerType !== 'mouse') longPress.current = window.setTimeout(() => { pressOrigin.current.opened = true; openTruck(truck.truckId, true) }, 550)
          }}
          onPointerMove={(event) => { if (Math.hypot(event.clientX - pressOrigin.current.x, event.clientY - pressOrigin.current.y) > 8) window.clearTimeout(longPress.current) }}
          onPointerUp={() => { window.clearTimeout(longPress.current) }}
          onPointerCancel={() => window.clearTimeout(longPress.current)}>
          <span className="gl-map-truck__placeholder" aria-hidden="true" />
          <img src="/glorious/truck.png" alt="" draggable={false} onLoad={(event) => {
            event.currentTarget.style.opacity = '1'
            if (event.currentTarget.parentElement) event.currentTarget.parentElement.dataset.loaded = 'true'
          }} onError={(event) => {
            event.currentTarget.style.opacity = '0'
            if (event.currentTarget.parentElement) delete event.currentTarget.parentElement.dataset.loaded
          }} />
          <span className="gl-map-truck__label">{truckLabel(truck.truckId)}</span>
        </button>
      })}
    </div>
    {active && <div ref={card} className="gl-live-world__card" id="gl-truck-card" tabIndex={-1} aria-label={`${truckLabel(active.truckId)} details`}>
      <GlassSurface strong>
        <div className="gl-live-world__card-title"><h2>{truckLabel(active.truckId)}</h2><button aria-label="Close truck details" onClick={closeCard}>×</button></div>
        <img className="gl-live-world__photo" src="/glorious/truck-photo.jpg" alt="Fleet truck" onError={(event) => { event.currentTarget.hidden = true }} />
        <dl>
          <div><dt>Truck</dt><dd>{active.truckId}</dd></div>
          <div><dt>Status</dt><dd><span className={`gl-live-world__status gl-live-world__status--${stopped(active.status) ? 'stopped' : 'moving'}`}>{active.status}</span></dd></div>
          <div><dt>Speed</dt><dd>{active.speedKmh.toFixed(1)} km/h</dd></div>
          <div><dt>Route ID</dt><dd>{active.routeId ?? 'Not provided'}</dd></div>
          <div><dt>Display path</dt><dd>{route ? `${route.from} → ${route.to}` : 'Loading routes'}</dd></div>
          <div><dt>Assignment</dt><dd>Partition {partition} · {owner ? `Worker ${owner.workerId.replace(/^worker-/, 'W')}` : 'reassigning'}</dd></div>
        </dl>
        <p className="gl-live-world__mapping">Geographic path is a display mapping; telemetry is real.</p>
        {contextMenu && <div role="menu" aria-label={`${truckLabel(active.truckId)} actions`} className="gl-live-world__actions">
          <button ref={actionButton} role="menuitem" disabled={active.status.toUpperCase() === 'STOPPED' || pending !== null} onClick={() => { void breakdown() }}>{pending === active.truckId ? 'Requesting…' : 'Simulate breakdown'}</button>
          <p>Resumes automatically.</p>
        </div>}
        {actionError && <p role="alert" className="gl-live-world__error">{actionError}</p>}
        {notice && <p role="status">{notice}</p>}
      </GlassSurface>
    </div>}
    {(mapError || fleet.error || !fleet.fresh || !fleet.running) && <div className="gl-live-world__notice" role="status"><GlassSurface>{mapError ?? fleet.error ?? (!fleet.fresh ? 'Telemetry unavailable · positions frozen' : 'Simulator paused · positions frozen')}</GlassSurface></div>}
  </section>
}
