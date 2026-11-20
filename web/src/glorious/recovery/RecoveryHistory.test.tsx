import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { RecoveryHistory } from './RecoveryHistory'
import { mergeHistory, type RecoveryEntry } from './model'

afterEach(cleanup)
const base: RecoveryEntry = {
  id: 1, event: 'WORKER_DEAD', at: 1_700_000_000_000,
  workerId: 'worker-2', executionId: 'execution-1', generation: 3,
  restoredFromCheckpoint: 42, reason: 'Heartbeat timeout', actor: 'coordinator',
}

describe('persistent activity rendering', () => {
  it('shows newest first with the actual restored checkpoint, generation, pause and reassignment', () => {
    const history = mergeHistory([], [
      base,
      { ...base, id: 2, event: 'RECOVERING', at: base.at + 100 },
      { ...base, id: 3, event: 'RECOVERED', at: base.at + 1_600, reason: null,
        partitionChanges: [{ partition: 1, oldOwner: 'worker-2', newOwner: 'worker-1' }] },
    ])
    render(<RecoveryHistory entries={history} loaded />)
    const rows = within(screen.getAllByRole('list')[0]).getAllByRole('listitem')
    expect(within(rows[0]).getByText('Recovery finished')).toBeTruthy()
    expect(within(rows[0]).getByText('Restored from checkpoint #42 · generation 3')).toBeTruthy()
    expect(screen.getByText('Processing paused for 1.5 s during recovery.')).toBeTruthy()
    expect(screen.getByText('P1: worker-2 → worker-1')).toBeTruthy()
    expect(screen.getAllByText('Actor: coordinator')).toHaveLength(3)
    expect(screen.getByText('Worker died')).toBeTruthy()
  })

  it('does not invent timing or a checkpoint when audit metadata is missing', () => {
    render(<RecoveryHistory entries={[{ ...base, event: 'RECOVERED', restoredFromCheckpoint: null }]} loaded />)
    expect(screen.getByText('Pause length unknown (timing was not fully recorded).')).toBeTruthy()
    expect(screen.getByText(/Checkpoint used: not recorded/)).toBeTruthy()
  })

  it('renders accepted kills as unconfirmed, completed checkpoint IDs, and an honest empty state', () => {
    const { rerender } = render(<RecoveryHistory entries={[]} loaded />)
    expect(screen.getByText('No recovery activity yet.')).toBeTruthy()
    rerender(<RecoveryHistory loaded entries={[
      { ...base, event: 'KILL_REQUESTED' },
      { ...base, id: 2, event: 'CHECKPOINT_COMPLETED', checkpointId: 43, durationMs: 120 },
    ]} />)
    expect(screen.getByText('Kill requested (not yet confirmed)')).toBeTruthy()
    expect(screen.getByText('Checkpoint #43 · 120 ms')).toBeTruthy()
  })
})
