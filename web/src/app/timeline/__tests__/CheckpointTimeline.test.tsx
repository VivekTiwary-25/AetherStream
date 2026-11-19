import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionEvent, WebSocketMessage } from '../../../api/types'
import { CheckpointTimeline } from '../CheckpointTimeline'


vi.mock('../../../api/client', () => ({
  getCheckpoints: vi.fn().mockResolvedValue([]),
  getCurrentExecution: vi.fn().mockResolvedValue(null),
}))

// One stable socket state: a fresh lastMessage object per render would re-trigger the component's effect forever.
const socketState = vi.hoisted<{ lastMessage: WebSocketMessage<ExecutionEvent> }>(() => ({
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

const recoveringMessage = socketState.lastMessage

beforeEach(() => {
  socketState.lastMessage = recoveringMessage
})

// The timeline subscribes to every frame; replay the current socketState frame to the handler once per new frame.
vi.mock('../../../api/ws', async () => {
  const { useEffect } = await import('react')
  return {
    useAetherMessages: (handler: (message: WebSocketMessage) => void) => {
      const message = socketState.lastMessage
      useEffect(() => { handler(message) }, [message]) // eslint-disable-line react-hooks/exhaustive-deps
      return 'connected'
    },
  }
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('CheckpointTimeline', () => {
  it('renders the checkpoint used during recovery', () => {
    render(<CheckpointTimeline />)
    expect(screen.getAllByText(/Recovering from checkpoint #12/).length).toBeGreaterThan(0)
  })

  it.each([true, false])('renders the server duration with prior RECOVERING = %s', (withRecoveryEntry) => {
    const recoveredMessage: WebSocketMessage<ExecutionEvent> = {
      type: 'execution',
      ts: recoveringMessage.ts + 1_000,
      data: { ...recoveringMessage.data, state: 'RUNNING', event: 'RECOVERED', durationMs: 6_000 },
    }
    if (!withRecoveryEntry) socketState.lastMessage = recoveredMessage
    const view = render(<CheckpointTimeline />)
    if (withRecoveryEntry) {
      socketState.lastMessage = recoveredMessage
      view.rerender(<CheckpointTimeline />)
    }

    expect(screen.getByTitle('Recovered in 6.0 s').textContent).toBe('✓ Recovered in 6.0 s')
    expect(screen.getByText('Recovered in 6.0 s').textContent).toBe('Recovered in 6.0 s')
  })

  it('derives the recovery band and marker from the state change when the server sends no RECOVERING/RECOVERED (rehearsal-1 #11)', () => {
    const ts = recoveringMessage.ts
    const running = { ...recoveringMessage.data, state: 'RUNNING' as const, generation: 1, restoredFromCheckpoint: null, lastCompletedCheckpoint: 18 }
    const frames: WebSocketMessage<ExecutionEvent>[] = [
      { type: 'execution', ts, data: { ...running, event: 'CHECKPOINT_COMPLETED', checkpointId: 18 } },
      // What the rehearsal saw: no WORKER_DEAD was observed, so the server labelled the generation change REBALANCED.
      { type: 'execution', ts: ts + 1_000, data: { ...running, state: 'RECOVERING', generation: 2, event: 'REBALANCED' as ExecutionEvent['event'] } },
      { type: 'execution', ts: ts + 13_000, data: { ...running, generation: 2, restoredFromCheckpoint: 18, lastCompletedCheckpoint: 19, event: 'CHECKPOINT_COMPLETED', checkpointId: 19 } },
    ]
    socketState.lastMessage = frames[0]!
    const view = render(<CheckpointTimeline />)
    for (const frame of frames.slice(1)) {
      socketState.lastMessage = frame
      view.rerender(<CheckpointTimeline />)
    }
    expect(screen.getByTitle('Recovering from checkpoint #18 (generation 1 → 2)')).toBeTruthy()
    expect(screen.getByTitle('Recovered').textContent).toBe('✓ Recovered')
    expect(screen.getByText('Recovered from checkpoint #18')).toBeTruthy()
  })
})
