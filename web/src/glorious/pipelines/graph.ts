import type { Edge, Node } from '@xyflow/react'
import { layeredLayout } from '../../app/editor/pipelineModel'
import type { NodeKind, Pipeline, PipelineNode } from './model'

export interface StepData extends Record<string, unknown> { kind: NodeKind; params: PipelineNode['params']; errors?: string[] }
export type StepNode = Node<StepData, 'step'>

/** Pipeline JSON → React Flow nodes/edges. Layout is top-to-bottom (the old editor's layered columns, rotated). */
export function pipelineToFlow(pipeline: Pipeline): { nodes: StepNode[]; edges: Edge[] } {
  const layout = layeredLayout({ nodes: pipeline.nodes.map((node) => ({ id: node.id, type: node.type, config: {} })), edges: pipeline.edges })
  return {
    nodes: pipeline.nodes.map((node) => {
      const at = layout.get(node.id) ?? { x: 70, y: 65 }
      return { id: node.id, type: 'step', position: { x: 40 + (at.y - 65) * 2, y: 30 + ((at.x - 70) / 240) * 150 }, data: { kind: node.type, params: node.params } }
    }),
    edges: pipeline.edges.map((edge) => ({ id: `${edge.from}->${edge.to}`, source: edge.from, target: edge.to })),
  }
}

/** React Flow state → Pipeline JSON (positions and UI-only data are dropped). */
export function flowToPipeline(name: string, nodes: StepNode[], edges: Edge[]): Pipeline {
  return {
    name,
    nodes: nodes.map((node) => ({ id: node.id, type: node.data.kind, params: node.data.params }) as PipelineNode),
    edges: edges.map((edge) => ({ from: edge.source, to: edge.target })),
  }
}

export function nextNodeId(nodes: Array<{ id: string }>): string {
  const max = nodes.reduce((highest, node) => { const match = /^n(\d+)$/.exec(node.id); return match ? Math.max(highest, Number(match[1])) : highest }, 0)
  return `n${max + 1}`
}
