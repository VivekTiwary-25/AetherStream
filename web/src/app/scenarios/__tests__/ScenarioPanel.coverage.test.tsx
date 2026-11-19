import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ScenarioPanel } from '../ScenarioPanel'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

const scenarios = [
  { name: 'rush-hour', label: 'Rush Hour Special', description: 'Heavy traffic.' },
  { name: 'breakdown', label: 'Truck Breakdown', description: 'Stop a truck.' },
]

function setup(applyResponse = jsonResponse({})) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const path = String(input)
    if (path === '/api/scenarios') return Promise.resolve(jsonResponse(scenarios))
    if (path === '/api/simulator/state') return Promise.resolve(jsonResponse({ scenario: 'rush-hour' }))
    return Promise.resolve(applyResponse)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<ScenarioPanel />)
  return fetchMock
}

describe('ScenarioPanel additional coverage', () => {
  it('loads the scenario list and marks the simulator scenario active', async () => {
    setup()

    expect(await screen.findByRole('button', { name: 'Rush Hour Special' })).toBeTruthy()
    expect(screen.getByText('Heavy traffic.')).toBeTruthy()
    expect(await screen.findByText('Active: Rush Hour Special')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rush Hour Special' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('posts a scenario run and disables scenario controls until it completes', async () => {
    let resolveApply!: (response: Response) => void
    const applyResponse = new Promise<Response>((resolve) => { resolveApply = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/scenarios') return Promise.resolve(jsonResponse(scenarios))
      if (path === '/api/simulator/state') return Promise.resolve(jsonResponse({ scenario: null }))
      return applyResponse
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ScenarioPanel />)

    const runButton = await screen.findByRole('button', { name: 'Truck Breakdown' })
    fireEvent.click(runButton)
    await waitFor(() => expect(runButton.hasAttribute('disabled')).toBe(true))
    expect(screen.getByRole('button', { name: 'Rush Hour Special' }).hasAttribute('disabled')).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('/api/simulator/scenario', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ name: 'breakdown', truckId: 'truck-005' }),
    }))

    resolveApply(jsonResponse({ scenario: 'breakdown' }))
    expect(await screen.findByText('Active: Truck Breakdown')).toBeTruthy()
    await waitFor(() => expect(runButton.hasAttribute('disabled')).toBe(false))
  })

  it('shows a failed scenario run in the error state', async () => {
    setup(jsonResponse({ message: 'unavailable' }, 503))
    fireEvent.click(await screen.findByRole('button', { name: 'Truck Breakdown' }))

    expect((await screen.findByRole('alert')).textContent).toBe('The simulator could not complete that request. Please try again.')
    expect(screen.getByRole('button', { name: 'Truck Breakdown' }).hasAttribute('disabled')).toBe(false)
  })
})
