import { useEffect, useRef, useState } from 'react'
import type { SimulatorState, SimulatorTruck } from '../../api/types'
import { useAetherMessages } from '../../api/ws'

export type LiveTruck = SimulatorTruck & { routeId?: string }
type Fleet = { trucks: LiveTruck[]; timeScale: number; running: boolean; fresh: boolean; error: string | null }
const emptyFleet: Fleet = { trucks: [], timeScale: 0, running: false, fresh: false, error: null }

function fleetTrucks(trucks: LiveTruck[]): LiveTruck[] {
  // Only the configured six-truck fleet has geographic routes. Surge trucks remain in analytics.
  return trucks.filter((truck) => truck && typeof truck.truckId === 'string'
    && typeof truck.status === 'string' && Number.isFinite(truck.speedKmh))
    .sort((a, b) => a.truckId.localeCompare(b.truckId)).slice(0, 6)
}

export function useLiveFleet(): Fleet {
  const [fleet, setFleet] = useState<Fleet>(emptyFleet)
  const lastTelemetry = useRef(0)
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
      setFleet({ trucks: fleetTrucks(simulator.trucks), timeScale: simulator.timeScale, running: simulator.running, fresh: true, error: null })
    } else if (message.type === 'trucks') {
      const data = message.data as { trucks?: LiveTruck[] } | null
      if (!Array.isArray(data?.trucks)) return
      lastTelemetry.current = now
      socketRevision.current++
      setFleet((previous) => ({ ...previous, trucks: fleetTrucks(data.trucks!), fresh: true, error: null }))
    }
  })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    let busy = false
    // Trucks frames omit running/timeScale. Poll the real control state as well as
    // sharing the existing socket; never assume the Compose default speed of 10.
    const refresh = async () => {
      if (busy) return
      busy = true
      const startedAtRevision = socketRevision.current
      const startedAtSnapshotRevision = snapshotRevision.current
      try {
        const response = await fetch('/api/simulator/state', { signal: controller.signal })
        if (!response.ok) throw new Error(`Fleet unavailable (HTTP ${response.status})`)
        const simulator = await response.json() as SimulatorState
        if (!Array.isArray(simulator.trucks) || !Number.isFinite(simulator.timeScale)) throw new Error('Invalid simulator state')
        if (!active) return
        lastTelemetry.current = Date.now()
        setFleet((previous) => ({
          trucks: socketRevision.current !== startedAtRevision ? previous.trucks : fleetTrucks(simulator.trucks),
          timeScale: snapshotRevision.current !== startedAtSnapshotRevision ? previous.timeScale : simulator.timeScale,
          running: snapshotRevision.current !== startedAtSnapshotRevision ? previous.running : simulator.running,
          fresh: true,
          error: null,
        }))
      } catch (error) {
        if (active) setFleet((previous) => ({ ...previous, error: error instanceof Error ? error.message : 'Fleet unavailable' }))
      } finally {
        busy = false
      }
    }
    void refresh()
    const polling = window.setInterval(() => { void refresh() }, 3000)
    const freshness = window.setInterval(() => {
      if (Date.now() - lastTelemetry.current > 4500) {
        setFleet((previous) => previous.fresh ? { ...previous, fresh: false, error: 'Live fleet disconnected — movement paused' } : previous)
      }
    }, 1000)
    return () => {
      active = false
      controller.abort()
      window.clearInterval(polling)
      window.clearInterval(freshness)
    }
  }, [])
  return fleet
}
