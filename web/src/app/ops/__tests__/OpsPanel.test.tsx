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

  it('requires an in-page confirmation (not window.confirm) and posts the selected savepoint id', async () => {
    const fetchMock = setup({
      'GET /api/savepoints': jsonResponse([saved]),
      'POST /api/executions/exec-1/restore': jsonResponse({ id: 'exec-2', pipelineId: 'logistics-demo', state: 'RUNNING', generation: 1, restoredFromCheckpoint: 12 }),
    })
    const confirm = vi.fn()
    vi.stubGlobal('confirm', confirm)
    vi.stubGlobal('crypto', { randomUUID: () => 'request-key' })

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect(screen.getByText('Restart the pipeline from this savepoint?')).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByText('Restart the pipeline from this savepoint?')).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm restore' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/executions/exec-1/restore', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ savepointId: 'sp-abc', idempotencyKey: 'request-key' }),
    }))
    expect(confirm).not.toHaveBeenCalled()
    expect((await screen.findByRole('status')).textContent).toBe('Restored from checkpoint #12 → new execution exec-2')
  })

  it('shows an incompatible savepoint response message verbatim', async () => {
    setup({
      'GET /api/savepoints': jsonResponse([saved]),
      'POST /api/executions/exec-1/restore': jsonResponse({ error: 'INCOMPATIBLE_SAVEPOINT', message: 'This savepoint belongs to a different pipeline version.' }, 409),
    })
    vi.stubGlobal('crypto', { randomUUID: () => 'request-key' })

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm restore' }))
    expect((await screen.findByRole('alert')).textContent).toBe('This savepoint belongs to a different pipeline version.')
  })

  it('shows a refused rescale next to the button, naming 409 CONFLICT', async () => {
    setup({ 'POST /api/executions/exec-1/rescale': jsonResponse({ error: 'CONFLICT', message: 'execution exec-1 does not need a rebalance' }, 409) })
    fireEvent.click(await screen.findByRole('button', { name: 'Rescale' }))
    expect((await screen.findByRole('alert')).textContent).toBe('Refused (409 CONFLICT): execution exec-1 does not need a rebalance')
  })

  it('lists at most three savepoints of the current pipeline, newest first', async () => {
    const row = (savepointId: string, pipelineId: string, createdAt: string) => ({ savepointId, checkpointId: 1, createdAt, executionId: 'exec-0', pipelineId })
    setup({
      'GET /api/executions/current': jsonResponse({ id: 'exec-1', state: 'RUNNING', pipelineId: 'logistics-demo' }),
      'GET /api/savepoints': jsonResponse([
        row('sp-1', 'logistics-demo', '2026-11-19T10:00:00Z'),
        row('sp-other', 'other-pipeline', '2026-11-19T13:00:00Z'),
        row('sp-3', 'logistics-demo', '2026-11-19T12:00:00Z'),
        row('sp-2', 'logistics-demo', '2026-11-19T11:00:00Z'),
        row('sp-4', 'logistics-demo', '2026-11-19T12:30:00Z'),
      ]),
    })
    await screen.findByText('sp-4 · checkpoint #1')
    const list = screen.getByLabelText('Recent savepoints').textContent ?? ''
    expect(list.indexOf('sp-4')).toBeLessThan(list.indexOf('sp-3'))
    expect(list.indexOf('sp-3')).toBeLessThan(list.indexOf('sp-2'))
    expect(list).not.toContain('sp-1 ')
    expect(list).not.toContain('sp-other')
  })

  it('posts rescale for the current execution', async () => {
    const fetchMock = setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Rescale' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/executions/exec-1/rescale', expect.objectContaining({ method: 'POST' }))
    expect(screen.getByText('Pauses at the next checkpoint, moves partitions, resumes - a controlled pause, not live migration.')).toBeTruthy()
  })
})
