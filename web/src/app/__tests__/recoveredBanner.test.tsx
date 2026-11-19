import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionInfo, WebSocketMessage, WorkerInfo } from '../../api/types'
import { ExecutionBanner, WorkersPanel } from '../panels/LivePanels'
import { KILL_MARK_MAX_MS, RECOVERED_SHOW_MS, RECOVERY_HOLD_MS } from '../state/executionStatus'
import { DashboardContext, dashboardReducer, initialDashboardState, type DashboardState } from '../state/store'
import { spacedLabels } from '../timeline/timelineModel'

beforeEach(() => {
  // Reduced motion: jsdom has no Web Animations API, and the banner skips its checkpoint pulse then.
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener: () => undefined, removeEventListener: () => undefined }))
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const base: ExecutionInfo = { id: 'exec-1', pipelineId: 'logistics-demo', pipelineVersion: 1, state: 'RUNNING', generation: 1, restoredFromCheckpoint: null, lastCompletedCheckpoint: 18, createdAt: '2026-11-19T09:00:00Z' }

const execution = (data: Record<string, unknown>, ts: number) => ({ type: 'message' as const, at: ts, message: { type: 'execution', ts, data } as unknown as WebSocketMessage })

function banner(value: DashboardState) {
  return <DashboardContext.Provider value={value}><ExecutionBanner /></DashboardContext.Provider>
}

describe('Recovered banner after a kill (rehearsal-1 #3)', () => {
  it('records a recovery from the RECOVERING -> RUNNING state change when the server sends no RECOVERED', () => {
    let state: DashboardState = { ...initialDashboardState, execution: base }
    // What the rehearsal saw: the worker restarted too fast for a WORKER_DEAD, so only state/generation moved.
    state = dashboardReducer(state, execution({ ...base, state: 'RECOVERING', generation: 2, event: 'REBALANCED' }, 1_000))
    expect(state.recovering).toEqual({ since: 1_000, fromGeneration: 1, checkpointId: 18 })
    expect(state.recoveries).toBe(1)
    state = dashboardReducer(state, execution({ ...base, generation: 2, restoredFromCheckpoint: 18, event: 'CHECKPOINT_COMPLETED', checkpointId: 19, lastCompletedCheckpoint: 19 }, 13_000))
    expect(state.recovering).toBeNull()
    expect(state.lastRecovery).toEqual({ at: 13_000, durationMs: null, checkpointId: 18, fromGeneration: 1, toGeneration: 2 })
  })

  it('keeps the server-measured duration from RECOVERED, and does not count the recovery twice', () => {
    let state: DashboardState = { ...initialDashboardState, execution: base }
    state = dashboardReducer(state, execution({ ...base, state: 'RECOVERING', generation: 2, event: 'RECOVERING', checkpointId: 18 }, 1_000))
    state = dashboardReducer(state, execution({ ...base, generation: 2, restoredFromCheckpoint: 18, event: 'RECOVERED', checkpointId: 18, durationMs: 2_400 }, 4_000))
    state = dashboardReducer(state, execution({ ...base, generation: 2, event: 'CHECKPOINT_COMPLETED', checkpointId: 19, lastCompletedCheckpoint: 19 }, 5_000))
    expect(state.lastRecovery).toEqual({ at: 4_000, durationMs: 2_400, checkpointId: 18, fromGeneration: 1, toGeneration: 2 })
    expect(state.recoveries).toBe(1)
  })

  it('shows Recovered in X s for 10 s even when a checkpoint completes right after', () => {
    vi.useFakeTimers()
    const now = Date.now()
    const recovered = { ...base, generation: 2, lastCompletedCheckpoint: 19 }
    const value: DashboardState = {
      ...initialDashboardState,
      socketStatus: 'connected',
      execution: recovered,
      lastExecutionEvent: { ...recovered, event: 'CHECKPOINT_COMPLETED', checkpointId: 19 },
      lastRecovery: { at: now, durationMs: 2_400, checkpointId: 18, fromGeneration: 1, toGeneration: 2 },
    }
    render(banner(value))
    const region = screen.getByRole('region', { name: 'Execution status' })
    expect(region.textContent).toContain('Recovered in 2.4 s')
    // The checkpoint flash (1.5 s) does not hide it either.
    act(() => { vi.advanceTimersByTime(2_000) })
    expect(region.textContent).toContain('Recovered in 2.4 s')
    expect(region.textContent).toMatch(/\bRunning\b/)
    expect(region.textContent).not.toMatch(/recovering/i)
    expect(region.className).toContain('execution-banner--recovered')
    act(() => { vi.advanceTimersByTime(RECOVERED_SHOW_MS + 100) })
    expect(region.textContent).not.toContain('Recovered')
    expect(region.textContent).toContain('Checkpoint #19 saved')
  })

  it('names the checkpoint when the server gave no duration', () => {
    const recovered = { ...base, generation: 2, lastCompletedCheckpoint: 19 }
    render(banner({
      ...initialDashboardState,
      socketStatus: 'connected',
      execution: recovered,
      lastExecutionEvent: { ...recovered, event: 'CHECKPOINT_COMPLETED', checkpointId: 19 },
      lastRecovery: { at: Date.now(), durationMs: null, checkpointId: 18, fromGeneration: 1, toGeneration: 2 },
    }))
    expect(screen.getByText('Recovered from checkpoint #18')).toBeTruthy()
  })

  it('still shows Recovering first for the minimum hold', () => {
    vi.useFakeTimers()
    const recovering = { ...base, state: 'RECOVERING' as const }
    const view = render(banner({ ...initialDashboardState, socketStatus: 'connected', execution: recovering, lastExecutionEvent: { ...recovering, event: 'RECOVERING', checkpointId: 18 } }))
    expect(screen.getByText('Recovering from checkpoint #18')).toBeTruthy()
    const lastRecovery = { at: Date.now(), durationMs: 300, checkpointId: 18, fromGeneration: 1, toGeneration: 2 }
    view.rerender(banner({ ...initialDashboardState, socketStatus: 'connected', execution: base, lastExecutionEvent: { ...base, event: 'CHECKPOINT_COMPLETED', checkpointId: 19 }, lastRecovery }))
    expect(screen.getByText('Recovering from checkpoint #18')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(RECOVERY_HOLD_MS + 50) })
    expect(screen.getByText('Recovered in 0.3 s')).toBeTruthy()
  })
})

