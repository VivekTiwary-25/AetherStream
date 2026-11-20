import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CheckpointInfo, ExecutionInfo, SimulatorState, WebSocketMessage } from '../../api/types'
import { getCheckpoints, getCurrentExecution, getSimulatorState } from '../../api/client'
import { TruckBreakdown } from './TruckBreakdown'
import { useRecoveryFeed } from './useRecoveryFeed'

const socket = vi.hoisted(() => ({ listener: null as ((message: WebSocketMessage) => void) | null }))
vi.mock('../../api/ws', () => ({
  useAetherMessages: (listener: (message: WebSocketMessage) => void) => {
    socket.listener = listener
    return 'connected'
  },
}))
vi.mock('../../api/client', () => ({
  getCurrentExecution: vi.fn(), getCheckpoints: vi.fn(), getSimulatorState: vi.fn(),
}))

const execution: ExecutionInfo = {
  id: 'execution-1', state: 'RUNNING', generation: 3, restoredFromCheckpoint: 7,
  lastCompletedCheckpoint: 8, pipelineId: 'pipeline-1', pipelineVersion: 1, createdAt: '2026-11-20T00:00:00Z',
}
const response = (data: unknown) => new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
const fleet = {
  trucks: [
    { truckId: 'truck-004', status: 'DRIVING', speedKmh: 30, x: 0, y: 0, heading: 0 },
    { truckId: 'truck-006', status: 'IDLE', speedKmh: 0, x: 0, y: 0, heading: 0 },
  ],
} as SimulatorState

beforeEach(() => {
  vi.mocked(getCurrentExecution).mockResolvedValue(execution)
  vi.mocked(getCheckpoints).mockResolvedValue([])
  vi.mocked(getSimulatorState).mockResolvedValue(fleet)
})
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.resetAllMocks(); vi.restoreAllMocks(); socket.listener = null })

