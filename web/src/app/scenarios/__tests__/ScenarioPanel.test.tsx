import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ScenarioPanel } from '../ScenarioPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function setup(fetchMock = vi.fn()
  .mockResolvedValueOnce(jsonResponse([
    { name: 'rush-hour', label: 'Rush hour', description: 'Peak traffic.' },
    { name: 'breakdown', label: 'Breakdown', description: 'A truck stops.' },
    { name: 'surge', label: 'Surge (+6 trucks)', description: 'Add six trucks.' },
    { name: 'reset', label: 'Reset', description: 'Restore normal conditions.' },
  ]))
  .mockResolvedValueOnce(jsonResponse({ scenario: null }))) {
  vi.stubGlobal('fetch', fetchMock)
  render(<ScenarioPanel />)
  return fetchMock
}

describe('ScenarioPanel', () => {
  it('posts the rush-hour scenario', async () => {
    const fetchMock = setup()
    fetchMock.mockResolvedValueOnce(jsonResponse({}))
    fireEvent.click(await screen.findByRole('button', { name: 'Rush hour' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/simulator/scenario', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'rush-hour' }),
    }))
    expect(await screen.findByText('Active: Rush hour')).toBeTruthy()
  })

  it('posts the selected breakdown truck', async () => {
    const fetchMock = setup()
    fetchMock.mockResolvedValueOnce(jsonResponse({}))
    fireEvent.change(await screen.findByRole('combobox', { name: 'Breakdown truck' }), { target: { value: 'truck-003' } })
    fireEvent.click(screen.getByRole('button', { name: 'Breakdown' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/simulator/scenario', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ name: 'breakdown', truckId: 'truck-003' }),
    }))
  })

  it('renders built-in scenarios when the scenario list endpoint returns 404', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({}, 404))
      .mockResolvedValueOnce(jsonResponse({ scenario: null }))
    setup(fetchMock)

    expect(await screen.findByRole('button', { name: 'Rush hour' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Surge (+6 trucks)' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reset' })).toBeTruthy()
  })

  it('shows a plain-English message when the simulator returns 502', async () => {
    const fetchMock = setup()
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: 'upstream error' }, 502))
    fireEvent.click(await screen.findByRole('button', { name: 'Rush hour' }))

    expect((await screen.findByRole('alert')).textContent).toBe('The simulator could not complete that request. Please try again.')
  })
})
