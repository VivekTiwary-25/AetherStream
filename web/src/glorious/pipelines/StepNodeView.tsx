import { Handle, Position, type NodeProps } from '@xyflow/react'
import { describeNode, DISPLAY_NAMES } from './model'
import type { StepNode } from './graph'

/** Glass chip on the canvas: display name, params in plain words, and validation errors on the node. */
export function StepNodeView({ id, data, selected }: NodeProps<StepNode>) {
  const errors = data.errors ?? []
  return <div className={`gl-step gl-step--${data.kind}${selected ? ' gl-step--selected' : ''}${errors.length ? ' gl-step--invalid' : ''}`}>
    <Handle type="target" position={Position.Top} aria-label={`Input of ${id}`} />
    <div className="gl-step__title"><strong>{DISPLAY_NAMES[data.kind]}</strong><small>{id}</small></div>
    <p className="gl-step__text">{describeNode({ type: data.kind, params: data.params }).replace(/^[^:]+: /, '')}</p>
    {errors.length > 0 && <ul className="gl-step__errors" aria-label={`Errors on ${id}`}>{errors.map((message, index) => <li key={index}>Error: {message}</li>)}</ul>}
    {data.kind !== 'output' && <Handle type="source" position={Position.Bottom} aria-label={`Output of ${id}`} />}
  </div>
}
