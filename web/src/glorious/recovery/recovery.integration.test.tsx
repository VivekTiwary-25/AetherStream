import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionInfo, SimulatorState, WebSocketMessage } from '../../api/types'
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
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.resetAllMocks(); socket.listener = null })

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

  it('retries failed history hydration and clears the error when the backend returns', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn()
      .mockRejectedValueOnce(new Error('service unavailable'))
      .mockResolvedValue(response([{ id: 1, event: 'WORKER_DEAD', at: 10, workerId: 'worker-1', executionId: execution.id }]))
    vi.stubGlobal('fetch', fetch)
    const { result } = renderHook(useRecoveryFeed)
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(result.current.historyError).toBe('service unavailable')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
      await Promise.resolve()
    })
    expect(result.current.historyError).toBeNull()
    expect(result.current.history.map((entry) => entry.id)).toEqual([1])
    vi.useRealTimers()
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
