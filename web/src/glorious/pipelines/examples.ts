import type { Pipeline } from './model'

// Hand-written pipeline definitions for the editor and exact-match Ask AI offline fallback.
// These are saved workflows, not generated pipelines or result data.
export interface PipelineExample { id: string; prompt: string; pipeline: Pipeline }

export const EXAMPLES: PipelineExample[] = [
  {
    id: 'speeding',
    prompt: 'Alert me when any truck goes over 60 km/h',
    pipeline: {
      name: 'Speeding alert',
      nodes: [
        { id: 'n1', type: 'filter', params: { field: 'speedKmh', op: '>', value: 60 } },
        { id: 'n2', type: 'thresholdAlert', params: { field: 'speedKmh', op: '>', value: 60, message: 'Truck over 60 km/h' } },
        { id: 'n3', type: 'output', params: { name: 'Speeding trucks' } },
      ],
      edges: [{ from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }],
    },
  },
  {
    id: 'average',
    prompt: 'Average speed per truck over the last 30 seconds',
    pipeline: {
      name: 'Average speed per truck',
      nodes: [
        { id: 'n1', type: 'windowAverage', params: { field: 'speedKmh', windowSec: 30, groupBy: 'truckId' } },
        { id: 'n2', type: 'output', params: { name: 'Average speed per truck' } },
      ],
      edges: [{ from: 'n1', to: 'n2' }],
    },
  },
  {
    id: 'partition',
    prompt: 'Show only trucks on partition 2 with their speed',
    pipeline: {
      name: 'Partition 2 speeds',
      nodes: [
        { id: 'n1', type: 'filter', params: { field: 'partition', op: '==', value: 2 } },
        { id: 'n2', type: 'pickFields', params: { fields: ['truckId', 'speedKmh', 'partition'] } },
        { id: 'n3', type: 'output', params: { name: 'Partition 2 trucks' } },
      ],
      edges: [{ from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }],
    },
  },
]
