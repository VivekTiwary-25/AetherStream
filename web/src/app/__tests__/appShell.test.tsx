import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from '../../App'

const originalMockFlag = import.meta.env.VITE_USE_MOCK
const socketInstances: MockWebSocket[] = []

class MockWebSocket {
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  static readonly OPEN = 1

  constructor() {
    socketInstances.push(this)
  }

  close() {
    this.onclose?.()
  }
}

class MockResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

afterEach(() => {
  cleanup()
  socketInstances.length = 0
  vi.unstubAllGlobals()
  vi.stubEnv('VITE_USE_MOCK', originalMockFlag)
})

describe('App shell integration', () => {
  it('mounts all dashboard panels, opens the editor, and shares one WebSocket', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      const body = path.endsWith('/api/version') ? { commit: 'abcdef012345', builtAt: '' } : []
      if (path.endsWith('/api/executions/current')) return { ok: true, status: 200, json: async () => null } as Response
      return { ok: true, status: 200, json: async () => body } as Response
    }))
    vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket)
    vi.stubGlobal('ResizeObserver', MockResizeObserver)

    render(<App />)

    for (const title of [
      'Speed windows', 'Deliveries / min', 'Alerts', 'Workers', 'Dead letters',
      'Chaos controls', 'Reference comparison', 'Checkpoint & recovery timeline',
    ]) {
      expect(screen.getByRole('heading', { name: title })).toBeTruthy()
    }
    fireEvent.click(screen.getByRole('button', { name: 'Pipeline' }))
    expect(screen.getByRole('dialog', { name: 'Pipeline editor' })).toBeTruthy()
    await waitFor(() => expect(socketInstances).toHaveLength(1))
  })
})
