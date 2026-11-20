import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { ApiError, describeError, type ResultsPage } from '../api'
import { ErrorList } from '../ErrorList'
import { EXAMPLES } from '../examples'
import { flowToPipeline, nextNodeId, pipelineToFlow } from '../graph'
import { describeNode, describePipeline, isPipeline, orderNodes, readHandoff, storeHandoff, type Pipeline } from '../model'
import { initialRunView, MAX_RESULTS, resultColumns, runReducer } from '../results'

const speeding = EXAMPLES[0]!.pipeline

describe('pipeline description', () => {
  it('describes each node type in plain words', () => {
    expect(describeNode({ type: 'filter', params: { field: 'speedKmh', op: '>', value: 60 } })).toBe('Filter: keep events where speedKmh > 60')
    expect(describeNode({ type: 'filter', params: { field: 'status', op: '==', value: 'STOPPED' } })).toBe('Filter: keep events where status == "STOPPED"')
    expect(describeNode({ type: 'pickFields', params: { fields: ['truckId', 'speedKmh'] } })).toBe('Pick Field: keep only truckId, speedKmh')
    expect(describeNode({ type: 'windowAverage', params: { field: 'speedKmh', windowSec: 30, groupBy: 'truckId' } })).toBe('Window Average: average speedKmh over 30 s per truckId')
    expect(describeNode({ type: 'windowAverage', params: { field: 'speedKmh', windowSec: 10 } })).toBe('Window Average: average speedKmh over 10 s')
    expect(describeNode({ type: 'thresholdAlert', params: { field: 'speedKmh', op: '>', value: 60, message: 'Too fast' } })).toBe('Threshold Alert: alert when speedKmh > 60 - "Too fast"')
    expect(describeNode({ type: 'output', params: { name: 'Speeding trucks' } })).toBe('Output: show as "Speeding trucks"')
    expect(describeNode({ type: 'mystery', params: {} })).toBe('Unknown step (mystery)')
  })

  it('orders steps by data flow even when listed out of order', () => {
    const shuffled: Pipeline = { ...speeding, nodes: [speeding.nodes[2]!, speeding.nodes[0]!, speeding.nodes[1]!] }
    expect(orderNodes(shuffled).map((node) => node.id)).toEqual(['n1', 'n2', 'n3'])
    expect(describePipeline(shuffled).map((step) => step.inputs)).toEqual([[], ['n1'], ['n2']])
  })

  it('accepts only pipeline-shaped JSON', () => {
    expect(isPipeline(speeding)).toBe(true)
    expect(isPipeline({ name: 'x', nodes: [{ id: 'a' }], edges: [] })).toBe(false)
    expect(isPipeline(null)).toBe(false)
  })

  it('hands a pipeline to the editor through localStorage', () => {
    expect(storeHandoff(speeding)).toBe(true)
    expect(readHandoff()).toEqual(speeding)
    window.localStorage.setItem('glorious.pipelines.fromAi', '{not json')
    expect(readHandoff()).toBeNull()
  })
})

describe('editor graph <-> pipeline JSON', () => {
  it.each(EXAMPLES.map((example) => [example.id, example.pipeline] as const))('round-trips %s', (_, pipeline) => {
    const flow = pipelineToFlow(pipeline)
    expect(flowToPipeline(pipeline.name, flow.nodes, flow.edges)).toEqual(pipeline)
  })

  it('lays steps out top to bottom', () => {
    const { nodes } = pipelineToFlow(speeding)
    expect(nodes[0]!.position.y).toBeLessThan(nodes[1]!.position.y)
    expect(nodes[1]!.position.y).toBeLessThan(nodes[2]!.position.y)
  })

  it('picks the next free node id', () => {
    expect(nextNodeId([])).toBe('n1')
    expect(nextNodeId([{ id: 'n1' }, { id: 'n7' }, { id: 'custom' }])).toBe('n8')
  })
})

const page = (results: Array<{ seq: number }>, extra: Partial<ResultsPage> = {}): ResultsPage => ({
  runId: 'r1', state: 'RUNNING', eventsIn: 10, startedAt: 1,
  results: results.map(({ seq }) => ({ seq, ts: 1000 + seq, output: 'out', kind: 'row' as const, data: { truckId: `T${seq}` } })), ...extra,
})

