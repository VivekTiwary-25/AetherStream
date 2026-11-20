import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeadLetter, WebSocketMessage, WindowResult } from '../../api/types'
import { DashboardContext, initialDashboardState } from '../../app/state/store'
import { AnalyticsPanel } from './AnalyticsPanel'
import { LiveWorld } from '../map/LiveWorld'
const hooks = vi.hoisted(() => ({ listener: null as ((message: WebSocketMessage) => void) | null, status: 'connected' as 'connected' | 'disconnected' }))
vi.mock('../../api/ws', () => ({ useAetherMessages: (listener: (message: WebSocketMessage) => void) => { hooks.listener = listener; return hooks.status } }))
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
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); hooks.status = 'connected' })

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

  it('replaces old live trucks with reconnect snapshots and does not resurrect removed trucks', () => {
    renderPanel('truck-speeds')
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 35 }] } } as WebSocketMessage))
    act(() => hooks.listener?.({ type: 'snapshot', data: { simulator: { trucks: [{ truckId: 'truck-009', speedKmh: 42.5 }] } } } as WebSocketMessage))
    expect(screen.queryByTestId('truck-row-truck-001')).toBeNull()
    expect(screen.getByTestId('truck-row-truck-009').textContent).toContain('42.5 km/h now')
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [] } } as unknown as WebSocketMessage))
    expect(screen.queryByTestId('truck-row-truck-009')).toBeNull()
  })

  it('does not label disconnected or expired readings as current and clears its freshness timer', () => {
    vi.useFakeTimers()
    const { rerender, unmount } = renderPanel('truck-speeds')
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 35 }] } } as WebSocketMessage))
    expect(screen.getByText('35.0 km/h now')).toBeTruthy()
    act(() => vi.advanceTimersByTime(5000))
    expect(screen.getByText('Live speed unavailable')).toBeTruthy()
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 36 }] } } as WebSocketMessage))
    expect(screen.getByText('36.0 km/h now')).toBeTruthy()
    hooks.status = 'disconnected'
    rerender(<DashboardContext.Provider value={initialDashboardState}><AnalyticsPanel id="truck-speeds" /></DashboardContext.Provider>)
    expect(screen.getByText('Live speed unavailable')).toBeTruthy()
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps truck rows and history usable when live readings or window values are null', () => {
    const window = { sinkId: 'speedSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 3, values: null } as unknown as WindowResult
    renderPanel('truck-speeds', { ...initialDashboardState, windows: [window] })
    act(() => hooks.listener?.({ type: 'snapshot', data: { simulator: { trucks: [null, { truckId: 'truck-001', speedKmh: null }] } } } as unknown as WebSocketMessage))
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [null, { truckId: 'truck-001', speedKmh: null }] } } as unknown as WebSocketMessage))
    expect(screen.getByTestId('truck-row-truck-001').textContent).toContain('Live speed unavailable')
    expect(screen.getByTestId('truck-row-truck-001').textContent).toContain('No average')
    fireEvent.click(screen.getByTestId('truck-row-truck-001'))
    expect(screen.getByTestId('truck-speed-chart')).toBeTruthy()
    expect(screen.getAllByText('No samples yet.')).toHaveLength(2)
  })

  it('shows one history point and delivery count for overlapping REST and live windows', () => {
    const speed: WindowResult = { sinkId: 'speedSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 3, values: { avgSpeedKmh: 35 } }
    const delivery: WindowResult = { ...speed, sinkId: 'delSink', values: { deliveries: 2 } }
    const state = { ...initialDashboardState, windows: [speed, delivery, { ...speed }, { ...delivery }] }
    const panel = renderPanel('truck-speeds', state)
    fireEvent.click(screen.getByTestId('truck-row-truck-001'))
    expect(screen.getByRole('img', { name: 'Completed speed-window averages, 1 samples in km/h' })).toBeTruthy()
    panel.unmount()
    renderPanel('deliveries', state)
    // One deduplicated row for truck-001 (REST + live copies of the same window), showing 2 deliveries.
    const rows = document.querySelectorAll('.an-list .an-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('2 deliveries')
  })

  it('deduplicates failed records by event and kind and opens the exact selected record', () => {
    const rejected: DeadLetter = { eventId: 'same-event', kind: 'REJECTED', reason: 'Invalid speed', partition: 1, offset: 10, eventTime: 1000 }
    const late: DeadLetter = { ...rejected, kind: 'LATE', reason: 'Past watermark', offset: 11, eventTime: 2000 }
    renderPanel('failed-records', { ...initialDashboardState, deadLetters: [rejected, late, { ...late }] })
    expect(screen.getAllByRole('button', { name: /LATE.*same-event/ })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: /LATE.*same-event/ }))
    expect(screen.getByText('Past watermark')).toBeTruthy()
    expect(screen.getByText('11')).toBeTruthy()
    expect(screen.queryByText('Invalid speed')).toBeNull()
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
