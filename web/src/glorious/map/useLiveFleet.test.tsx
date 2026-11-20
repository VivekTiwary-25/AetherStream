import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SimulatorState, SimulatorTruck, WebSocketMessage } from '../../api/types'
import { useLiveFleet } from './useLiveFleet'

const socket = vi.hoisted(() => ({ listener: null as ((message: WebSocketMessage) => void) | null }))
vi.mock('../../api/ws', () => ({ useAetherMessages: (listener: (message: WebSocketMessage) => void) => { socket.listener = listener; return 'connected' } }))

const truck: SimulatorTruck = { truckId: 'truck-001', speedKmh: 35, status: 'DRIVING', x: 0, y: 0, heading: 0 }
const state = (trucks: SimulatorTruck[]): SimulatorState => ({ trucks, running: true, timeScale: 10, simTime: 0, emitted: 0, stops: [], depot: { x: 0, y: 0 } })
const response = (data: SimulatorState) => ({ ok: true, json: async () => data }) as Response

function frame(type: 'snapshot' | 'trucks', trucks: SimulatorTruck[]) {
  socket.listener?.({ type, ts: Date.now(), data: type === 'snapshot' ? { simulator: state(trucks) } : { trucks } })
}

describe('live fleet telemetry and cleanup', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
  })
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); socket.listener = null })

  it('excludes null and incomplete trucks from HTTP, snapshots and live frames before map rendering', async () => {
    const malformed = [null, { truckId: 'truck-002', speedKmh: null, status: 'DRIVING' }, { truckId: 'truck-003', speedKmh: 10, status: null }, truck] as unknown as SimulatorTruck[]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(state(malformed))))
    const { result } = renderHook(useLiveFleet)
    await act(async () => {})
    expect(result.current.trucks).toEqual([truck])
    act(() => frame('snapshot', malformed))
    expect(result.current.trucks).toEqual([truck])
    act(() => frame('trucks', malformed))
    expect(result.current.trucks).toEqual([truck])
  })

  it('does not overwrite newer socket trucks with a poll started in the same millisecond', async () => {
    let resolve!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
    const { result } = renderHook(useLiveFleet)
    const latest = { ...truck, speedKmh: 0, status: 'STOPPED' }
    act(() => frame('trucks', [latest]))
    await act(async () => { resolve(response(state([truck]))) })
    expect(result.current.trucks).toEqual([latest])
    expect(result.current.timeScale).toBe(10)
  })

  it('does not restore obsolete running or clock-scale state after a reconnect snapshot', async () => {
    let resolve!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
    const { result } = renderHook(useLiveFleet)
    act(() => socket.listener?.({ type: 'snapshot', ts: 0, data: { simulator: { ...state([truck]), running: false, timeScale: 2 } } }))
    await act(async () => { resolve(response(state([truck]))) })
    expect(result.current.running).toBe(false)
    expect(result.current.timeScale).toBe(2)
  })

  it('freezes stale telemetry and recovers when a new socket frame arrives', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Simulator unavailable')))
    const { result } = renderHook(useLiveFleet)
    await act(async () => {})
    act(() => frame('snapshot', [truck]))
    expect(result.current.fresh).toBe(true)
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(result.current.fresh).toBe(false)
    act(() => frame('trucks', [{ ...truck, speedKmh: 0, status: 'STOPPED' }]))
    expect(result.current.fresh).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('aborts an in-flight poll and removes both timers on unmount', async () => {
    let resolve!: (value: Response) => void
    const fetch = vi.fn<(url: string, options: RequestInit) => Promise<Response>>(() => new Promise<Response>((done) => { resolve = done }))
    vi.stubGlobal('fetch', fetch)
    const { result, unmount } = renderHook(useLiveFleet)
    const initial = result.current
    const signal = fetch.mock.calls[0][1].signal as AbortSignal
    unmount()
    expect(signal.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    await act(async () => { resolve(response(state([truck]))) })
    expect(result.current).toBe(initial)
  })
})
