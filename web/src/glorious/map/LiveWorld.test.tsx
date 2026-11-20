import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DashboardState } from '../../app/state/store'
import type { LiveTruck } from './useLiveFleet'
import { LiveWorld } from './LiveWorld'

const sources = vi.hoisted(() => ({
  fleet: { trucks: [] as LiveTruck[], timeScale: 10, running: true, fresh: true, error: null },
  dashboard: {
    socketStatus: 'connected' as DashboardState['socketStatus'],
    workers: [{ workerId: 'worker-1', alive: true, partitions: [0, 1, 2, 3, 4, 5] }],
    alerts: [],
    simTime: null,
  },
}))

vi.mock('./useLiveFleet', () => ({ useLiveFleet: () => sources.fleet }))
vi.mock('../../app/state/store', () => ({ useDashboard: () => sources.dashboard }))
vi.mock('maplibre-gl', () => ({
  setWorkerUrl: vi.fn(),
  addProtocol: vi.fn(),
  removeProtocol: vi.fn(),
  Map: class {
    touchZoomRotate = { disableRotation() {} }
    on() { return this }
    resize() {}
    remove() {}
  },
}))
vi.mock('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url', () => ({ default: 'map-worker.js' }))
vi.mock('pmtiles', () => ({ Protocol: class { tile = vi.fn() } }))

beforeEach(() => {
  sources.fleet = {
    trucks: [{ truckId: 'truck-001', speedKmh: 35, status: 'DRIVING', x: 0, y: 0, heading: 0 }],
    timeScale: 10, running: true, fresh: true, error: null,
  }
  sources.dashboard.socketStatus = 'connected'
  sources.dashboard.workers = [{ workerId: 'worker-1', alive: true, partitions: [0, 1, 2, 3, 4, 5] }]
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  // WebGL and static map loading are outside these telemetry presentation tests.
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
    ok: true,
    json: async () => url.endsWith('routes.geojson') ? { features: [] } : [],
  })))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function openTruck() {
  const marker = screen.getByRole('button', { name: /kilometers per hour/ })
  fireEvent.click(marker)
  return marker
}

function assignment() {
  return screen.getByText('Assignment').closest('div')!.querySelector('dd')!.textContent
}

describe('Live World last-known telemetry', () => {
  it.each([
    ['DRIVING', 35, 'green'],
    ['DRIVING', 5, 'amber'],
    ['STOPPED', 0, 'grey'],
    ['IDLE', 0, 'grey'],
    ['UNLOADING', 0, 'grey'],
  ])('greys stale %s markers at %s km/h and restores their fresh tone', async (status, speedKmh, tone) => {
    sources.fleet.trucks[0] = { ...sources.fleet.trucks[0], status, speedKmh }
    const view = render(<LiveWorld />)
    const marker = openTruck()
    await screen.findByText('Loading routes')

    expect(marker.classList.contains(`gl-map-truck--${tone}`)).toBe(true)
    expect(screen.getByTitle(`Status: ${status}`).textContent).toBe(status)
    expect(assignment()).toContain('Worker W1')

    sources.fleet = { ...sources.fleet, fresh: false }
    view.rerender(<LiveWorld />)

    expect(marker.classList.contains('gl-map-truck--grey')).toBe(true)
    expect(screen.getByTitle(`Status: ${status}`).textContent).toBe(`Last known: ${status}`)
    expect(assignment()).toContain('Worker W1')

    sources.fleet = { ...sources.fleet, fresh: true }
    view.rerender(<LiveWorld />)

    expect(marker.classList.contains(`gl-map-truck--${tone}`)).toBe(true)
    expect(screen.getByTitle(`Status: ${status}`).textContent).toBe(status)
  })

  it.each([
    ['connecting', true],
    ['disconnected', true],
    ['connecting', false],
    ['disconnected', false],
  ] as const)('hides cached ownership while %s with freshness %s, then restores it', async (socketStatus, fresh) => {
    sources.fleet.fresh = fresh
    const view = render(<LiveWorld />)
    openTruck()
    await screen.findByText('Loading routes')
    expect(assignment()).toContain('Worker W1')

    sources.dashboard.socketStatus = socketStatus
    view.rerender(<LiveWorld />)

    expect(assignment()).toMatch(/^Partition \d · Worker unavailable$/)
    expect(assignment()).not.toContain('Worker W1')
    expect(screen.getByTitle('Status: DRIVING').textContent).toBe(fresh ? 'DRIVING' : 'Last known: DRIVING')

    sources.dashboard.socketStatus = 'connected'
    view.rerender(<LiveWorld />)
    expect(assignment()).toMatch(/^Partition \d · Worker W1$/)
  })

  it('keeps reassigning for a connected socket without a live partition owner', async () => {
    sources.dashboard.workers = []
    render(<LiveWorld />)
    openTruck()
    await screen.findByText('Loading routes')
    expect(assignment()).toMatch(/^Partition \d · reassigning$/)
  })
})
