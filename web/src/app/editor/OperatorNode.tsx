import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { NodeType } from './types'
import type { FlowNode } from './pipelineModel'

const titles: Record<NodeType, string> = { source: 'Source', filter: 'Filter', map: 'Map', keyBy: 'Key-by', window: 'Window', rule: 'Rule', sink: 'Sink' }
export function OperatorNode({ id, type, data, selected }: NodeProps<FlowNode>) {
  const kind = (type ?? 'filter') as NodeType
  return <div className={`operator-node operator-node--${kind}${selected ? ' is-selected' : ''}${data.invalid ? ' is-invalid' : ''}`}>
    {kind !== 'source' && <Handle type="target" position={Position.Left} aria-label={`Input ${id}`} />}
    <div className="operator-node__title"><span className={`pipeline-icon pipeline-icon--${kind}`} aria-hidden="true">{({ source: '◉', filter: '⌕', map: '⇄', keyBy: '⌘', window: '▤', rule: '◇', sink: '↗' })[kind]}</span><strong>{data.label || titles[kind]}</strong></div>
    {data.invalid && <span className="operator-node__error" aria-label="Validation error">!</span>}
    <small>{id}</small>
    {kind !== 'sink' && <Handle type="source" position={Position.Right} aria-label={`Output ${id}`} />}
  </div>
}
