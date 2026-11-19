import { useId } from 'react'
import { DISPLAY_NAMES, GROUP_BY, OPS, STRING_OPS, type FieldInfo, type Op, type PipelineNode } from './model'
import type { StepNode } from './graph'

interface Props {
  node: StepNode
  fields: FieldInfo[]
  candidates: string[]
  input: string
  onParams: (params: PipelineNode['params']) => void
  onInput: (from: string) => void
  onDelete: () => void
}

/** Side panel: edit the selected step's params with plain form controls (fully keyboard accessible). */
export function StepInspector({ node, fields, candidates, input, onParams, onInput, onDelete }: Props) {
  const base = useId()
  const p = node.data.params as unknown as Record<string, unknown>
  const set = (patch: Record<string, unknown>) => onParams({ ...p, ...patch } as unknown as PipelineNode['params'])
  const typeOf = (name: unknown) => fields.find((field) => field.name === name)?.type
  const numeric = fields.filter((field) => field.type === 'number')
  const fieldOptions = (list: FieldInfo[]) => list.map((field) => <option key={field.name} value={field.name}>{field.name} ({field.type})</option>)

  const comparison = () => {
    const fieldType = typeOf(p.field)
    const ops = fieldType === 'string' ? STRING_OPS : OPS
    return <>
      <label htmlFor={`${base}-field`}>Field</label>
      <select id={`${base}-field`} value={String(p.field ?? '')} onChange={(event) => {
        const nextType = typeOf(event.target.value)
        const op = nextType === 'string' && !STRING_OPS.includes(p.op as Op) ? '==' : p.op
        const value = nextType === 'number' ? (Number(p.value) || 0) : String(p.value ?? '')
        set({ field: event.target.value, op, value })
      }}>{fieldOptions(fields)}</select>
      <label htmlFor={`${base}-op`}>Comparison</label>
      <select id={`${base}-op`} value={String(p.op ?? '>')} onChange={(event) => set({ op: event.target.value })}>
        {ops.map((op) => <option key={op} value={op}>{op}</option>)}
      </select>
      <label htmlFor={`${base}-value`}>Value</label>
      {fieldType === 'number'
        ? <input id={`${base}-value`} type="number" value={Number.isFinite(Number(p.value)) ? String(p.value) : ''} onChange={(event) => set({ value: event.target.value === '' ? 0 : Number(event.target.value) })} />
        : <input id={`${base}-value`} type="text" value={String(p.value ?? '')} onChange={(event) => set({ value: event.target.value })} />}
    </>
  }

  return <div className="gl-inspector__form">
    <h2>{DISPLAY_NAMES[node.data.kind]} <small className="gl-muted">{node.id}</small></h2>
    <label htmlFor={`${base}-input`}>Input</label>
    <select id={`${base}-input`} value={input} onChange={(event) => onInput(event.target.value)}>
      <option value="">Live truck positions (first step)</option>
      {candidates.map((id) => <option key={id} value={id}>{id}</option>)}
    </select>

    {node.data.kind === 'filter' && comparison()}
    {node.data.kind === 'thresholdAlert' && <>
      {comparison()}
      <label htmlFor={`${base}-message`}>Alert message</label>
      <input id={`${base}-message`} type="text" value={String(p.message ?? '')} onChange={(event) => set({ message: event.target.value })} />
    </>}
    {node.data.kind === 'pickFields' && <fieldset>
      <legend>Fields to keep</legend>
      {fields.map((field) => {
        const selected = Array.isArray(p.fields) ? p.fields as string[] : []
        return <label key={field.name} className="gl-check"><input type="checkbox" checked={selected.includes(field.name)}
          onChange={(event) => set({ fields: event.target.checked ? [...selected, field.name] : selected.filter((name) => name !== field.name) })} /> {field.name}</label>
      })}
    </fieldset>}
    {node.data.kind === 'windowAverage' && <>
      <label htmlFor={`${base}-field`}>Field to average</label>
      <select id={`${base}-field`} value={String(p.field ?? '')} onChange={(event) => set({ field: event.target.value })}>{fieldOptions(numeric)}</select>
      <label htmlFor={`${base}-window`}>Window (seconds)</label>
      <input id={`${base}-window`} type="number" min={1} value={String(p.windowSec ?? '')} onChange={(event) => set({ windowSec: event.target.value === '' ? 0 : Number(event.target.value) })} />
      <label htmlFor={`${base}-group`}>Group by</label>
      <select id={`${base}-group`} value={String(p.groupBy ?? '')} onChange={(event) => {
        const next = { ...p }
        if (event.target.value) next.groupBy = event.target.value
        else delete next.groupBy
        onParams(next as unknown as PipelineNode['params'])
      }}>
        <option value="">(all trucks together)</option>
        {GROUP_BY.map((name) => <option key={name} value={name}>{name}</option>)}
      </select>
    </>}
    {node.data.kind === 'output' && <>
      <label htmlFor={`${base}-name`}>Output name</label>
      <input id={`${base}-name`} type="text" value={String(p.name ?? '')} onChange={(event) => set({ name: event.target.value })} />
    </>}
    <button type="button" className="gl-clay-button gl-danger" onClick={onDelete}>Delete step</button>
  </div>
}
