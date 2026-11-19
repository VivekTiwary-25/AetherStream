// Pipeline model for the Glorious Pipelines track (Track D shared contract).
// Exactly five node types; the implicit source is the live truck position stream.

export type NodeKind = 'filter' | 'pickFields' | 'windowAverage' | 'thresholdAlert' | 'output'
export type Op = '>' | '>=' | '<' | '<=' | '==' | '!='
export type FieldType = 'string' | 'number'

export interface FilterParams { field: string; op: Op; value: string | number }
export interface PickFieldsParams { fields: string[] }
export interface WindowAverageParams { field: string; windowSec: number; groupBy?: string }
export interface ThresholdAlertParams { field: string; op: Op; value: string | number; message: string }
export interface OutputParams { name: string }

export type PipelineNode =
  | { id: string; type: 'filter'; params: FilterParams }
  | { id: string; type: 'pickFields'; params: PickFieldsParams }
  | { id: string; type: 'windowAverage'; params: WindowAverageParams }
  | { id: string; type: 'thresholdAlert'; params: ThresholdAlertParams }
  | { id: string; type: 'output'; params: OutputParams }

export interface PipelineEdge { from: string; to: string }
export interface Pipeline { name: string; nodes: PipelineNode[]; edges: PipelineEdge[] }

export interface FieldInfo { name: string; type: FieldType; description?: string }

export const NODE_KINDS: NodeKind[] = ['filter', 'pickFields', 'windowAverage', 'thresholdAlert', 'output']
export const DISPLAY_NAMES: Record<NodeKind, string> = {
  filter: 'Filter', pickFields: 'Pick Field', windowAverage: 'Window Average', thresholdAlert: 'Threshold Alert', output: 'Output',
}
export const OPS: Op[] = ['>', '>=', '<', '<=', '==', '!=']
export const STRING_OPS: Op[] = ['==', '!=']
export const GROUP_BY = ['truckId', 'routeId', 'status', 'partition']
export const MAX_NODES = 12

/** The real truck position fields from the shared contract (RECON.md i). Used only when
 * GET /api/glorious/pipelines/fields is unreachable, so the editor can still be used. */
export const CONTRACT_FIELDS: FieldInfo[] = [
  { name: 'truckId', type: 'string' }, { name: 'speedKmh', type: 'number' }, { name: 'heading', type: 'number' },
  { name: 'x', type: 'number' }, { name: 'y', type: 'number' }, { name: 'routeId', type: 'string' },
  { name: 'status', type: 'string' }, { name: 'partition', type: 'number' }, { name: 'eventTime', type: 'number' },
]

export const isNodeKind = (value: unknown): value is NodeKind => typeof value === 'string' && (NODE_KINDS as string[]).includes(value)

export function defaultParams(kind: NodeKind): PipelineNode['params'] {
  switch (kind) {
    case 'filter': return { field: 'speedKmh', op: '>', value: 60 }
    case 'pickFields': return { fields: ['truckId', 'speedKmh'] }
    case 'windowAverage': return { field: 'speedKmh', windowSec: 30, groupBy: 'truckId' }
    case 'thresholdAlert': return { field: 'speedKmh', op: '>', value: 60, message: 'Truck over 60 km/h' }
    case 'output': return { name: 'Results' }
  }
}

const formatValue = (value: unknown) => typeof value === 'string' ? `"${value}"` : String(value)

/** Plain-words description of one node, e.g. "Filter: speedKmh > 60". */
export function describeNode(node: { type: string; params: Record<string, unknown> | object }): string {
  const p = node.params as Record<string, unknown>
  if (!isNodeKind(node.type)) return `Unknown step (${node.type})`
  const title = DISPLAY_NAMES[node.type]
  switch (node.type) {
    case 'filter': return `${title}: keep events where ${p.field} ${p.op} ${formatValue(p.value)}`
    case 'pickFields': return `${title}: keep only ${Array.isArray(p.fields) && p.fields.length ? p.fields.join(', ') : '(no fields)'}`
    case 'windowAverage': return `${title}: average ${p.field} over ${p.windowSec} s${p.groupBy ? ` per ${p.groupBy}` : ''}`
    case 'thresholdAlert': return `${title}: alert when ${p.field} ${p.op} ${formatValue(p.value)} - "${p.message ?? ''}"`
    case 'output': return `${title}: show as "${p.name ?? ''}"`
  }
}

/** Nodes in data-flow order (topological; ties keep their listed order). Cycles fall back to listed order. */
export function orderNodes(pipeline: Pick<Pipeline, 'nodes' | 'edges'>): PipelineNode[] {
  const ids = new Set(pipeline.nodes.map((node) => node.id))
  const indegree = new Map(pipeline.nodes.map((node) => [node.id, 0]))
  for (const edge of pipeline.edges) if (ids.has(edge.from) && ids.has(edge.to)) indegree.set(edge.to, (indegree.get(edge.to) ?? 0) + 1)
  const result: PipelineNode[] = []
  const done = new Set<string>()
  while (result.length < pipeline.nodes.length) {
    const next = pipeline.nodes.find((node) => !done.has(node.id) && indegree.get(node.id) === 0)
    if (!next) { result.push(...pipeline.nodes.filter((node) => !done.has(node.id))); break }
    done.add(next.id)
    result.push(next)
    for (const edge of pipeline.edges) if (edge.from === next.id && ids.has(edge.to)) indegree.set(edge.to, (indegree.get(edge.to) ?? 1) - 1)
  }
  return result
}

/** Ordered steps with their inputs, for the readable review list. */
export function describePipeline(pipeline: Pick<Pipeline, 'nodes' | 'edges'>): Array<{ id: string; type: string; text: string; inputs: string[] }> {
  return orderNodes(pipeline).map((node) => ({
    id: node.id, type: node.type, text: describeNode(node),
    inputs: pipeline.edges.filter((edge) => edge.to === node.id).map((edge) => edge.from),
  }))
}

/** Loose shape check for JSON coming from storage or the AI bridge. The server validates for real. */
export function isPipeline(value: unknown): value is Pipeline {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<Pipeline>
  return typeof candidate.name === 'string' && Array.isArray(candidate.nodes) && Array.isArray(candidate.edges)
    && candidate.nodes.every((node) => !!node && typeof node.id === 'string' && typeof node.type === 'string' && !!node.params && typeof node.params === 'object')
    && candidate.edges.every((edge) => !!edge && typeof edge.from === 'string' && typeof edge.to === 'string')
}

export const AI_HANDOFF_KEY = 'glorious.pipelines.fromAi'

export function storeHandoff(pipeline: Pipeline): boolean {
  try { window.localStorage.setItem(AI_HANDOFF_KEY, JSON.stringify(pipeline)); return true } catch { return false }
}

export function readHandoff(): Pipeline | null {
  try {
    const raw = window.localStorage.getItem(AI_HANDOFF_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    return isPipeline(parsed) ? parsed : null
  } catch { return null }
}
