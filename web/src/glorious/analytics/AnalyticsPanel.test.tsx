import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WebSocketMessage } from '../../api/types'
import { DashboardContext, initialDashboardState } from '../../app/state/store'
import { AnalyticsPanel } from './AnalyticsPanel'
import { LiveWorld } from '../map/LiveWorld'
const hooks = vi.hoisted(() => ({ listener: null as ((message: WebSocketMessage) => void) | null }))
vi.mock('../../api/ws', () => ({ useAetherMessages: (listener: (message: WebSocketMessage) => void) => { hooks.listener = listener; return 'connected' } }))
vi.mock('../shell/windows', () => ({ useWindows: () => ({ update: vi.fn() }) }))
vi.mock('../map/useLiveFleet', () => ({ useLiveFleet: () => ({ trucks: [{ truckId: 'truck-001', status: 'DRIVING', speedKmh: 35, x: 0, y: 0, heading: 0 }], timeScale: 1, running: true, fresh: true, error: null }) }))
vi.mock('maplibre-gl', () => ({
  setWorkerUrl: vi.fn(), addProtocol: vi.fn(), removeProtocol: vi.fn(),
  LngLatBounds: class { extend() {} },
  Map: class {
    touchZoomRotate = { disableRotation: vi.fn() }
    on() {}
    remove() {}
  },
}))

function renderPanel(id: 'deliveries' | 'alerts' | 'failed-records' | 'truck-speeds', state = initialDashboardState) {
  return render(<DashboardContext.Provider value={state}><AnalyticsPanel id={id} /></DashboardContext.Provider>)
}

describe('analytics live-data states and snapshot seeding', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('distinguishes disconnected empty states from an active execution with no data', () => {
    for (const id of ['deliveries', 'alerts', 'failed-records'] as const) {
      const { unmount } = renderPanel(id, { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'RUNNING' } as never })
      expect(screen.getByText(/No (delivery windows|alerts|failed records) yet\./)).toBeTruthy()
      unmount()
    }
    const disconnected = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'disconnected', execution: { state: 'RUNNING' } as never })
    expect(screen.getByText('Disconnected — live data unavailable.')).toBeTruthy()
    disconnected.unmount()
    const stopped = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'STOPPED' } as never })
    expect(screen.getByText('No data yet — start the demo pipeline.')).toBeTruthy()
    stopped.unmount()
    const { unmount } = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'RUNNING' } as never })
    expect(screen.getByText('No delivery windows yet.')).toBeTruthy()
    unmount()
  })

  it('seeds truck rows and current speed from the simulator snapshot', () => {
    renderPanel('truck-speeds')
    act(() => hooks.listener?.({ type: 'snapshot', data: { simulator: { trucks: [{ truckId: 'truck-009', speedKmh: 42.5, status: 'MOVING' }] } } } as WebSocketMessage))
    expect(screen.getByTestId('truck-row-truck-009').textContent).toContain('42.5 km/h now')
  })
  it('confirms map breakdowns and cancels with Escape without sending a request', () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    render(<DashboardContext.Provider value={initialDashboardState}><LiveWorld /></DashboardContext.Provider>)
    fireEvent.contextMenu(screen.getByRole('button', { name: /Truck T-01, DRIVING/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Simulate breakdown' }))
    expect(screen.getByRole('alertdialog').textContent).toContain('Simulate a breakdown of Truck truck-001?')
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(fetch.mock.calls.some(([url]) => url === '/api/simulator/scenario')).toBe(false)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Simulate breakdown' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(fetch).toHaveBeenCalledWith('/api/simulator/scenario', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'breakdown', truckId: 'truck-001' }) }))
  })

})
