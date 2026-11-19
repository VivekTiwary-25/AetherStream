import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionInfo } from '../../api/types'
import { ExecutionBanner } from '../panels/LivePanels'
import { RECOVERY_HOLD_MS } from '../state/executionStatus'
import { DashboardContext, dashboardReducer, initialDashboardState, type DashboardState } from '../state/store'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

const base: ExecutionInfo = { id: 'exec-1', pipelineId: 'logistics-demo', pipelineVersion: 1, state: 'RUNNING', generation: 4, restoredFromCheckpoint: 12, lastCompletedCheckpoint: 12, createdAt: '2026-11-19T09:00:00Z' }

function banner(value: DashboardState) {
  return <DashboardContext.Provider value={value}><ExecutionBanner /></DashboardContext.Provider>
}

describe('execution banner polish (T98)', () => {
  it('keeps a sub-second recovery visible for the minimum hold, then shows the measured time', () => {
    vi.useFakeTimers()
    const recovering = { ...base, state: 'RECOVERING' as const }
    const view = render(banner({ ...initialDashboardState, socketStatus: 'connected', execution: recovering, lastExecutionEvent: { ...recovering, event: 'RECOVERING', checkpointId: 12 } }))
    expect(screen.getByText('Recovering from checkpoint #12')).toBeTruthy()

    const recovered = { ...base, generation: 5, event: 'RECOVERED' as const, durationMs: 40 }
    view.rerender(banner({ ...initialDashboardState, socketStatus: 'connected', execution: base, lastExecutionEvent: recovered }))
    expect(screen.getByText('Recovering from checkpoint #12')).toBeTruthy()

    act(() => { vi.advanceTimersByTime(RECOVERY_HOLD_MS + 50) })
    expect(screen.queryByText('Recovering from checkpoint #12')).toBeNull()
    expect(screen.getByText('Recovered in < 0.1 s')).toBeTruthy()
    expect(screen.getByText('Running')).toBeTruthy()
  })

  it('uses human state labels and keeps the connection status in its own chip', () => {
    render(banner({ ...initialDashboardState, socketStatus: 'disconnected', execution: { ...base, lastCompletedCheckpoint: 0 } }))
    expect(screen.queryByText('RUNNING')).toBeNull()
    expect(screen.getAllByText('Running').length).toBeGreaterThan(0)
    expect(screen.getByText('Live connection lost — reconnecting…').className).toContain('execution-banner__connection--disconnected')
  })

  it('reads as words, so the banner text says Running after a recovery (ui-e2e word-boundary regex)', () => {
    render(banner({ ...initialDashboardState, socketStatus: 'connected', execution: base, lastExecutionEvent: { ...base, event: 'RECOVERED', durationMs: 2000 } }))
    const text = screen.getByRole('region', { name: 'Execution status' }).textContent ?? ''
    expect(text).toMatch(/\bRunning\b/)
    expect(text).not.toMatch(/recovering/i)
    expect(text).toContain('Recovered in 2.0 s')
  })

  it('still says Running while backpressured after recovery', () => {
    const workers = [{ workerId: 'w1', alive: true, state: 'RUNNING', backpressured: true }] as unknown as DashboardState['workers']
    render(banner({ ...initialDashboardState, socketStatus: 'connected', workers, execution: base, lastExecutionEvent: { ...base, event: 'RECOVERED', durationMs: 2000 } }))
    const text = screen.getByRole('region', { name: 'Execution status' }).textContent ?? ''
    expect(text).toMatch(/\bRunning\b/)
    expect(text).toContain('Backpressured')
  })

  it('holds a recovery that was only counted (RECOVERING and RECOVERED in one batch), then returns to Running', () => {
    vi.useFakeTimers()
    const view = render(banner({ ...initialDashboardState, socketStatus: 'connected', execution: base, lastExecutionEvent: { ...base, event: 'STARTED' } }))
    view.rerender(banner({ ...initialDashboardState, socketStatus: 'connected', execution: base, recoveries: 1, recoveryCheckpoint: 12, lastExecutionEvent: { ...base, event: 'RECOVERED', durationMs: 40 } }))
    expect(screen.getByText('Recovering from checkpoint #12')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(RECOVERY_HOLD_MS + 50) })
    expect(screen.queryByText('Recovering from checkpoint #12')).toBeNull()
    expect(screen.getByText('Running')).toBeTruthy()
  })

  it('treats REBALANCED as a rebalance, not a recovery, and a rejoining RESTORING worker does not re-arm the hold', () => {
    const workers = [{ workerId: 'w2', alive: true, state: 'RESTORING', backpressured: false }] as unknown as DashboardState['workers']
    const rebalanced = { ...base, generation: 6, event: 'REBALANCED', workerId: 'w2' } as unknown as DashboardState['lastExecutionEvent']
    render(banner({ ...initialDashboardState, socketStatus: 'connected', workers, execution: { ...base, generation: 6 }, lastExecutionEvent: rebalanced, rebalancedFromGeneration: 5 }))
    expect(screen.getByText('Rebalanced (gen 5 → 6)')).toBeTruthy()
    expect(screen.getByText('Running')).toBeTruthy()
    expect(screen.queryByText(/Recovering/)).toBeNull()
  })
})

describe('dashboard reducer: REBALANCED (contract 04)', () => {
  it('records the old generation and does not count a recovery', () => {
    const running = { ...initialDashboardState, execution: base }
    const next = dashboardReducer(running, { type: 'message', message: { type: 'execution', ts: 1, data: { ...base, generation: 5, event: 'REBALANCED', workerId: 'w2' } } as never })
    expect(next.rebalancedFromGeneration).toBe(4)
    expect(next.recoveries).toBe(0)
    expect(next.execution?.generation).toBe(5)
  })
})
