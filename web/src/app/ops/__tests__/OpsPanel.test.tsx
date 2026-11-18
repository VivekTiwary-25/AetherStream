import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { OpsPanel } from '../OpsPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function setup(responses: Record<string, Response> = {}) {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    const response = responses[`${init?.method ?? 'GET'} ${path}`] ?? (
      path === '/api/executions/current' ? jsonResponse({ id: 'exec-1', state: 'RUNNING' }) : jsonResponse([])
    )
    return Promise.resolve(response)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<OpsPanel />)
  return fetchMock
}

const saved = { savepointId: 'sp-abc', checkpointId: 12, createdAt: '2026-11-19T12:00:00Z' }

describe('OpsPanel', () => {
  it('creates and shows a savepoint at its checkpoint', async () => {
    const fetchMock = setup({ 'POST /api/executions/exec-1/savepoints': jsonResponse(saved) })
    fireEvent.click(await screen.findByRole('button', { name: 'Take savepoint' }))

    expect((await screen.findByRole('status')).textContent).toBe('Savepoint sp-abc at checkpoint #12')
    expect(fetchMock).toHaveBeenLastCalledWith('/api/executions/exec-1/savepoints', expect.objectContaining({ method: 'POST' }))
    expect(screen.getByText('sp-abc · checkpoint #12')).toBeTruthy()
  })

  it('requires confirmation and posts the selected savepoint id', async () => {
    const fetchMock = setup({ 'GET /api/savepoints': jsonResponse([saved]) })
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('confirm', confirm)
    vi.stubGlobal('crypto', { randomUUID: () => 'request-key' })

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect(confirm).toHaveBeenCalledWith('Restart the pipeline from this savepoint?')
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/executions/exec-1/restore', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ savepointId: 'sp-abc', idempotencyKey: 'request-key' }),
    }))
  })

  it('shows an incompatible savepoint response message verbatim', async () => {
    setup({
      'GET /api/savepoints': jsonResponse([saved]),
      'POST /api/executions/exec-1/restore': jsonResponse({ error: 'INCOMPATIBLE_SAVEPOINT', message: 'This savepoint belongs to a different pipeline version.' }, 409),
    })
    const confirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', confirm)
    vi.stubGlobal('crypto', { randomUUID: () => 'request-key' })

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect((await screen.findByRole('alert')).textContent).toBe('This savepoint belongs to a different pipeline version.')
  })

  it('posts rescale for the current execution', async () => {
    const fetchMock = setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Rescale' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/executions/exec-1/rescale', expect.objectContaining({ method: 'POST' }))
    expect(screen.getByText('Pauses at the next checkpoint, moves partitions, resumes - a controlled pause, not live migration.')).toBeTruthy()
  })
})
