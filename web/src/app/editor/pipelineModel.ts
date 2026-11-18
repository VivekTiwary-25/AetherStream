import type { Edge, Node } from '@xyflow/react'
import type { PipelineDefinition, PipelineNode, NodeType } from './types'

export type FlowNode = Node<{ label?: string; config: Record<string, unknown>; invalid?: boolean }, NodeType>

export function toFlow(pipeline: PipelineDefinition): { nodes: FlowNode[]; edges: Edge[] } {
  const positions = layeredLayout(pipeline)
  return {
    nodes: pipeline.nodes.map((node) => ({
      id: node.id,
      type: node.type as NodeType,
      position: node.position ?? positions.get(node.id) ?? { x: 0, y: 0 },
      data: { label: node.label, config: node.config ?? {} },
    })),
    edges: pipeline.edges.map((edge, index) => ({ id: `${edge.from}-${edge.to}-${index}`, source: edge.from, target: edge.to, animated: false })),
  }
}

export function fromFlow(pipeline: PipelineDefinition, nodes: FlowNode[], edges: Edge[]): PipelineDefinition {
  return {
    ...pipeline,
    nodes: nodes.map((node) => ({
      ...pipeline.nodes.find((candidate) => candidate.id === node.id),
      id: node.id,
      type: node.type ?? 'filter',
      label: node.data.label,
      position: node.position,
      config: node.data.config,
    } as PipelineNode)),
    edges: edges.map((edge) => ({ from: edge.source, to: edge.target })),
  }
}

export function withoutPositions(pipeline: PipelineDefinition): PipelineDefinition {
  return { ...pipeline, nodes: pipeline.nodes.map((node) => { const result = { ...node }; delete result.position; return result }) }
}

export function layeredLayout(pipeline: Pick<PipelineDefinition, 'nodes' | 'edges'>): Map<string, { x: number; y: number }> {
  const incoming = new Map(pipeline.nodes.map((node) => [node.id, 0]))
  const outgoing = new Map(pipeline.nodes.map((node) => [node.id, [] as string[]]))
  for (const edge of pipeline.edges) {
    if (!incoming.has(edge.from) || !incoming.has(edge.to)) continue
    outgoing.get(edge.from)?.push(edge.to)
    incoming.set(edge.to, (incoming.get(edge.to) ?? 0) + 1)
  }
  const depth = new Map<string, number>()
  const queue = pipeline.nodes.filter((node) => incoming.get(node.id) === 0).map((node) => node.id)
  for (const id of queue) depth.set(id, 0)
  for (let index = 0; index < queue.length; index += 1) {
    const id = queue[index]!
    for (const child of outgoing.get(id) ?? []) {
      depth.set(child, Math.max(depth.get(child) ?? 0, (depth.get(id) ?? 0) + 1))
      incoming.set(child, (incoming.get(child) ?? 1) - 1)
      if (incoming.get(child) === 0) queue.push(child)
    }
  }
  const rows = new Map<number, number>()
  return new Map(pipeline.nodes.map((node) => {
    const column = depth.get(node.id) ?? 0
    const row = rows.get(column) ?? 0
    rows.set(column, row + 1)
    return [node.id, { x: 70 + column * 240, y: 65 + row * 150 }]
  }))
}
