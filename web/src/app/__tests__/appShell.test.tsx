import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from '../../App'
import { tourSteps } from '../tour'

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
  // shouldAdvanceTime keeps RTL's waitFor working while every timer stays under fake-timer control.
  vi.useFakeTimers({ shouldAdvanceTime: true })
  localStorage.clear()
  sessionStorage.clear()
  stubNetwork()
})

afterEach(() => {
  cleanup()
  // Unmount closed the shared socket; drop any reconnect/poll timers that are still queued.
  vi.clearAllTimers()
  vi.useRealTimers()
  socketInstances.length = 0
  vi.unstubAllGlobals()
  vi.stubEnv('VITE_USE_MOCK', originalMockFlag)
  localStorage.clear()
  sessionStorage.clear()
})

describe('App shell integration', () => {
  it('mounts all dashboard panels, opens the editor, and shares one WebSocket', async () => {
    render(<App />)

    for (const title of [
      'Speed windows', 'Deliveries / min', 'Alerts', 'Workers', 'Dead letters',
      'Chaos controls', 'Reference comparison', 'Checkpoint & recovery timeline',
      'Operations', 'City scenarios',
    ]) {
      expect(screen.getByRole('heading', { name: title })).toBeTruthy()
    }
    fireEvent.click(screen.getByRole('button', { name: 'Open pipeline editor' }))
    expect(screen.getByRole('dialog', { name: 'Pipeline editor' })).toBeTruthy()
    await waitFor(() => expect(socketInstances).toHaveLength(1))
  })

  it('mounts the operations and scenario panels as collapsible cards', async () => {
    render(<App />)
    expect(screen.getByRole('button', { name: 'Take savepoint' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rescale' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rush hour' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Collapse Operations' }))
    expect(screen.queryByRole('heading', { name: 'Operations' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Expand Operations' }))
    expect(screen.getByRole('heading', { name: 'Operations' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Collapse City scenarios' }))
    expect(screen.queryByRole('heading', { name: 'City scenarios' })).toBeNull()
    await act(async () => { await Promise.resolve() })
  })

  it('reaches the example picker from the shell editor', async () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Open pipeline editor' }))
    const picker = await screen.findByRole('combobox', { name: 'Examples' })
    await waitFor(() => expect(screen.getByRole('option', { name: 'Truck city' })).toBeTruthy())
    expect((picker as HTMLSelectElement).disabled).toBe(false)
  })

  it('shows the intro splash once per session and the Tour button opens the tour', async () => {
    render(<App />)
    expect(screen.getByRole('dialog', { name: 'AetherStream introduction' })).toBeTruthy()
    act(() => { vi.advanceTimersByTime(2_000) })
    expect(screen.queryByRole('dialog', { name: 'AetherStream introduction' })).toBeNull()

    expect(screen.queryByText(`Step 1 of ${tourSteps.length}`)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Start tour' }))
    expect(screen.getByText(`Step 1 of ${tourSteps.length}`)).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'The city' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByText(`Step 1 of ${tourSteps.length}`)).toBeNull()

    cleanup()
    render(<App />)
    expect(screen.queryByRole('dialog', { name: 'AetherStream introduction' })).toBeNull()
  })

  it('every tour step targets an element that exists in the shell', () => {
    render(<App />)
    for (const step of tourSteps) {
      const alternatives = step.target!.split(/,(?![^[]*\])/).map((part) => part.trim())
      const found = alternatives.some((selector) => document.querySelector(selector) !== null)
      expect(found, `tour step "${step.id}" (${step.target}) must match an element`).toBe(true)
    }
  })
}, 20_000)
