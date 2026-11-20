import { act, cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkerInfo } from '../../api/types'
import { DashboardContext, initialDashboardState } from '../../app/state/store'
import { LogsRecovery } from './LogsRecovery'
import { DEAD_HOLD_MS, REJOINED_HOLD_MS, mergeHistory } from './model'

const baseFeed = {
  history: [] as unknown[], historyLoaded: true, historyError: null as string | null, execution: null as unknown,
  executionError: null, status: 'connected', checkpoints: [] as unknown[],
  checkpointsLoaded: true, checkpointError: null,
}
const feed = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('./useRecoveryFeed', () => ({ useRecoveryFeed: () => feed.current }))

beforeEach(() => { feed.current = baseFeed })
afterEach(() => { cleanup(); vi.useRealTimers() })

const worker: WorkerInfo = {
  workerId: 'worker-2', alive: true, state: 'RUNNING', partitions: [],
  recordsPerSec: 12, sourceQueue: { size: 0, capacity: 64 },
  sinkQueue: { size: 0, capacity: 64 }, backpressured: false, lastHeartbeatMs: 0,
}

function view(current: WorkerInfo) {
  return <DashboardContext.Provider value={{ ...initialDashboardState, workers: [current] }}>
    <LogsRecovery />
  </DashboardContext.Provider>
}

describe('re-registration death display', () => {
  it('shows an already-alive worker Dead until the event deadline, then Rejoined then Running', () => {
    vi.useFakeTimers()
    const at = 1795152385334
    vi.setSystemTime(at + 2_000)
    const dead = {
      id: 86, event: 'WORKER_DEAD', at, workerId: 'worker-2',
      executionId: 'exec-20261120-051856-8aa5', generation: null,
      restoredFromCheckpoint: null, actor: 'coordinator', reason: 're-registered',
    }
    feed.current = {
      ...baseFeed,
      execution: { id: dead.executionId, state: 'RUNNING', generation: 3, restoredFromCheckpoint: 44 },
      history: mergeHistory([], [
        { ...dead, observedAt: at + 2_000 },
        { ...dead, id: 87, event: 'WORKER_RESTARTED', at: at + 8, observedAt: at + 2_000 },
        { ...dead, id: 88, event: 'RECOVERING', generation: 2, restoredFromCheckpoint: 44 },
        { ...dead, id: 89, event: 'RECOVERED', at: at + 1_108, generation: 3, restoredFromCheckpoint: 44, durationMs: 1_108 },
      ]),
    }
    const alive = { ...worker, lastHeartbeatMs: at + 1_500 }
    render(<DashboardContext.Provider value={{ ...initialDashboardState, workers: [
      alive, { ...alive, workerId: 'worker-1' },
    ] }}><LogsRecovery /></DashboardContext.Provider>)
    const failed = within(screen.getByTestId('worker-card-worker-2'))
    const survivor = within(screen.getByTestId('worker-card-worker-1'))
    expect(failed.getByText('Dead')).toBeTruthy()
    expect(survivor.getByText('Running')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(DEAD_HOLD_MS - 2_000 - 250) })
    expect(failed.getByText('Dead')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(250) })
    expect(failed.getByText('Rejoined')).toBeTruthy()
    expect(survivor.getByText('Running')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(REJOINED_HOLD_MS) })
    expect(failed.getByText('Running')).toBeTruthy()
  })
})

describe('worker partition availability', () => {
  it('keeps partial worker data visible without claiming unknown ownership is unassigned', () => {
    const { rerender } = render(view({ ...worker, partitions: undefined } as unknown as WorkerInfo))
    const ownership = within(screen.getByRole('region', { name: 'Partition ownership' }))
    expect(screen.getByText('Partition data unavailable')).toBeTruthy()
    expect(within(ownership.getByTestId('partition-chip-0')).getByText('Unavailable')).toBeTruthy()
    expect(screen.getByText('Last heartbeat: Unavailable')).toBeTruthy()

    rerender(view({ ...worker, partitions: null } as unknown as WorkerInfo))
    expect(screen.getByText('Partition data unavailable')).toBeTruthy()

    rerender(view(worker))
    expect(screen.getByText('No assigned partitions')).toBeTruthy()
    expect(within(ownership.getByTestId('partition-chip-0')).getByText('Unassigned')).toBeTruthy()

    rerender(view({ ...worker, partitions: [0] }))
    expect(within(ownership.getByTestId('partition-chip-0')).getByText('worker-2')).toBeTruthy()
  })
})

describe('fake audit', () => {
  const run = { id: 'execution-1', state: 'RUNNING', generation: 2, restoredFromCheckpoint: null }
  const recovering = { id: 1, event: 'RECOVERING', at: Date.parse('2026-11-20T10:00:00Z'), workerId: 'worker-2', executionId: 'execution-1', generation: 2, restoredFromCheckpoint: 4 }

  it('only counts a live pause while the server says the run is recovering', () => {
    feed.current = { ...baseFeed, history: [recovering], execution: { ...run, state: 'RECOVERING' } }
    const { rerender } = render(view(worker))
    expect(screen.getByText(/Processing paused for .* \(in progress\)/)).toBeTruthy()
    feed.current = { ...baseFeed, history: [recovering], execution: run }
    rerender(view(worker))
    expect(screen.queryByText(/Processing paused for .* \(in progress\)/)).toBeNull()
    expect(screen.getByText(/Recovery started .*; its end has not been recorded yet\./)).toBeTruthy()
    feed.current = { ...baseFeed, status: 'disconnected', history: [recovering], execution: { ...run, state: 'RECOVERING' } }
    rerender(view(worker))
    expect(screen.getByText(/its end has not been recorded yet \(connection unavailable\)/)).toBeTruthy()
  })
  it('closes an unended planned rebalance banner when execution leaves RECOVERING', () => {
    feed.current = {
      ...baseFeed,
      execution: run,
      history: [{ ...recovering, reason: 'rebalance' }],
    }
    render(view(worker))
    expect(screen.getByText(/Planned rebalance started .*execution has left RECOVERING, but no completion event was recorded/)).toBeTruthy()
    expect(screen.queryByText(/Processing paused for .* \(in progress\)/)).toBeNull()
  })


  it('shows partitions the server reports beyond the configured six', () => {
    render(view({ ...worker, partitions: [7] }))
    const ownership = within(screen.getByRole('region', { name: 'Partition ownership' }))
    expect(within(ownership.getByTestId('partition-chip-7')).getByText('worker-2')).toBeTruthy()
  })

  it('says a missing checkpoint duration is not recorded instead of "Unavailable ms"', () => {
    feed.current = { ...baseFeed, execution: run, checkpoints: [{ checkpointId: 3, state: 'COMPLETED', generation: 2, durationMs: null, completedAt: null, sourceOffsets: {} }] }
    render(view(worker))
    expect(screen.queryByText(/Unavailable ms/)).toBeNull()
    expect(screen.getAllByText('Not recorded').length).toBe(3)
  })

  it('shows an honest message, never rows, when /api/recovery/history fails', () => {
    feed.current = { ...baseFeed, historyLoaded: false, historyError: 'Request failed (HTTP 404)' }
    render(view(worker))
    expect(screen.getByText('Recovery history unavailable: Request failed (HTTP 404)')).toBeTruthy()
    expect(screen.getByText('Recovery history could not be loaded.')).toBeTruthy()
    expect(screen.queryByRole('listitem')).toBeNull()
  })
})
