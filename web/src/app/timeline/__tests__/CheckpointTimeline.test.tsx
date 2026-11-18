import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CheckpointTimeline } from '../CheckpointTimeline'


vi.mock('../../../api/client', () => ({
  getCheckpoints: vi.fn().mockResolvedValue([]),
  getCurrentExecution: vi.fn().mockResolvedValue(null),
}))

// One stable socket state: a fresh lastMessage object per render would re-trigger the component's effect forever.
const socketState = vi.hoisted(() => ({
  lastMessage: {
    type: 'execution',
    ts: 1_800_000_000_000,
    data: {
      id: 'exec-1', pipelineId: 'demo', pipelineVersion: 1, state: 'RECOVERING', generation: 4,
      restoredFromCheckpoint: 12, lastCompletedCheckpoint: 12, createdAt: '2026-11-19T09:00:00Z',
      event: 'RECOVERING', checkpointId: 12,
    },
  },
}))

vi.mock('../../../api/ws', () => ({
  useAetherSocket: () => socketState,
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('CheckpointTimeline', () => {
  it('renders the checkpoint used during recovery', () => {
    render(<CheckpointTimeline />)
    expect(screen.getAllByText(/Recovering from checkpoint #12/).length).toBeGreaterThan(0)
  })
})
