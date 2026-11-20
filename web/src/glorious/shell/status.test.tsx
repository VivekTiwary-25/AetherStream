import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DashboardContext, initialDashboardState, type DashboardState } from '../../app/state/store'
import { StatusPill } from './StatusPill'

const running: DashboardState = {
  ...initialDashboardState,
  socketStatus: 'connected',
  execution: { id: 'exec-1', pipelineId: 'fleet', pipelineVersion: 1, state: 'RUNNING', generation: 1, restoredFromCheckpoint: null, lastCompletedCheckpoint: 0, createdAt: '2026-11-20T00:00:00Z' },
}

function pill(state: DashboardState) {
  return <DashboardContext.Provider value={state}><StatusPill /></DashboardContext.Provider>
}

afterEach(() => { cleanup(); vi.useRealTimers() })

describe('engine status honesty', () => {
  it('keeps the plain Running label with an unchanged execution for 30 seconds while connected', () => {
    vi.useFakeTimers()
    const view = render(pill(running))
    act(() => { vi.advanceTimersByTime(30_000) })
    view.rerender(pill({ ...running }))
    expect(screen.getByRole('status').textContent).toBe('Running')
    expect(screen.getByLabelText('Event rate unavailable')).toBeTruthy()
  })

  it('marks retained execution as last reported only while the connection is down', () => {
    const state = { ...running, flow: { eventsPerSec: 42, byPartition: {}, rejectedPerSec: 0, latePerSec: 0 } }
    const view = render(pill(state))
    expect(screen.getByLabelText('42 events per second')).toBeTruthy()
    view.rerender(pill({ ...state, socketStatus: 'disconnected' }))
    expect(screen.getByRole('status').textContent).toBe('Last reported: Running')
    expect(screen.getByLabelText('Event rate unavailable')).toBeTruthy()
    view.rerender(pill(state))
    expect(screen.getByRole('status').textContent).toBe('Running')
  })

  it('does not invent a stopped execution before the first snapshot', () => {
    const view = render(pill({ ...initialDashboardState, socketStatus: 'connected' }))
    expect(screen.getByRole('status').textContent).toBe('Unavailable')
    view.rerender(pill(initialDashboardState))
    expect(screen.getByRole('status').textContent).toBe('Disconnected')
  })

  it('distinguishes an explicitly failed execution from a stopped one', () => {
    render(pill({ ...running, execution: { ...running.execution!, state: 'FAILED' } }))
    expect(screen.getByRole('status').textContent).toBe('Failed')
  })
})