describe('recovery feed cutover', () => {
  it('keeps a live failure received before REST hydration, with its original hold deadline', async () => {
    const dead = { id: 2, event: 'WORKER_RESTARTED', at: Date.now(), workerId: 'worker-2', executionId: execution.id, generation: 3, restoredFromCheckpoint: 7 }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([{ ...dead, id: 1, event: 'KILL_REQUESTED' }])))
    const { result } = renderHook(useRecoveryFeed)
    act(() => socket.listener!({ type: 'recovery', ts: dead.at, data: dead } as unknown as WebSocketMessage))
    const observedAt = result.current.history[0].observedAt
    await waitFor(() => expect(result.current.historyLoaded).toBe(true))
    expect(result.current.history.map((entry) => entry.event)).toEqual(['WORKER_RESTARTED', 'KILL_REQUESTED'])
    expect(result.current.history[0].observedAt).toBe(observedAt)
    expect(result.current.historyLoaded).toBe(true)
  })

  it('backs history off to the cap, resets after recovery, and cancels polling on unmount', async () => {
    vi.useFakeTimers()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetch = vi.fn()
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockResolvedValueOnce(response([{ id: 1, event: 'WORKER_DEAD', at: 10, workerId: 'worker-1', executionId: execution.id }]))
      .mockRejectedValueOnce(new Error('another outage'))
      .mockImplementation(async () => response([]))
    vi.stubGlobal('fetch', fetch)
    const { result, unmount } = renderHook(useRecoveryFeed)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.historyError).toBe('service unavailable')

    for (const [index, delay] of [5_000, 10_000, 15_000, 15_000].entries()) {
      await act(async () => { await vi.advanceTimersByTimeAsync(delay - 1) })
      expect(fetch).toHaveBeenCalledTimes(index + 1)
      await act(async () => { await vi.advanceTimersByTimeAsync(1) })
      expect(fetch).toHaveBeenCalledTimes(index + 2)
    }
    expect(warn).toHaveBeenCalledTimes(1)
    expect(result.current.historyError).toBeNull()
    expect(result.current.history.map((entry) => entry.id)).toEqual([1])
    expect(getCurrentExecution).toHaveBeenCalledTimes(10)

    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(result.current.historyError).toBe('another outage')
    expect(warn).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(4_999) })
    expect(fetch).toHaveBeenCalledTimes(6)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(fetch).toHaveBeenCalledTimes(7)
    expect(result.current.historyError).toBeNull()
    // Empty REST hydration must not remove facts already merged.
    expect(result.current.history.map((entry) => entry.id)).toEqual([1])

    const signal = (fetch.mock.calls[0][1] as RequestInit).signal!
    unmount()
    expect(signal.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(fetch).toHaveBeenCalledTimes(7)
    expect(getCurrentExecution).toHaveBeenCalledTimes(12)
  })

  it('polls execution independently of slow history and keeps history moving during slow execution', async () => {
    vi.useFakeTimers()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const history = deferred<Response>()
    const current = deferred<ExecutionInfo | null>()
    const fetch = vi.fn()
      .mockReturnValueOnce(history.promise)
      .mockImplementation(async () => response([]))
    vi.stubGlobal('fetch', fetch)
    vi.mocked(getCurrentExecution)
      .mockRejectedValueOnce(new Error('execution unavailable'))
      .mockRejectedValueOnce(new Error('execution unavailable'))
      .mockRejectedValueOnce(new Error('execution unavailable'))
      .mockResolvedValueOnce(execution)
      .mockReturnValueOnce(current.promise)
    const { result, unmount } = renderHook(useRecoveryFeed)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.executionError).toBe('execution unavailable')
    for (const [index, delay] of [5_000, 10_000, 15_000].entries()) {
      await act(async () => { await vi.advanceTimersByTimeAsync(delay - 1) })
      expect(getCurrentExecution).toHaveBeenCalledTimes(index + 1)
      await act(async () => { await vi.advanceTimersByTimeAsync(1) })
      expect(getCurrentExecution).toHaveBeenCalledTimes(index + 2)
    }
    expect(result.current.execution).toEqual(execution)
    expect(result.current.executionError).toBeNull()
    expect(result.current.historyLoaded).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalledTimes(1)

    await act(async () => { history.resolve(response([])); await vi.advanceTimersByTimeAsync(0) })
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(getCurrentExecution).toHaveBeenCalledTimes(5)
    expect(result.current.historyLoaded).toBe(true)
    unmount()
    await act(async () => {
      current.reject(new Error('late execution failure'))
      await vi.advanceTimersByTimeAsync(30_000)
    })
    expect(warn).toHaveBeenCalledTimes(1)
    expect(getCurrentExecution).toHaveBeenCalledTimes(5)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('backs checkpoint polling off without overlapping requests or bypassing retries for live events', async () => {
    vi.useFakeTimers()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(async () => response([])))
    const checkpoints = deferred<CheckpointInfo[]>()
    const late = deferred<CheckpointInfo[]>()
    const completed: CheckpointInfo = {
      checkpointId: 9, generation: 3, state: 'COMPLETED', durationMs: 40,
      completedAt: '2026-11-20T00:01:00Z', sourceOffsets: {},
    }
    vi.mocked(getCheckpoints)
      .mockRejectedValueOnce(new Error('checkpoints unavailable'))
      .mockRejectedValueOnce(new Error('checkpoints unavailable'))
      .mockRejectedValueOnce(new Error('checkpoints unavailable'))
      .mockResolvedValueOnce([completed, { ...completed, checkpointId: 10, state: 'IN_PROGRESS' }])
      .mockReturnValueOnce(checkpoints.promise)
      .mockReturnValueOnce(late.promise)
    const { result, unmount } = renderHook(useRecoveryFeed)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.checkpointError).toBe('checkpoints unavailable')
    for (const [index, delay] of [5_000, 10_000, 15_000].entries()) {
      act(() => socket.listener!({
        type: 'recovery', ts: Date.now(),
        data: { id: index + 1, event: 'CHECKPOINT_COMPLETED', at: Date.now(), executionId: execution.id },
      } as unknown as WebSocketMessage))
      await act(async () => { await vi.advanceTimersByTimeAsync(delay - 1) })
      expect(getCheckpoints).toHaveBeenCalledTimes(index + 1)
      await act(async () => { await vi.advanceTimersByTimeAsync(1) })
      expect(getCheckpoints).toHaveBeenCalledTimes(index + 2)
    }
    expect(warn).toHaveBeenCalledTimes(1)
    expect(result.current.checkpointError).toBeNull()
    expect(result.current.checkpointsLoaded).toBe(true)
    expect(result.current.checkpoints).toEqual([completed])

    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(getCheckpoints).toHaveBeenCalledTimes(5)
    act(() => socket.listener!({
      type: 'recovery', ts: Date.now(),
      data: { id: 4, event: 'CHECKPOINT_COMPLETED', at: Date.now(), executionId: execution.id },
    } as unknown as WebSocketMessage))
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(getCheckpoints).toHaveBeenCalledTimes(5)
    await act(async () => { checkpoints.resolve([completed]); await vi.advanceTimersByTimeAsync(0) })
    await act(async () => { await vi.advanceTimersByTimeAsync(4_999) })
    expect(getCheckpoints).toHaveBeenCalledTimes(5)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(getCheckpoints).toHaveBeenCalledTimes(6)
    unmount()
    await act(async () => { late.reject(new Error('late checkpoint failure')); await vi.advanceTimersByTimeAsync(30_000) })
    expect(getCheckpoints).toHaveBeenCalledTimes(6)
    expect(warn).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('serializes checkpoint requests across execution changes and ignores stale results', async () => {
    vi.useFakeTimers()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const history = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(history.promise))
    const old = deferred<CheckpointInfo[]>()
    const current = deferred<CheckpointInfo[]>()
    vi.mocked(getCheckpoints).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const { result, unmount } = renderHook(useRecoveryFeed)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    act(() => socket.listener!({
      type: 'execution', ts: Date.now(), data: { ...execution, id: 'execution-2' },
    }))
    expect(getCheckpoints).toHaveBeenCalledTimes(1)
    await act(async () => { old.resolve([]); await vi.advanceTimersByTimeAsync(0) })
    expect(getCheckpoints).toHaveBeenCalledTimes(2)
    expect(getCheckpoints).toHaveBeenLastCalledWith('execution-2')
    expect(result.current.checkpointsLoaded).toBe(false)
    await act(async () => { current.resolve([]); await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.checkpointsLoaded).toBe(true)
    unmount()
    await act(async () => { history.reject(new Error('late history failure')); await vi.advanceTimersByTimeAsync(30_000) })
    expect(warn).not.toHaveBeenCalled()
    expect(getCheckpoints).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not replace a live execution with a stale REST result and shows only completed checkpoints', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])))
    vi.mocked(getCheckpoints).mockResolvedValue([
      { checkpointId: 9, generation: 4, state: 'COMPLETED', durationMs: 40, completedAt: '2026-11-20T00:01:00Z', sourceOffsets: { '1': 37 } },
      { checkpointId: 10, generation: 4, state: 'IN_PROGRESS', durationMs: 0, completedAt: null, sourceOffsets: {} },
    ])
    const { result } = renderHook(useRecoveryFeed)
    act(() => socket.listener!({ type: 'execution', ts: Date.now(), data: { ...execution, id: 'execution-2', generation: 4 } }))
    await waitFor(() => expect(result.current.checkpointsLoaded).toBe(true))
    expect(result.current.execution?.id).toBe('execution-2')
    expect(result.current.checkpoints.map((checkpoint) => checkpoint.checkpointId)).toEqual([9])
  })
})

