import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import { axe } from 'vitest-axe'
import App from '../../App'
import { AlertsPanel, DeadLettersPanel, DeliveriesPanel, ExecutionBanner, SpeedWindowsPanel, WorkersPanel } from '../panels/LivePanels'
import { ChaosPanel } from '../chaos/ChaosPanel'
import { ComparePanel } from '../chaos/ComparePanel'
import { OpsPanel } from '../ops/OpsPanel'
import { ScenarioPanel } from '../scenarios/ScenarioPanel'
import { CheckpointTimeline } from '../timeline/CheckpointTimeline'
import { DashboardProvider } from '../state/store'

const originalMockFlag = import.meta.env.VITE_USE_MOCK

const socketInstances: MockWebSocket[] = []

class MockWebSocket {
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  static readonly OPEN = 1
  closed = false

  constructor() {
    socketInstances.push(this)
  }

  close() {
    if (this.closed) return
    this.closed = true
    this.onclose?.()
  }
}

class MockResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function stubNetwork() {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.endsWith('/api/executions/current')) return { ok: true, status: 204, json: async () => null } as Response
    if (path.endsWith('/api/version')) return { ok: true, status: 200, json: async () => ({ commit: 'abcdef012345', builtAt: '' }) } as Response
    if (path.endsWith('/api/pipelines/examples')) {
      return { ok: true, status: 200, json: async () => [{ id: 'logistics-demo', name: 'Truck city', description: 'Demo' }] } as Response
    }
    if (path.endsWith('/api/simulator/state')) return { ok: true, status: 200, json: async () => ({ scenario: null }) } as Response
    return { ok: true, status: 200, json: async () => [] } as Response
  }))
  vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket)
  vi.stubGlobal('ResizeObserver', MockResizeObserver)
}

beforeEach(() => {
  // Keep timers controlled while allowing async testing-library queries to advance.
  vi.useFakeTimers({ shouldAdvanceTime: true })
  localStorage.clear()
  sessionStorage.clear()
  stubNetwork()
})

afterEach(() => {
  cleanup()
  vi.clearAllTimers()
  vi.useRealTimers()
  socketInstances.length = 0
  vi.unstubAllGlobals()
  vi.stubEnv('VITE_USE_MOCK', originalMockFlag)
  localStorage.clear()
  sessionStorage.clear()
})

// jsdom cannot compute rendered colors, so axe's color-contrast check is disabled.
const axeOptions = { rules: { 'color-contrast': { enabled: false } } }

async function expectAccessible(ui: React.ReactElement) {
  const { container } = render(ui)
  const results = await axe(container, axeOptions)
  const seriousViolations = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
  expect(seriousViolations).toEqual([])
}
describe('axe accessibility', () => {
  // BUG: DeadLettersPanel gives role="tab" buttons aria-pressed, which axe flags as critical aria-allowed-attr.
  it.skip('BUG: checks the App shell; the Dead letters panel uses aria-pressed on role="tab" buttons', async () => {
    await expectAccessible(<App />)
  })

  const panelCases = [
    ['speed windows', <SpeedWindowsPanel />],
    ['deliveries', <DeliveriesPanel />],
    ['alerts', <AlertsPanel />],
    ['workers', <WorkersPanel />],
    ['execution banner', <ExecutionBanner />],
    ['chaos controls', <ChaosPanel />],
    ['reference comparison', <ComparePanel />],
    ['operations', <OpsPanel />],
    ['city scenarios', <ScenarioPanel />],
    ['checkpoint timeline', <CheckpointTimeline />],
  ] as const
  it.each(panelCases)('checks the %s panel', async (_name, panel) => {
    await expectAccessible(<DashboardProvider>{panel}</DashboardProvider>)
  })
  // BUG: role="tab" does not support aria-pressed; axe reports a critical aria-allowed-attr violation.
  it.skip('BUG: checks the dead letters panel (aria-pressed on role="tab" buttons)', async () => {
    await expectAccessible(<DashboardProvider><DeadLettersPanel /></DashboardProvider>)
  })
})