describe('results polling reducer', () => {
  it('starts at the API’s non-negative cursor and receives the first result', () => {
    let view = initialRunView('r1')
    expect(view.after).toBe(0)
    view = runReducer(view, { type: 'page', page: page([{ seq: 1 }, { seq: 2 }]) })
    expect(view.after).toBe(2)
    expect(view.results.map((result) => result.seq)).toEqual([1, 2])
  })

  it('ignores duplicates, sorts out-of-order results and keeps after on an empty page', () => {
    let view = runReducer(initialRunView('r1'), { type: 'page', page: page([{ seq: 1 }]) })
    view = runReducer(view, { type: 'page', page: page([{ seq: 3 }, { seq: 1 }, { seq: 2 }]) })
    expect(view.results.map((result) => result.seq)).toEqual([1, 2, 3])
    view = runReducer(view, { type: 'page', page: page([], { eventsIn: 42 }) })
    expect(view.after).toBe(3)
    expect(view.eventsIn).toBe(42)
  })

  it('caps the buffer and ignores pages from another run', () => {
    const view = runReducer(initialRunView('r1'), { type: 'page', page: page(Array.from({ length: MAX_RESULTS + 20 }, (_, seq) => ({ seq }))) })
    expect(view.results).toHaveLength(MAX_RESULTS)
    expect(view.results[0]!.seq).toBe(20)
    const same = runReducer(view, { type: 'page', page: page([{ seq: 9999 }], { runId: 'other' }) })
    expect(same).toBe(view)
  })

  it('records FAILED with its error and clears a poll error on the next page', () => {
    let view = runReducer(initialRunView('r1'), { type: 'pollError', message: 'Pipeline service unavailable' })
    expect(view.pollError).toBe('Pipeline service unavailable')
    view = runReducer(view, { type: 'page', page: page([], { state: 'FAILED', error: 'boom' }) })
    expect(view).toMatchObject({ state: 'FAILED', error: 'boom', pollError: undefined })
  })

  it('collects table columns from result data', () => {
    expect(resultColumns([
      { seq: 1, ts: 1, output: 'o', kind: 'row', data: { truckId: 'T1', speedKmh: 61 } },
      { seq: 2, ts: 2, output: 'o', kind: 'average', data: { group: 'T1', avg: 50, count: 3 } },
    ])).toEqual(['truckId', 'speedKmh', 'group', 'avg', 'count'])
  })
})

describe('error rendering', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it.each(['pipeline', 'ai'] as const)('shows validation errors from a %s 400 with their node ids', (service) => {
    const view = describeError(new ApiError(400, { valid: false, errors: [{ nodeId: 'n2', message: 'Unknown field "speed"' }, { message: 'At least one output is required' }] }), service)
    expect(view.message).toBe('The pipeline is not valid')
    render(<ErrorList error={view} />)
    expect(screen.getByRole('alert').textContent).toContain('n2: Unknown field "speed"')
    expect(screen.getByRole('alert').textContent).toContain('At least one output is required')
  })

  it('says the service is unavailable on 502, 404 and network errors', () => {
    expect(describeError(new ApiError(502, null)).message).toBe('Pipeline service unavailable')
    expect(describeError(new ApiError(404, null)).message).toBe('Pipeline service unavailable')
    expect(describeError(new ApiError(0, null), 'ai').message).toBe('AI service unavailable')
    render(<ErrorList error={describeError(new ApiError(502, null))} />)
    expect(screen.getByRole('alert').textContent).toContain('Pipeline service unavailable')
  })

  it('explains a busy AI and other server errors honestly', () => {
    expect(describeError(new ApiError(429, { error: 'busy' }), 'ai').message).toMatch(/busy/)
    expect(describeError(new ApiError(429, {})).message).toBe('The pipeline service is busy. Try again in a moment.')
    expect(describeError(new ApiError(500, { error: 'disk full' })).message).toBe('Request failed (500): disk full')
  })
})
