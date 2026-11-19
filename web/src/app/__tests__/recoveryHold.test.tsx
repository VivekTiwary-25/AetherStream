import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionInfo } from '../../api/types'
import { ExecutionBanner } from '../panels/LivePanels'
import { RECOVERY_HOLD_MS } from '../state/executionStatus'
import { DashboardContext, initialDashboardState, type DashboardState } from '../state/store'

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
})