const worker = (workerId: string): WorkerInfo => ({
  workerId, alive: true, state: 'RUNNING', partitions: [0, 1], sourceQueue: { size: 0, capacity: 1000 }, sinkQueue: { size: 0, capacity: 1000 }, backpressured: false, recordsPerSec: 40, lastHeartbeatMs: 1,
})

describe('Workers panel after a kill (rehearsal-1 #12)', () => {
  it('shows the killed worker as killed at once, then back after the mark expires', () => {
    vi.useFakeTimers()
    const value: DashboardState = { ...initialDashboardState, socketStatus: 'connected', workers: [worker('worker-1'), worker('worker-2'), worker('worker-3')] }
    const killed = dashboardReducer(value, { type: 'workerKilled', workerId: 'worker-1', at: Date.now() })
    render(<DashboardContext.Provider value={killed}><WorkersPanel /></DashboardContext.Provider>)
    expect(screen.getByText('2 alive')).toBeTruthy()
    expect(screen.getByText('Killed · restarting')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(KILL_MARK_MAX_MS + 100) })
    expect(screen.getByText('3 alive')).toBeTruthy()
    expect(screen.queryByText('Killed · restarting')).toBeNull()
  })
})

describe('timeline checkpoint labels (rehearsal-1 #11)', () => {
  it('skips a label that would overlap its newer neighbour', () => {
    const now = 120_000
    // At 720 px for 120 s, 1 s is 6 px: 2 s apart (12 px) overlaps, 10 s apart (60 px) does not.
    const ticks = [{ timestamp: 100_000 }, { timestamp: 108_000 }, { timestamp: 110_000 }]
    expect(spacedLabels(ticks, now).map((tick) => tick.timestamp)).toEqual([100_000, 110_000])
  })
})
