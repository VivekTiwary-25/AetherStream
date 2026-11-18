import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { dashboardReducer, DashboardContext, initialDashboardState } from '../state/store'
import { ExecutionBanner, WorkersPanel } from '../panels/LivePanels'
import type { ExecutionInfo, SnapshotMessage, WebSocketMessage, WorkerInfo } from '../../api/types'

afterEach(cleanup)

const sampleSnapshot: SnapshotMessage = {
  execution: { id: 'exec-1', pipelineId: 'logistics-demo', pipelineVersion: 1, state: 'RUNNING', generation: 3, restoredFromCheckpoint: 12, lastCompletedCheckpoint: 15, createdAt: '2026-11-19T09:00:00Z' },
  workers: [],
  summary: { executionId: 'exec-1', windowsWritten: 4812, alerts: 2, rejected: 1, late: 0, deliveriesLastMinute: 8, avgSpeedLastMinute: 34.2, eventsPerSec: 120.5 },
  simulator: { running: true, timeScale: 10, simTime: 1795251660000, trucks: [], stops: [], depot: { x: 1000, y: 1000 }, emitted: 12345 },
}

const worker = (workerId: string, alive: boolean, backpressured: boolean): WorkerInfo => ({
  workerId, alive, state: 'RUNNING', partitions: [0, 3], sourceQueue: { size: 950, capacity: 1000 }, sinkQueue: { size: 0, capacity: 1000 }, backpressured, recordsPerSec: 41.5, lastHeartbeatMs: 1795251600123,
})

describe('dashboard reducer', () => {
  it('applies every contract WebSocket message and retains only 200 windows', () => {
    let state = dashboardReducer(initialDashboardState, { type: 'message', message: { type: 'snapshot', ts: 1, data: sampleSnapshot } })
    expect(state.execution?.id).toBe('exec-1')
    expect(state.simTime).toBe(sampleSnapshot.simulator.simTime)
    const updates: WebSocketMessage[] = [
      { type: 'trucks', ts: 2, data: { simTime: 2000, trucks: [] } },
      { type: 'flow', ts: 3, data: { eventsPerSec: 4, byPartition: { '0': 4 }, rejectedPerSec: 0, latePerSec: 0 } },
      { type: 'windows', ts: 4, data: [{ sinkId: 'speedSink', key: 'truck-001', windowStart: 0, windowEnd: 60000, values: { avgSpeedKmh: 31 }, eventCount: 10 }] },
      { type: 'alerts', ts: 5, data: [{ alertId: 'stuck|truck-007|0', key: 'truck-007', ruleId: 'stuck', message: 'truck-007 has not moved for 10 min', eventTime: 600000 }] },
      { type: 'deadLetters', ts: 6, data: [{ eventId: 'bad-1', kind: 'REJECTED', reason: 'unparseable JSON', partition: 0, offset: 5, eventTime: 1000 }] },
      { type: 'workers', ts: 7, data: [worker('w-1', true, true)] },
      { type: 'execution', ts: 8, data: { ...sampleSnapshot.execution!, event: 'RECOVERING', checkpointId: 12 } },
    ]
    updates.forEach((message) => { state = dashboardReducer(state, { type: 'message', message }) })
    expect(state.simTime).toBe(2000)
    expect(state.flow?.eventsPerSec).toBe(4)
    expect(state.windows).toHaveLength(1)
    expect(state.alerts[0]?.key).toBe('truck-007')
    expect(state.deadLetters[0]?.kind).toBe('REJECTED')
    expect(state.workers[0]?.workerId).toBe('w-1')
    expect(state.lastExecutionEvent?.event).toBe('RECOVERING')
    const full = { ...state, windows: Array.from({ length: 200 }, (_, index) => ({ sinkId: 'speedSink', key: String(index), windowStart: index, windowEnd: index + 1, values: {}, eventCount: 1 })) }
    const bounded = dashboardReducer(full, { type: 'message', message: updates[2]! })
    expect(bounded.windows).toHaveLength(200)
  })
})

describe('live panels', () => {
  it('labels backpressured and dead workers', () => {
    const value = { ...initialDashboardState, workers: [worker('w-hot', true, true), worker('w-dead', false, false)] }
    render(<DashboardContext.Provider value={value}><WorkersPanel /></DashboardContext.Provider>)
    expect(screen.getAllByText(/backpressured/i).length).toBeGreaterThan(0)
    expect(screen.getByText('Dead')).toBeTruthy()
  })

  it('shows the checkpoint used for recovery', () => {
    const execution: ExecutionInfo = { id: 'exec-1', pipelineId: 'logistics-demo', pipelineVersion: 1, state: 'RECOVERING', generation: 4, restoredFromCheckpoint: 12, lastCompletedCheckpoint: 12, createdAt: '2026-11-19T09:00:00Z' }
    const value = { ...initialDashboardState, execution, lastExecutionEvent: { ...execution, event: 'RECOVERING' as const, checkpointId: 12 } }
    render(<DashboardContext.Provider value={value}><ExecutionBanner /></DashboardContext.Provider>)
    expect(screen.getByText('Recovering from checkpoint #12')).toBeTruthy()
  })
})
