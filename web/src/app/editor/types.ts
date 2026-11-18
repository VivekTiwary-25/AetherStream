export type NodeType = 'source' | 'filter' | 'map' | 'keyBy' | 'window' | 'rule' | 'sink'

export interface PipelineNode {
  id: string
  type: NodeType | string
  label?: string
  position?: { x: number; y: number }
  config: Record<string, unknown>
}

export interface PipelineEdge {
  from: string
  to: string
}

export interface PipelineDefinition {
  id?: string
  version?: number
  name: string
  nodes: PipelineNode[]
  edges: PipelineEdge[]
}

export interface CompilerError {
  nodeId: string | null
  code: string
  message: string
}

export interface ExecutionPlan {
  pipelineId: string
  pipelineVersion: number
  topic: string
  partitions: number
  startFrom: string
  operators: Array<{ id: string; type: string; config: Record<string, unknown>; inputs: string[]; outputType: string }>
}

export interface ValidationResult {
  valid: boolean
  errors: CompilerError[]
  plan: ExecutionPlan | null
}
