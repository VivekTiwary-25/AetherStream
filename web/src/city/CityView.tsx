import { useEffect, useRef, useState } from 'react'
import { Application, Container, Graphics, Text } from 'pixi.js'
import { fitWorld, interpolateTruck, partitionForTruck, type TruckPose } from './cityLogic'
import { subscribeCity } from './citySocket'
import type { CityExecution, CitySnapshot, CityTruck, CityWorker, FlowData, SimulatorState, TruckUpdate } from './cityTypes'

const colorFor = (status: CityTruck['status']) => status === 'DRIVING' ? 0x48e5e7 : status === 'UNLOADING' ? 0xffbd69 : status === 'STOPPED' ? 0xff657a : 0x9aabbf
const panelStyle: React.CSSProperties = { position: 'absolute', zIndex: 2, color: '#d9e8f3', font: '12px/1.45 system-ui, sans-serif', background: 'rgba(8,20,32,.76)', border: '1px solid rgba(116,190,205,.2)', borderRadius: 10, padding: '9px 12px', backdropFilter: 'blur(12px)' }

export function CityView() {
  const host = useRef<HTMLDivElement>(null)
  const [sim, setSim] = useState<SimulatorState | null>(null)
  const [workers, setWorkers] = useState<CityWorker[]>([])
  const [execution, setExecution] = useState<CityExecution | null>(null)
  const [flow, setFlow] = useState<FlowData | null>(null)
  const [hover, setHover] = useState<CityTruck | null>(null)
  const [message, setMessage] = useState('Connecting to city stream')
  const dragging = useRef(false)
  const camera = useRef({ x: 0, y: 0, zoom: 1 })
  const truckData = useRef<CityTruck[]>([])
  const socketCleanup = useRef<(() => void) | undefined>(undefined)
  const latest = useRef({ sim, workers, execution, flow })
  latest.current = { sim, workers, execution, flow }

  useEffect(() => {
    socketCleanup.current = subscribeCity((msg) => {
      if (msg.type === 'snapshot') {
        const snap = msg.data as CitySnapshot
        setSim(snap.simulator); truckData.current = snap.simulator.trucks; setWorkers(snap.workers); setExecution(snap.execution)
      } else if (msg.type === 'trucks') {
        const update = msg.data as TruckUpdate
        truckData.current = update.trucks
        setSim((old) => old ? { ...old, simTime: update.simTime, trucks: update.trucks } : old)
      } else if (msg.type === 'workers') setWorkers(msg.data as CityWorker[])
      else if (msg.type === 'flow') setFlow(msg.data as FlowData)
      else if (msg.type === 'execution') setExecution(msg.data as CityExecution)
    })
    return () => socketCleanup.current?.()
  }, [])

  useEffect(() => {
    if (!window.WebGLRenderingContext) return
    const element = host.current
    if (!element) return
    const app = new Application()
    let disposed = false
    let resizeObserver: ResizeObserver | undefined
    const view = new Container()
    const ground = new Graphics()
    const roads = new Graphics()
    const sites = new Graphics()
    const core = new Graphics()
    const particles = new Graphics()
    const truckLayer = new Container()
    const engineLabel = new Text({ text: 'STREAM ENGINE', style: { fontFamily: 'Arial', fontSize: 18, fontWeight: 'bold', fill: 0xaad3e5, letterSpacing: 2 } })
    view.addChild(ground, roads, sites, core, particles, truckLayer, engineLabel)
    let width = element.clientWidth, height = element.clientHeight
    let ready = false
    const poses = new Map<string, TruckPose>()
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    const start = async () => {
      await app.init({ background: 0x07111d, antialias: true, autoDensity: true, resolution: Math.min(window.devicePixelRatio || 1, 2), preference: 'webgl', resizeTo: element })
      if (disposed) { app.destroy(true); return }
      ready = true
      app.canvas.style.cssText = 'display:block;width:100%;height:100%;touch-action:none;cursor:grab'
      element.appendChild(app.canvas)
      app.stage.addChild(view)
      resizeObserver = new ResizeObserver(() => { width = element.clientWidth; height = element.clientHeight })
      resizeObserver.observe(element)
      app.canvas.addEventListener('wheel', (e) => { e.preventDefault(); camera.current.zoom = Math.max(.45, Math.min(3.2, camera.current.zoom * (e.deltaY > 0 ? .9 : 1.1))) }, { passive: false })
      app.canvas.addEventListener('pointerdown', (e) => { dragging.current = true; app.canvas.setPointerCapture(e.pointerId); app.canvas.style.cursor = 'grabbing'; (app.canvas as HTMLCanvasElement & { _px?: number; _py?: number })._px = e.clientX; (app.canvas as HTMLCanvasElement & { _px?: number; _py?: number })._py = e.clientY })
      app.canvas.addEventListener('pointerup', () => { dragging.current = false; app.canvas.style.cursor = 'grab' })
      app.canvas.addEventListener('pointermove', (e) => { if (!dragging.current) return; const canvas = app.canvas as HTMLCanvasElement & { _px?: number; _py?: number }; const dx = e.clientX - (canvas._px ?? e.clientX), dy = e.clientY - (canvas._py ?? e.clientY); camera.current.x += dx; camera.current.y += dy; canvas._px = e.clientX; canvas._py = e.clientY })
      app.canvas.addEventListener('dblclick', () => { camera.current = { x: 0, y: 0, zoom: 1 } })
      app.ticker.add((ticker) => {
        if (document.hidden) return
        const dt = Math.min(ticker.deltaMS / 1000, .05)
        const base = fitWorld(width, height, 34)
        const scale = base.scale * camera.current.zoom
        view.scale.set(scale)
        view.position.set((width - 2000 * scale) / 2 + camera.current.x, (height - 2000 * scale) / 2 + camera.current.y)
        ground.clear().rect(0, 0, 2000, 2000).fill(0x0a1724)
        roads.clear()
        for (let n = 0; n <= 2000; n += 200) {
          roads.rect(n - 9, 0, 18, 2000).fill({ color: 0x182b38, alpha: .7 })
          roads.rect(0, n - 9, 2000, 18).fill({ color: 0x182b38, alpha: .7 })
          roads.rect(n - 1, 0, 2, 2000).fill({ color: 0x4f8790, alpha: .15 })
          roads.rect(0, n - 1, 2000, 2).fill({ color: 0x4f8790, alpha: .15 })
        }
        sites.clear()
        for (let i = 0; i < 75; i += 1) {
          const x = 35 + ((i * 137 + 71) % 1920), y = 35 + ((i * 263 + 119) % 1920)
          if (Math.abs(x - 1000) < 40 || Math.abs(y - 1000) < 40) continue
          const park = i % 11 === 0
          sites.roundRect(x, y, park ? 72 : 50, park ? 66 : 58, 5).fill(park ? 0x16372f : 0x111f2b).stroke({ color: park ? 0x2d735d : 0x263846, alpha: .65, width: 2 })
          if (!park) sites.rect(x + 12, y + 11, 10, 9).fill({ color: 0x82a6a3, alpha: .09 })
        }
        const city = latest.current.sim
        const depot = city?.depot ?? { x: 1000, y: 1000 }
        const pulse = reduceMotion ? .3 : .35 + .15 * Math.sin(performance.now() / 550)
        sites.circle(depot.x, depot.y, 57).stroke({ color: 0x41d9df, alpha: pulse, width: 4 }).circle(depot.x, depot.y, 35).fill(0x173c49).stroke({ color: 0x55e2e8, alpha: .85, width: 3 }).roundRect(depot.x - 17, depot.y - 12, 34, 24, 5).fill(0x5ae2e5)
        for (const stop of city?.stops ?? []) sites.circle(stop.x, stop.y, 13).fill(0x122b37).stroke({ color: 0x63bdc1, alpha: .8, width: 2 }).circle(stop.x, stop.y, 3).fill(0xa2eff0)
        core.clear()
        const activeExecution = latest.current.execution
        const activeFlow = latest.current.flow
        const recovering = activeExecution?.state === 'RECOVERING'
        core.roundRect(1850, 760, 118, 480, 18).fill({ color: recovering ? 0x202335 : 0x102330, alpha: .98 }).stroke({ color: recovering ? 0xa383d2 : 0x348999, alpha: .8, width: 4 })
        core.circle(1909, 795, 18).fill(recovering ? 0x8370ae : 0x36d7be)
        for (let lane = 0; lane < 6; lane += 1) core.roundRect(1868, 835 + lane * 56, 82, 36, 8).fill({ color: 0x1d4250, alpha: recovering ? .25 : .42 + ((activeFlow?.byPartition[String(lane)] ?? 0) > 0 ? .5 : 0) }).stroke({ color: recovering ? 0x786b9a : 0x48ccd2, alpha: .6, width: 1 })
        const health = latest.current.workers.slice(0, 12)
        health.forEach((worker, i) => core.arc(1909, 795, 29, -Math.PI / 2 + i * Math.PI * 2 / Math.max(health.length, 1), -Math.PI / 2 + (i + .8) * Math.PI * 2 / Math.max(health.length, 1)).stroke({ color: !worker.alive ? 0xff5e68 : worker.backpressured ? 0xffbd69 : 0x60e5a5, width: 4 }))
        engineLabel.position.set(1860, 1260)
        particles.clear()
        const spawnCount = reduceMotion ? 1 : Math.min(12, Math.floor((activeFlow?.eventsPerSec ?? 4) * dt * .16))
        for (let i = 0; i < spawnCount; i += 1) {
          const truck = truckData.current[i % Math.max(truckData.current.length, 1)]
          if (truck) { const lane = partitionForTruck(truck.truckId); const y = 853 + lane * 56; particles.moveTo(truck.x, truck.y).quadraticCurveTo(1450, (truck.y + y) / 2 - 80, 1868, y + 18).stroke({ color: (activeFlow?.latePerSec ?? 0) > 0 ? 0xa780ff : (activeFlow?.rejectedPerSec ?? 0) > 0 ? 0xff596f : 0x54e8ee, alpha: .55, width: 2 }) }
        }
        const current = new Set<string>()
        for (const truck of truckData.current) {
          current.add(truck.truckId)
          let pose = poses.get(truck.truckId)
          if (!pose) pose = { ...truck, targetX: truck.x, targetY: truck.y, targetHeading: truck.heading }
          const rate = 1 - Math.exp(-dt * 7)
          pose = interpolateTruck(pose, truck, rate)
          poses.set(truck.truckId, pose)
          let sprite = truckLayer.children.find((child) => child.label === truck.truckId) as Container | undefined
          if (!sprite) { sprite = new Container(); sprite.label = truck.truckId; const glow = new Graphics().circle(0, 0, 24).fill({ color: 0x44dfe5, alpha: .1 }); const body = new Graphics().roundRect(-18, -11, 26, 22, 5).fill(0x1b3444).stroke({ color: 0x6ee7e9, width: 2 }).roundRect(8, -8, 13, 16, 4).fill(0x52dadd); const text = new Text({ text: truck.truckId.slice(-2), style: { fontFamily: 'Arial', fontSize: 10, fontWeight: 'bold', fill: 0xffffff } }); text.position.set(-12, -5); sprite.addChild(glow, body, text); truckLayer.addChild(sprite) }
          sprite.position.set(pose.x, pose.y); sprite.rotation = (pose.heading - 90) * Math.PI / 180; sprite.alpha = truck.status === 'STOPPED' && !reduceMotion ? .8 + .2 * Math.sin(performance.now() / 400) : 1
          const g = sprite.children[1] as Graphics
          g.tint = colorFor(truck.status)
        }
        for (const id of poses.keys()) if (!current.has(id)) { poses.delete(id); const child = truckLayer.children.find((item) => item.label === id); if (child) truckLayer.removeChild(child).destroy({ children: true }) }
      })
    }
    void start()
    const visibility = () => { if (document.hidden) app.ticker.stop(); else if (ready) app.ticker.start() }
    document.addEventListener('visibilitychange', visibility)
    return () => { disposed = true; resizeObserver?.disconnect(); document.removeEventListener('visibilitychange', visibility); if (ready) app.destroy(true, { children: true, texture: true }); else app.destroy(true) }
  }, [])

  const status = execution?.state === 'RECOVERING' ? `Recovering from checkpoint #${execution.restoredFromCheckpoint ?? 0}` : execution?.state ?? 'Awaiting execution'
  const addTruck = async (event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault()
    const bounds = host.current?.getBoundingClientRect()
    if (!bounds) return
    const view = fitWorld(bounds.width, bounds.height, 34), zoom = camera.current.zoom
    const x = Math.max(0, Math.min(2000, (event.clientX - bounds.left - (bounds.width - 2000 * view.scale * zoom) / 2 - camera.current.x) / (view.scale * zoom)))
    const y = Math.max(0, Math.min(2000, (event.clientY - bounds.top - (bounds.height - 2000 * view.scale * zoom) / 2 - camera.current.y) / (view.scale * zoom)))
    try { const response = await fetch('/api/simulator/trucks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ x, y }) }); if (!response.ok) throw new Error(); setMessage('Truck dispatched') }
    catch { setMessage('Simulator unavailable') }
    window.setTimeout(() => setMessage(''), 1800)
  }
  const drop = (event: React.DragEvent<HTMLDivElement>) => { if (event.dataTransfer.types.includes('text/city-truck')) void addTruck(event as unknown as React.DragEvent<HTMLButtonElement>) }
  const title = hover ? `${hover.truckId} · ${hover.speedKmh.toFixed(1)} km/h · ${hover.status}` : null

  return <section aria-label="Live delivery city" style={{ position: 'relative', width: '100%', height: 'min(72vh, 720px)', minHeight: 430, overflow: 'hidden', borderRadius: 22, border: '1px solid rgba(93,160,179,.2)', background: 'radial-gradient(ellipse at 48% 45%, #122636, #07111d 78%)', boxShadow: '0 24px 70px rgba(0,0,0,.35)' }} onDragOver={(e) => e.preventDefault()} onDrop={drop}>
    <div ref={host} style={{ position: 'absolute', inset: 0 }} onPointerMove={(event) => { const bounds = host.current?.getBoundingClientRect(); if (!bounds) return; const px = (event.clientX - bounds.left - (bounds.width - 2000 * fitWorld(bounds.width, bounds.height, 34).scale * camera.current.zoom) / 2 - camera.current.x) / (fitWorld(bounds.width, bounds.height, 34).scale * camera.current.zoom); const py = (event.clientY - bounds.top - (bounds.height - 2000 * fitWorld(bounds.width, bounds.height, 34).scale * camera.current.zoom) / 2 - camera.current.y) / (fitWorld(bounds.width, bounds.height, 34).scale * camera.current.zoom); const truck = truckData.current.find((item) => Math.hypot(item.x - px, item.y - py) < 32); setHover(truck ?? null) }} />
    <div style={{ ...panelStyle, top: 16, left: 18 }}><strong style={{ color: '#e4f5fa', letterSpacing: 1.6 }}>AETHER CITY</strong><div style={{ color: '#8aa9bb' }}>2000 × 2000 m · {sim?.trucks.length ?? 0} active trucks</div></div>
    <div style={{ ...panelStyle, top: 16, right: 18, color: execution?.state === 'FAILED' ? '#ff7888' : execution?.state === 'RECOVERING' ? '#c6a5ff' : '#76e2b0' }}>● {status}</div>
    {title && <div style={{ ...panelStyle, top: '48%', left: '50%', transform: 'translate(-50%,-50%)', pointerEvents: 'none' }}>{title}</div>}
    <div style={{ ...panelStyle, bottom: 16, left: 18, display: 'flex', alignItems: 'center', gap: 10 }}><button draggable onDragStart={(e) => e.dataTransfer.setData('text/city-truck', 'new')} onClick={() => setMessage('Drag this chip onto a street')} style={{ color: '#daf7f7', background: 'rgba(43,139,145,.35)', border: '1px solid rgba(91,226,225,.45)', borderRadius: 8, padding: '7px 11px', cursor: 'grab' }}>＋ Add truck</button><span>{message || `${(flow?.eventsPerSec ?? 0).toFixed(1)} events/s`}</span></div>
    <div style={{ ...panelStyle, bottom: 16, right: 18, color: '#9eb9c9' }}>{workers.filter((worker) => worker.alive).length}/{workers.length} workers · {workers.some((worker) => worker.backpressured) ? 'BACKPRESSURED' : 'HEALTHY'}</div>
  </section>
}
