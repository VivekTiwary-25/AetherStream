import { describe, expect, it } from 'vitest'
import { fromFlow, layeredLayout, toFlow, withoutPositions } from '../pipelineModel'
import type { PipelineDefinition } from '../types'

const demo: PipelineDefinition = { id: 'demo', version: 1, name: 'Demo', nodes: [
  { id: 'src', type: 'source', config: { topic: 'events', startFrom: 'latest' } },
  { id: 'filter', type: 'filter', config: { conditions: [{ field: 'type', op: '==', value: 'truck.position' }] } },
  { id: 'sink', type: 'sink', config: { kind: 'postgres.windows' } },
], edges: [{ from: 'src', to: 'filter' }, { from: 'filter', to: 'sink' }] }

describe('pipeline model', () => {
  it('round trips pipeline JSON without editor positions', () => {
    const flow = toFlow(demo)
    expect(withoutPositions(fromFlow(demo, flow.nodes, flow.edges))).toEqual(demo)
  })
  it('lays out every edge target to the right of its source', () => {
    const positions = layeredLayout(demo)
    for (const edge of demo.edges) expect(positions.get(edge.to)!.x).toBeGreaterThan(positions.get(edge.from)!.x)
  })
})
