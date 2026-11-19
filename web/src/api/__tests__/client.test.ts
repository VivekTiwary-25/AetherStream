import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addSimulatorTruck,
  compareWithReference,
  createPipeline,
  getAlerts,
  getCheckpoints,
  getCurrentExecution,
  getDeadLetters,
  getDemoPipeline,
  getExecution,
  getHealth,
  getPipeline,
  getReady,
  getSimulatorState,
  getSummary,
  getVersion,
  getWindows,
  getWorkers,
  killWorker,
  listPipelines,
  sendDuplicates,
  sendLateEvents,
  sendMalformed,
  setSlowSink,
  startExecution,
  stopExecution,
  validatePipeline,
} from '../client'
import type { PipelineDefinition } from '../types'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('REST API client', () => {
  it('returns decoded success bodies and sends JSON headers and bodies', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ status: 'UP' })))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getHealth()).resolves.toEqual({ status: 'UP' })
    await validatePipeline({ id: 'demo', version: 1, name: 'Demo', nodes: [], edges: [] })

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/health', { headers: {} })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/pipelines/validate', {
      method: 'POST',
      body: JSON.stringify({ id: 'demo', version: 1, name: 'Demo', nodes: [], edges: [] }),
      headers: { 'Content-Type': 'application/json' },
    })
  })

  it('maps HTTP errors to ApiError and preserves server messages verbatim', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ error: 'CONFLICT', message: 'Already running; keep this exact message.' }, 409))
      .mockResolvedValueOnce(response({ error: 'MISSING', message: 'Pipeline not found' }, 404))
      .mockResolvedValueOnce(response({ error: 'BROKEN', message: 'Backend failed' }, 500))
      .mockResolvedValueOnce(new Response('not JSON', { status: 503, statusText: 'Unavailable' }))
    vi.stubGlobal('fetch', fetchMock)

    for (const [call, code, message] of [
      [getHealth, 'CONFLICT', 'Already running; keep this exact message.'],
      [getHealth, 'MISSING', 'Pipeline not found'],
      [getHealth, 'BROKEN', 'Backend failed'],
      [getHealth, 'HTTP_503', 'Unavailable'],
    ] as const) {
      await expect(call()).rejects.toMatchObject({ name: 'ApiError', code, message })
    }
  })

  it('passes through network failures and returns undefined for no-content responses', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getHealth()).rejects.toThrow('offline')
    await expect(getCurrentExecution()).resolves.toBeUndefined()
  })

  it('encodes resource identifiers and formats result query parameters', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response([])))
    vi.stubGlobal('fetch', fetchMock)

    await getPipeline('id/with space')
    await getExecution('exec/1')
    await getCheckpoints('exec/1', 7)
    await getWindows('sink & other', 13)
    await getAlerts(9)
    await getDeadLetters('REJECTED', 4)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/pipelines/id%2Fwith%20space',
      '/api/executions/exec%2F1',
      '/api/executions/exec%2F1/checkpoints?limit=7',
      '/api/results/windows?sinkId=sink%20%26%20other&limit=13',
      '/api/results/alerts?limit=9',
      '/api/results/dead-letters?kind=REJECTED&limit=4',
    ])
  })

  it('posts a new simulator truck with its coordinates', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ truckId: 'truck-009' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(addSimulatorTruck(500, 700)).resolves.toEqual({ truckId: 'truck-009' })
    expect(fetchMock).toHaveBeenCalledWith('/api/simulator/trucks', {
      method: 'POST',
      body: JSON.stringify({ x: 500, y: 700 }),
      headers: { 'Content-Type': 'application/json' },
    })
  })

  it('routes the remaining API operations to their endpoints', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({})))
    vi.stubGlobal('fetch', fetchMock)
    const pipeline: PipelineDefinition = { id: 'demo', version: 1, name: 'Demo', nodes: [], edges: [] }

    await Promise.all([
      getReady(), getVersion(), createPipeline(pipeline), listPipelines(), getDemoPipeline(),
      startExecution('p/1', { idempotencyKey: 'key' }), stopExecution('e/1'), getWorkers(), getSummary(),
      sendLateEvents(2, 500), sendDuplicates(3), sendMalformed(4), setSlowSink(8), killWorker('w/1'),
      getSimulatorState(), compareWithReference(),
    ])

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/ready', '/api/version', '/api/pipelines', '/api/pipelines', '/api/pipelines/demo',
      '/api/pipelines/p%2F1/executions', '/api/executions/e%2F1/stop', '/api/workers', '/api/results/summary',
      '/api/chaos/late-events', '/api/chaos/duplicates', '/api/chaos/malformed', '/api/chaos/slow-sink',
      '/api/chaos/kill-worker', '/api/simulator/state', '/api/compare',
    ])
  })
})
