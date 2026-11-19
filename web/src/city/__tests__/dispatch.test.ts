import { describe, expect, it, vi } from 'vitest'
import { CITY_SIZE } from '../cityLayout'
import { checkDrop, dispatchTruck, NOTICE_MS } from '../dispatch'

const reply = (status: number, body: unknown) => Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => body === undefined ? Promise.reject(new Error('no body')) : Promise.resolve(body) })

describe('drop point check', () => {
  it('accepts points on the city and rounds them', () => {
    expect(checkDrop({ x: 512.4, y: 699.6 })).toEqual({ point: { x: 512, y: 700 }, inside: true })
  })

  it('accepts the edge streets with a small margin, clamped onto the map', () => {
    expect(checkDrop({ x: -20, y: CITY_SIZE + 30 })).toEqual({ point: { x: 0, y: CITY_SIZE }, inside: true })
  })

  it('rejects drops on the stream engine panel or outside the map', () => {
    expect(checkDrop({ x: 2300, y: 900 }).inside).toBe(false)
    expect(checkDrop({ x: 500, y: -300 }).inside).toBe(false)
  })

  it('rejects a missing or broken point (renderer not ready)', () => {
    expect(checkDrop(null).inside).toBe(false)
    expect(checkDrop({ x: Number.NaN, y: 4 }).inside).toBe(false)
  })
})

describe('dispatching a truck', () => {
  it('POSTs the drop point to /api/simulator/trucks and reports the new truck', async () => {
    const fetchImpl = vi.fn(() => reply(200, { truckId: 'truck-009' }))
    const result = await dispatchTruck({ x: 500.4, y: 700 }, fetchImpl)
    expect(fetchImpl).toHaveBeenCalledWith('/api/simulator/trucks', expect.objectContaining({ method: 'POST', body: JSON.stringify({ x: 500, y: 700 }) }))
    expect(result).toEqual({ ok: true, truckId: 'truck-009', notice: { tone: 'success', text: 'truck-009 dispatched' } })
  })

  it('still succeeds when the response has no truck id', async () => {
    const result = await dispatchTruck({ x: 1, y: 1 }, () => reply(200, undefined))
    expect(result).toMatchObject({ ok: true, truckId: null, notice: { tone: 'success', text: 'Truck dispatched' } })
  })

  it('shows the API error message (contract 04 error shape)', async () => {
    const result = await dispatchTruck({ x: 1, y: 1 }, () => reply(503, { error: 'SIMULATOR_DOWN', message: 'Simulator is not reachable' }))
    expect(result).toEqual({ ok: false, notice: { tone: 'error', text: 'Truck not added · Simulator is not reachable' } })
  })

  it('falls back to the HTTP status when the error has no message', async () => {
    const result = await dispatchTruck({ x: 1, y: 1 }, () => reply(500, undefined))
    expect(result.notice).toEqual({ tone: 'error', text: 'Truck not added · HTTP 500' })
  })

  it('reports a network failure plainly', async () => {
    const result = await dispatchTruck({ x: 1, y: 1 }, () => Promise.reject(new TypeError('Failed to fetch')))
    expect(result).toEqual({ ok: false, notice: { tone: 'error', text: 'Simulator unreachable · truck not added' } })
  })

  it('keeps error notices on screen longer than success notices', () => {
    expect(NOTICE_MS.error).toBeGreaterThan(NOTICE_MS.success)
  })
})
