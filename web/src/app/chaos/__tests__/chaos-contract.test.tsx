import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ChaosPanel } from '../ChaosPanel'
import { ComparePanel } from '../ComparePanel'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

const workers = [
  { workerId: 'w-1', alive: true, state: 'RUNNING', partitions: [0], sourceQueue: { size: 0, capacity: 1000 }, sinkQueue: { size: 0, capacity: 1000 }, backpressured: false, recordsPerSec: 5, lastHeartbeatMs: 1 },
  { workerId: 'w-2', alive: true, state: 'RUNNING', partitions: [1, 2], sourceQueue: { size: 0, capacity: 1000 }, sinkQueue: { size: 0, capacity: 1000 }, backpressured: false, recordsPerSec: 5, lastHeartbeatMs: 1 },
]

describe('ChaosPanel API contract', () => {
  it('posts the contract bodies for late, duplicate, and malformed events', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ sent: 20 }))
      .mockResolvedValueOnce(jsonResponse({ sent: 20 }))
      .mockResolvedValueOnce(jsonResponse({ sent: 5 }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Send late events' }))
    await screen.findByText('20 late events sent')
    fireEvent.click(screen.getByRole('button', { name: 'Send duplicates (20)' }))
    await screen.findByText('20 duplicate events sent')
    fireEvent.click(screen.getByRole('button', { name: 'Send malformed (5)' }))
    await screen.findByText('5 malformed events sent')

    expect(fetchMock.mock.calls).toEqual([
      ['/api/chaos/late-events', expect.objectContaining({ method: 'POST', body: JSON.stringify({ count: 20, lateByMs: 180_000 }) })],
      ['/api/chaos/duplicates', expect.objectContaining({ method: 'POST', body: JSON.stringify({ count: 20 }) })],
      ['/api/chaos/malformed', expect.objectContaining({ method: 'POST', body: JSON.stringify({ count: 5 }) })],
    ])
  })

  it('posts each selected slow-sink delay including restoring normal speed', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ delayMs: 50 }))
      .mockResolvedValueOnce(jsonResponse({ delayMs: 200 }))
      .mockResolvedValueOnce(jsonResponse({ delayMs: 0 }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)
    const delay = screen.getByRole('combobox', { name: 'Slow down output' })

    fireEvent.change(delay, { target: { value: '50' } })
    await screen.findByText('Output slowed to 50 ms per record.')
    fireEvent.change(delay, { target: { value: '200' } })
    await screen.findByText('Output slowed to 200 ms per record.')
    fireEvent.change(delay, { target: { value: '0' } })
    await screen.findByText('Output speed restored.')

    expect(fetchMock.mock.calls.map(([path, init]) => [path, JSON.parse((init as RequestInit).body as string)])).toEqual([
      ['/api/chaos/slow-sink', { delayMs: 50 }],
      ['/api/chaos/slow-sink', { delayMs: 200 }],
      ['/api/chaos/slow-sink', { delayMs: 0 }],
    ])
  })

  it('loads workers before confirmation and sends the selected worker id', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(workers))
      .mockResolvedValueOnce(jsonResponse({ workerId: 'w-1' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Kill worker' }))
    expect(await screen.findByText('Kill worker w-2? It will crash without warning.')).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.change(screen.getByRole('combobox', { name: 'Worker to kill' }), { target: { value: 'w-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm kill' }))

    await screen.findByText('Worker w-1 killed.')
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/workers', expect.any(Object))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/chaos/kill-worker', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ workerId: 'w-1' }),
    }))
  })

  it('disables controls while pending and shows a 409 message verbatim', async () => {
    const request = deferred<Response>()
    const fetchMock = vi.fn().mockReturnValue(request.promise)
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Send late events' }))
    expect(screen.getByRole('button', { name: 'Send late events' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Send duplicates (20)' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('combobox', { name: 'Slow down output' }).hasAttribute('disabled')).toBe(true)

    request.resolve(jsonResponse({ error: 'CONFLICT', message: 'Chaos injection is unavailable while the execution is recovering.' }, 409))
    expect((await screen.findByRole('alert')).textContent).toBe('Chaos injection is unavailable while the execution is recovering.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send late events' }).hasAttribute('disabled')).toBe(false))
  })
})

describe('ComparePanel API contract', () => {
  it('posts an empty compare body, disables while loading, and shows a 409 message verbatim', async () => {
    const comparison = deferred<Response>()
    const fetchMock = vi.fn((input: RequestInfo | URL) => String(input) === '/api/executions/current'
      ? Promise.resolve(jsonResponse(null, 204))
      : comparison.promise)
    vi.stubGlobal('fetch', fetchMock)
    render(<ComparePanel />)

    const button = screen.getByRole('button', { name: 'Compare with reference' })
    fireEvent.click(button)
    expect(screen.getByRole('button', { name: 'Comparing…' }).hasAttribute('disabled')).toBe(true)
    expect(await screen.findByRole('status', { name: 'Comparison in progress' })).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledWith('/api/compare', expect.objectContaining({ method: 'POST', body: '{}' }))

    comparison.resolve(jsonResponse({ error: 'COMPARE_CONFLICT', message: 'The execution has no completed checkpoint to compare.' }, 409))
    expect((await screen.findByRole('alert')).textContent).toBe('The execution has no completed checkpoint to compare.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Compare with reference' }).hasAttribute('disabled')).toBe(false))
  })
})
