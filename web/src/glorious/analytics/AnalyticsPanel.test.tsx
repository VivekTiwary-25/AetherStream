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

function renderPanel(id: 'deliveries' | 'alerts' | 'failed-records' | 'truck-speeds' | 'events-processed', state = initialDashboardState) {
  return render(<DashboardContext.Provider value={state}><AnalyticsPanel id={id} /></DashboardContext.Provider>)
}

describe('analytics live-data states and snapshot seeding', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); hooks.status = 'connected' })

  it('distinguishes disconnected empty states from an active execution with no data', () => {
    for (const id of ['deliveries', 'alerts', 'failed-records'] as const) {
      const { unmount } = renderPanel(id, { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'RUNNING' } as never })
      expect(screen.getByText(/No (deliveries counted|alerts|failed records) yet\./)).toBeTruthy()
      unmount()
    }
    const disconnected = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'disconnected', execution: { state: 'RUNNING' } as never })
    expect(screen.getByText('Disconnected — live data unavailable.')).toBeTruthy()
    disconnected.unmount()
    const stopped = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'STOPPED' } as never })
    expect(screen.getByText('No data yet — start the demo pipeline.')).toBeTruthy()
    stopped.unmount()
    const { unmount } = renderPanel('deliveries', { ...initialDashboardState, socketStatus: 'connected', execution: { state: 'RUNNING' } as never })
    expect(screen.getByText('No deliveries counted yet.')).toBeTruthy()
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
    expect(screen.getAllByText('Samples unavailable.')).toHaveLength(2)
  })

  it('shows one history point and delivery count for overlapping REST and live windows', () => {
    const speed: WindowResult = { sinkId: 'speedSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 3, values: { avgSpeedKmh: 35 } }
    const delivery: WindowResult = { ...speed, sinkId: 'delSink', values: { deliveries: 2 } }
    const state = { ...initialDashboardState, windows: [speed, delivery, { ...speed }, { ...delivery }] }
    const panel = renderPanel('truck-speeds', state)
    fireEvent.click(screen.getByTestId('truck-row-truck-001'))
    expect(screen.getByRole('img', { name: 'Average speed per completed window, 1 samples in km/h' })).toBeTruthy()
    panel.unmount()
    renderPanel('deliveries', state)
    // One deduplicated row for truck-001 (REST + live copies of the same window), showing 2 deliveries.
    const rows = document.querySelectorAll('.an-list .an-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('2 deliveries')
  })

  it('shows unavailable delivery counts instead of zero and does not chart partial fleet totals', () => {
    const row: WindowResult = { sinkId: 'delSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 2, values: { deliveries: 3 } }
    const state = { ...initialDashboardState, windows: [
      { ...row, windowStart: 60_000, windowEnd: 120_000, values: {} },
      row,
      { ...row, key: 'truck-002', values: null } as unknown as WindowResult,
      { ...row, key: 'truck-003', values: { deliveries: Infinity } },
      { ...row, key: 'truck-004', values: { deliveries: -1 } },
    ] }
    renderPanel('deliveries', state)
    expect(screen.getAllByText('Delivery count unavailable')).toHaveLength(4)
    expect(screen.queryByText('0 deliveries')).toBeNull()
    expect(screen.queryByText('3 deliveries')).toBeNull()
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('Samples unavailable.')).toBeTruthy()
  })

  it('keeps a measured zero delivery count and sums valid reported counts', () => {
    const row: WindowResult = { sinkId: 'delSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 2, values: { deliveries: 0 } }
    renderPanel('deliveries', { ...initialDashboardState, windows: [row, { ...row, key: 'truck-002', values: { deliveries: 3 } }] })
    expect(screen.getByText('0 deliveries')).toBeTruthy()
    expect(document.querySelector('.an-chart__bar title')?.textContent).toBe('3 deliveries')
  })

  it('retains missing completed-window averages as chart gaps', () => {
    const row: WindowResult = { sinkId: 'speedSink', key: 'truck-001', windowStart: 0, windowEnd: 60_000, eventCount: 2, values: { avgSpeedKmh: 30 } }
    renderPanel('truck-speeds', { ...initialDashboardState, windows: [
      { ...row, windowStart: 120_000, windowEnd: 180_000, values: { avgSpeedKmh: 40 } },
      { ...row, windowStart: 60_000, windowEnd: 120_000, values: {} },
      row,
    ] })
    fireEvent.click(screen.getByTestId('truck-row-truck-001'))
    const chart = screen.getByRole('img', { name: 'Average speed per completed window, 2 samples in km/h' })
    expect(chart.querySelectorAll('polyline')).toHaveLength(2)
    expect(chart.querySelectorAll('.an-chart__area')).toHaveLength(0)
  })

  it('counts overlapping REST and live alerts once in the list and minute chart', () => {
    const alert = { alertId: 'alert-1', key: 'truck-001', ruleId: 'speed', message: 'Speed limit exceeded', eventTime: 60_000 }
    renderPanel('alerts', { ...initialDashboardState, alerts: [alert, { ...alert }] })
    expect(screen.getAllByText('Speed limit exceeded')).toHaveLength(1)
    expect(document.querySelector('.an-chart__bar title')?.textContent).toBe('1 alerts/min')
  })

  it('expires partition estimates, retains chart history and requires a fresh frame after reconnect', () => {
    vi.useFakeTimers()
    const { rerender, unmount } = renderPanel('events-processed')
    const frame = { type: 'flow', data: { eventsPerSec: 10, rejectedPerSec: 0, latePerSec: 1, byPartition: { 0: 5 } } } as WebSocketMessage
    act(() => hooks.listener?.(frame))
    expect(screen.getByText('Estimated 5.00 events/s')).toBeTruthy()
    act(() => vi.advanceTimersByTime(5000))
    expect(screen.queryByText('Estimated 5.00 events/s')).toBeNull()
    expect(screen.getByText(/Live throughput unavailable/)).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Events processed, 1 samples in events/s' })).toBeTruthy()
    act(() => hooks.listener?.(frame))
    expect(screen.getByText('Estimated 5.00 events/s')).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Events processed, 2 samples in events/s' }).querySelectorAll('polyline')).toHaveLength(2)
    hooks.status = 'disconnected'
    rerender(<DashboardContext.Provider value={initialDashboardState}><AnalyticsPanel id="events-processed" /></DashboardContext.Provider>)
    expect(screen.getByText('Disconnected — live throughput unavailable.')).toBeTruthy()
    expect(screen.queryByText('Estimated 5.00 events/s')).toBeNull()
    hooks.status = 'connected'
    rerender(<DashboardContext.Provider value={initialDashboardState}><AnalyticsPanel id="events-processed" /></DashboardContext.Provider>)
    expect(screen.queryByText('Estimated 5.00 events/s')).toBeNull()
    act(() => hooks.listener?.(frame))
    expect(screen.getByText('Estimated 5.00 events/s')).toBeTruthy()
    act(() => hooks.listener?.({ type: 'snapshot', data: {} } as WebSocketMessage))
    expect(screen.queryByText('Estimated 5.00 events/s')).toBeNull()
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('accepts partial flow fields without crashing, coercing missing values or reviving old partitions', () => {
    renderPanel('events-processed')
    act(() => hooks.listener?.({ type: 'flow', data: { eventsPerSec: 12 } } as WebSocketMessage))
    expect(screen.getByRole('img', { name: 'Events processed, 1 samples in events/s' })).toBeTruthy()
    expect(screen.getByText('Partition rates unavailable.')).toBeTruthy()
    expect(screen.getAllByText('Samples unavailable.')).toHaveLength(2)
    act(() => hooks.listener?.({ type: 'flow', data: { eventsPerSec: null, rejectedPerSec: 0, latePerSec: Infinity, byPartition: { 0: 0, 1: null, 2: '4' } } } as WebSocketMessage))
    expect(screen.getByText('Estimated 0.00 events/s')).toBeTruthy()
    expect(screen.getAllByText('Rate unavailable')).toHaveLength(2)
    expect(screen.getByRole('img', { name: 'Rejected, 1 samples in records/s' })).toBeTruthy()
    expect(screen.queryByRole('img', { name: /Late,/ })).toBeNull()
    act(() => hooks.listener?.({ type: 'flow', data: { eventsPerSec: 14, byPartition: [] } } as WebSocketMessage))
    expect(screen.queryByText('Estimated 0.00 events/s')).toBeNull()
    expect(screen.getByText('Partition rates unavailable.')).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Events processed, 2 samples in events/s' }).querySelectorAll('polyline')).toHaveLength(2)
    act(() => hooks.listener?.({ type: 'flow', data: { byPartition: {} } } as WebSocketMessage))
    expect(screen.getByText('No partition rates reported.')).toBeTruthy()
    act(() => hooks.listener?.({ type: 'flow', data: null } as WebSocketMessage))
    expect(screen.getByText('Partition rates unavailable.')).toBeTruthy()
  })

  it('keeps gaps in live speed history across missing readings, expiry and disconnects', () => {
    vi.useFakeTimers()
    const { rerender } = renderPanel('truck-speeds')
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 30 }] } } as WebSocketMessage))
    fireEvent.click(screen.getByTestId('truck-row-truck-001'))
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: null }] } } as WebSocketMessage))
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 35 }] } } as WebSocketMessage))
    expect(screen.getByRole('img', { name: /Live readings since/ }).querySelectorAll('polyline')).toHaveLength(2)
    act(() => vi.advanceTimersByTime(5000))
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 40 }] } } as WebSocketMessage))
    expect(screen.getByRole('img', { name: /Live readings since/ }).querySelectorAll('polyline')).toHaveLength(3)
    hooks.status = 'disconnected'
    rerender(<DashboardContext.Provider value={initialDashboardState}><AnalyticsPanel id="truck-speeds" /></DashboardContext.Provider>)
    hooks.status = 'connected'
    rerender(<DashboardContext.Provider value={initialDashboardState}><AnalyticsPanel id="truck-speeds" /></DashboardContext.Provider>)
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 45 }] } } as WebSocketMessage))
    expect(screen.getByRole('img', { name: /Live readings since/ }).querySelectorAll('polyline')).toHaveLength(4)
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [] } } as unknown as WebSocketMessage))
    act(() => hooks.listener?.({ type: 'trucks', data: { trucks: [{ truckId: 'truck-001', speedKmh: 50 }] } } as WebSocketMessage))
    expect(screen.getByRole('img', { name: /Live readings since/ }).querySelectorAll('polyline')).toHaveLength(5)
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
