import { useEffect, useRef, useState } from 'react'
import type { SimulatorState, SimulatorTruck } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
import { createBackoff } from '../data/backoff'

export type LiveTruck = SimulatorTruck & { routeId?: string }
type Fleet = { trucks: LiveTruck[]; timeScale: number; running: boolean; fresh: boolean; controlsFresh: boolean; error: string | null }
const emptyFleet: Fleet = { trucks: [], timeScale: 0, running: false, fresh: false, controlsFresh: false, error: null }

function fleetTrucks(trucks: LiveTruck[]): LiveTruck[] {
  // Only the configured six-truck fleet has geographic routes. Surge trucks remain in analytics.
  return trucks.filter((truck) => truck && typeof truck.truckId === 'string'
    && typeof truck.status === 'string' && Number.isFinite(truck.speedKmh))
    .sort((a, b) => a.truckId.localeCompare(b.truckId)).slice(0, 6)
}

export function useLiveFleet(): Fleet {
  const [fleet, setFleet] = useState<Fleet>(emptyFleet)
  const lastTelemetry = useRef(0)
  const lastControls = useRef<number | null>(null)
  const socketRevision = useRef(0)
  const snapshotRevision = useRef(0)
  useAetherMessages((message) => {
    const now = Date.now()
    if (message.type === 'snapshot') {
      const simulator = (message.data as { simulator?: SimulatorState } | null)?.simulator
      if (!simulator || !Array.isArray(simulator.trucks)) return
      lastTelemetry.current = now
      socketRevision.current++
      snapshotRevision.current++
      const controlsFresh = typeof simulator.running === 'boolean' && Number.isFinite(simulator.timeScale) && simulator.timeScale >= 0
      lastControls.current = controlsFresh ? now : null
      setFleet((previous) => ({
        trucks: fleetTrucks(simulator.trucks),
        timeScale: controlsFresh ? simulator.timeScale : previous.timeScale,
        running: controlsFresh ? simulator.running : previous.running,
        fresh: true,
        controlsFresh,
        error: controlsFresh ? null : 'Simulator controls unavailable',
      }))
    } else if (message.type === 'trucks') {
      const data = message.data as { trucks?: LiveTruck[] } | null
      if (!Array.isArray(data?.trucks)) return
      lastTelemetry.current = now
      socketRevision.current++
      setFleet((previous) => {
        const controlsFresh = previous.controlsFresh && lastControls.current !== null && now - lastControls.current <= 4500
        return { ...previous, trucks: fleetTrucks(data.trucks!), fresh: true, controlsFresh, error: controlsFresh ? null : 'Simulator controls unavailable' }
      })
    }
  })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    let polling: number | undefined
    let requestTimeout: number | undefined
    const backoff = createBackoff('Live fleet')
    // Trucks frames omit running/timeScale. Poll the real control state as well as
    // sharing the existing socket; never assume the Compose default speed of 10.
    const refresh = async () => {
      let delay = 3000
      const startedAtRevision = socketRevision.current
      const startedAtSnapshotRevision = snapshotRevision.current
      const requestController = new AbortController()
      requestTimeout = window.setTimeout(() => requestController.abort(), 4000)
      const abortParent = () => requestController.abort()
      if (controller.signal.aborted) abortParent()
      else controller.signal.addEventListener('abort', abortParent, { once: true })
      const signal = typeof AbortSignal.any === 'function'
        ? AbortSignal.any([controller.signal, requestController.signal])
        : requestController.signal

      try {
        const response = await fetch('/api/simulator/state', { signal })
        if (!response.ok) throw new Error(`Fleet unavailable (HTTP ${response.status})`)
        const simulator = await response.json() as SimulatorState
        if (!Array.isArray(simulator.trucks) || typeof simulator.running !== 'boolean' || !Number.isFinite(simulator.timeScale) || simulator.timeScale < 0) throw new Error('The simulator sent data that could not be read')
        if (!active) return
        backoff.success()
        const now = Date.now()
        const newerTrucks = socketRevision.current !== startedAtRevision
        const newerControls = snapshotRevision.current !== startedAtSnapshotRevision
        if (!newerTrucks) lastTelemetry.current = now
        if (!newerControls) lastControls.current = now
        setFleet((previous) => ({
          trucks: newerTrucks ? previous.trucks : fleetTrucks(simulator.trucks),
          timeScale: newerControls ? previous.timeScale : simulator.timeScale,
          running: newerControls ? previous.running : simulator.running,
          fresh: now - lastTelemetry.current <= 4500,
          controlsFresh: lastControls.current !== null && now - lastControls.current <= 4500,
          error: lastControls.current === null || now - lastControls.current > 4500 ? 'Simulator controls unavailable' : now - lastTelemetry.current <= 4500 ? null : 'Disconnected · trucks shown at last known position',
        }))
      } catch (error) {
        if (!active) return
        delay = backoff.failure(error)
        setFleet((previous) => snapshotRevision.current !== startedAtSnapshotRevision ? previous : { ...previous, controlsFresh: false, error: error instanceof Error ? error.message : 'Fleet unavailable' })
      } finally {
        // The shared controller lives for the whole mount; drop this poll's listener
        // or every 3 s poll leaks one closure (and its AbortController) for hours.
        controller.signal.removeEventListener('abort', abortParent)
        if (requestTimeout !== undefined) window.clearTimeout(requestTimeout)
        requestTimeout = undefined
        if (active) polling = window.setTimeout(() => { void refresh() }, delay)
      }
    }
    void refresh()
    const freshness = window.setInterval(() => {
      const now = Date.now()
      const fresh = now - lastTelemetry.current <= 4500
      const controlsFresh = lastControls.current !== null && now - lastControls.current <= 4500
      setFleet((previous) => {
        if (!fresh && previous.fresh) return { ...previous, fresh: false, controlsFresh, error: 'Disconnected · trucks shown at last known position' }
        if (!controlsFresh && previous.controlsFresh) return { ...previous, controlsFresh: false, error: 'Simulator controls unavailable' }
        return previous
      })
    }, 1000)
    return () => {
      active = false
      controller.abort()
      window.clearTimeout(polling)
      if (requestTimeout !== undefined) window.clearTimeout(requestTimeout)
      window.clearInterval(freshness)
    }
  }, [])
  return fleet
}
