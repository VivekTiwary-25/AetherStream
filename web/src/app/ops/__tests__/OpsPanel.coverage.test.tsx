import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { OpsPanel } from '../OpsPanel'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

const savepoints = [
  { savepointId: 'sp-new', checkpointId: 21, createdAt: '2026-11-19T12:00:00Z' },
  { savepointId: 'sp-old', checkpointId: 18, createdAt: '2026-11-19T11:00:00Z' },
]

function setup(postResponse = jsonResponse({})) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const path = String(input)
    if (path === '/api/executions/current') return Promise.resolve(jsonResponse({ id: 'exec-1', state: 'RUNNING' }))
    if (path === '/api/savepoints') return Promise.resolve(jsonResponse(savepoints))
    return Promise.resolve(postResponse)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<OpsPanel />)
  return fetchMock
}

describe('OpsPanel additional coverage', () => {
  it('lists recent savepoints and exposes the current execution status', async () => {
    setup()

    expect(await screen.findByText('sp-new · checkpoint #21')).toBeTruthy()
    expect(screen.getByText('sp-old · checkpoint #18')).toBeTruthy()
    expect(screen.getByText('Running')).toBeTruthy()
  })

  it('keeps operations disabled until the current execution has loaded', async () => {
    let resolveExecution!: (response: Response) => void
    const executionResponse = new Promise<Response>((resolve) => { resolveExecution = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL) => String(input) === '/api/executions/current'
      ? executionResponse
      : Promise.resolve(jsonResponse(savepoints)))
    vi.stubGlobal('fetch', fetchMock)
    render(<OpsPanel />)

    expect(screen.getByRole('button', { name: 'Take savepoint' }).hasAttribute('disabled')).toBe(true)
    resolveExecution(jsonResponse({ id: 'exec-1', state: 'RUNNING' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Take savepoint' }).hasAttribute('disabled')).toBe(false))
  })

  it('posts a rescale request and disables operations while it is pending', async () => {
    let resolveRescale!: (response: Response) => void
    const pendingResponse = new Promise<Response>((resolve) => { resolveRescale = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/executions/current') return Promise.resolve(jsonResponse({ id: 'exec-1', state: 'RUNNING' }))
      if (path === '/api/savepoints') return Promise.resolve(jsonResponse(savepoints))
      if (path.endsWith('/rescale') && init?.method === 'POST') return pendingResponse
      return Promise.resolve(jsonResponse({}))
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<OpsPanel />)

    const rescale = await screen.findByRole('button', { name: 'Rescale' })
    fireEvent.click(rescale)
    await waitFor(() => expect(rescale.hasAttribute('disabled')).toBe(true))
    expect(screen.getByRole('button', { name: 'Take savepoint' }).hasAttribute('disabled')).toBe(true)
    resolveRescale(jsonResponse({ checkpointId: 22 }))
    await waitFor(() => expect(rescale.hasAttribute('disabled')).toBe(false))
    expect(fetchMock).toHaveBeenCalledWith('/api/executions/exec-1/rescale', expect.objectContaining({ method: 'POST' }))
  })

  it('shows an API 409 message verbatim when creating a savepoint fails', async () => {
    setup(jsonResponse({ error: 'CONFLICT', message: 'A checkpoint is already in progress.' }, 409))
    fireEvent.click(await screen.findByRole('button', { name: 'Take savepoint' }))

    expect((await screen.findByRole('alert')).textContent).toBe('A checkpoint is already in progress.')
  })

  it.skip('BUG: rescale input validation cannot be tested because OpsPanel exposes no rescale input')
})
