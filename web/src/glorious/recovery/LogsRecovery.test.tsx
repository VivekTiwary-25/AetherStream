import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkerInfo } from '../../api/types'
import { DashboardContext, initialDashboardState } from '../../app/state/store'
import { LogsRecovery } from './LogsRecovery'

vi.mock('./useRecoveryFeed', () => ({
  useRecoveryFeed: () => ({
    history: [], historyLoaded: true, historyError: null, execution: null,
    executionError: null, status: 'connected', checkpoints: [],
    checkpointsLoaded: true, checkpointError: null,
  }),
}))

afterEach(cleanup)

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