describe('real truck breakdown form', () => {
  it('submits the selected fleet ID and reports the server outcome without inventing a stopped state', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ ok: true })).mockResolvedValueOnce(new Response(JSON.stringify({ message: 'Simulator unavailable' }), { status: 503 }))
    vi.stubGlobal('fetch', fetch)
    render(<TruckBreakdown />)
    await screen.findByRole('option', { name: 'truck-006 · IDLE' })
    fireEvent.change(screen.getByLabelText('Truck'), { target: { value: 'truck-006' } })
    fireEvent.click(screen.getByRole('button', { name: 'Simulate breakdown' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await screen.findByText('Breakdown accepted for truck-006. Watch its live status on the map.')
    expect(fetch).toHaveBeenCalledWith('/api/simulator/scenario', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'breakdown', truckId: 'truck-006' }),
    })
    expect(screen.getByRole('option', { name: 'truck-006 · IDLE' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Simulate breakdown' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    await screen.findByRole('alert')
    expect(screen.queryByText(/Breakdown accepted/)).toBeNull()
  })

  it('never offers a made-up truck when the simulator fleet is empty', async () => {
    vi.mocked(getSimulatorState).mockResolvedValue({ ...fleet, trucks: [] })
    render(<TruckBreakdown />)
    await screen.findByText('No trucks reported by the simulator.')
    expect((screen.getByRole('button', { name: 'Simulate breakdown' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
