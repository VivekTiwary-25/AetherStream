import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ChaosPanel } from '../ChaosPanel'
import { ComparePanel } from '../ComparePanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

describe('ChaosPanel', () => {
  it('requires confirmation before killing a worker', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([{ workerId: 'w-1', alive: true, state: 'RUNNING', partitions: [0, 1], sourceQueue: { size: 0, capacity: 1000 }, sinkQueue: { size: 0, capacity: 1000 }, backpressured: false, recordsPerSec: 5, lastHeartbeatMs: 1 }]))
      .mockResolvedValueOnce(jsonResponse({ workerId: 'w-1' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Kill worker' }))
    expect(await screen.findByText('Kill worker w-1? It will crash without warning.')).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm kill' }))

    expect(await screen.findByText('Worker w-1 killed.')).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string)).toEqual({ workerId: null })
  })

  it('posts the selected slow-sink delay', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ delayMs: 50 }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ChaosPanel />)

    fireEvent.change(screen.getByRole('combobox', { name: 'Slow down output' }), { target: { value: '50' } })

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(fetchMock).toHaveBeenCalledWith('/api/chaos/slow-sink', expect.objectContaining({ method: 'POST', body: JSON.stringify({ delayMs: 50 }) }))
    expect(screen.getByText(/Output slowed: queues fill up/)).toBeTruthy()
  })
})

describe('ComparePanel', () => {
  it('renders a matching comparison with formatted counts', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({ id: 'e-1', pipelineId: 'p', pipelineVersion: 1, state: 'RUNNING', generation: 1, restoredFromCheckpoint: null, lastCompletedCheckpoint: 2, createdAt: 'now' }))
      .mockResolvedValueOnce(jsonResponse({ checkpointId: 3, referenceResults: 4812, engineResults: 4812, matched: 4812, mismatches: [], recordsReplayed: 100, durationMs: 12, match: true })))
    render(<ComparePanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Compare with reference' }))

    expect(await screen.findByText('4,812 / 4,812 results match')).toBeTruthy()
    expect(screen.getByText(/Checked up to checkpoint #3 - replayed 100 events in 12 ms/)).toBeTruthy()
  })

  it('lists mismatches for a failing comparison', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse(null, 204))
      .mockResolvedValueOnce(jsonResponse({ checkpointId: 4, referenceResults: 2, engineResults: 1, matched: 1, mismatches: [{ type: 'MISSING', sinkId: 'speedSink', key: 'truck-7', windowStart: 60000, detail: 'Engine row missing' }], recordsReplayed: 20, durationMs: 8, match: false })))
    render(<ComparePanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Compare with reference' }))

    expect(await screen.findByRole('heading', { name: '1 mismatches' })).toBeTruthy()
    expect(screen.getByText('speedSink')).toBeTruthy()
    expect(screen.getByText('truck-7')).toBeTruthy()
    expect(screen.getByText('Engine row missing')).toBeTruthy()
  })
})
