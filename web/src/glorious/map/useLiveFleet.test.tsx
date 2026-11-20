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
    expect(result.current.controlsFresh).toBe(false)
    expect(result.current.error).toBe('Simulator controls unavailable')
  })

  it('does not let truck-only frames make missing simulator controls look available', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Control API offline')))
    const { result } = renderHook(useLiveFleet)
    await act(async () => {})
    act(() => frame('trucks', [truck]))
    expect(result.current.trucks).toEqual([truck])
    expect(result.current.fresh).toBe(true)
    expect(result.current.controlsFresh).toBe(false)
    expect(result.current.error).toBe('Simulator controls unavailable')
  })

  it.each([
    { running: undefined, timeScale: 10 },
    { running: true, timeScale: -1 },
    { running: true, timeScale: null },
  ])('rejects unavailable controls rather than accepting %j', async (controls) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...state([truck]), ...controls } as unknown as SimulatorState)))
    const { result } = renderHook(useLiveFleet)
    await act(async () => {})
    expect(result.current.controlsFresh).toBe(false)
    expect(result.current.trucks).toEqual([])
    expect(result.current.error).toBe('The simulator sent data that could not be read')
  })

  it('retains valid reconnect controls when an older HTTP request fails', async () => {
    let reject!: (reason: Error) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((_, fail) => { reject = fail })))
    const { result } = renderHook(useLiveFleet)
    act(() => frame('snapshot', [truck]))
    await act(async () => { reject(new Error('Obsolete failure')) })
    expect(result.current.controlsFresh).toBe(true)
    expect(result.current.running).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('does not replace a newer unavailable-control snapshot with an older successful poll', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(state([truck]))))
    const { result } = renderHook(useLiveFleet)
    act(() => socket.listener?.({ type: 'snapshot', ts: 0, data: { simulator: { trucks: [truck] } } }))
    await act(async () => {})
    expect(result.current.trucks).toEqual([truck])
    expect(result.current.controlsFresh).toBe(false)
    expect(result.current.error).toBe('Simulator controls unavailable')
  })

  it('backs off failed HTTP polls, recovers and resets the next outage delay', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetch = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response(state([truck])))
      .mockRejectedValueOnce(new Error('offline again'))
      .mockResolvedValue(response(state([truck])))
    vi.stubGlobal('fetch', fetch)
    const { result, unmount } = renderHook(useLiveFleet)
    await act(async () => {})
    await act(async () => { await vi.advanceTimersByTimeAsync(4999) })
    expect(fetch).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(fetch).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(result.current.error).toBeNull()
    expect(result.current.trucks).toEqual([truck])
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(result.current.error).toBe('offline again')
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(fetch).toHaveBeenCalledTimes(5)
    expect(result.current.error).toBeNull()
    expect(warn).toHaveBeenCalledTimes(2)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
    warn.mockRestore()
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
