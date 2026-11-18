import { useState } from 'react'
import type { FlowNode } from './pipelineModel'

const input = (label: string, value: unknown, change: (value: string) => void, type = 'text') => <label className="inspector-field">{label}<input type={type} value={String(value ?? '')} onChange={(event) => change(event.target.value)} /></label>
const asNumber = (value: string) => Number(value)

export function ConfigInspector({ node, onChange, pipeline }: { node: FlowNode | undefined; onChange: (config: Record<string, unknown>) => void; pipeline: string }) {
  const [tab, setTab] = useState<'config' | 'json'>('config')
  if (!node) return <aside className="pipeline-inspector"><h2>Inspector</h2><p>Select an operator to edit its configuration.</p></aside>
  const config = node.data.config
  const update = (key: string, value: unknown) => onChange({ ...config, [key]: value })
  const renderConfig = () => {
    switch (node.type) {
      case 'source': return <>{input('Topic', config.topic, (v) => update('topic', v))}<label className="inspector-field">Start from<select value={String(config.startFrom ?? 'latest')} onChange={(e) => update('startFrom', e.target.value)}><option>latest</option><option>earliest</option></select></label></>
      case 'filter': {
        const conditions = (config.conditions as Array<Record<string, unknown>> | undefined) ?? []
        return <div className="inspector-array">{conditions.map((condition, index) => <fieldset key={index}><legend>Condition {index + 1}</legend>{input('Field', condition.field, (v) => update('conditions', conditions.map((x, i) => i === index ? { ...x, field: v } : x)))}<label className="inspector-field">Operator<select value={String(condition.op ?? '==')} onChange={(e) => update('conditions', conditions.map((x, i) => i === index ? { ...x, op: e.target.value } : x))}>{['==', '!=', '>', '>=', '<', '<='].map((op) => <option key={op}>{op}</option>)}</select></label>{input('Value', condition.value, (v) => update('conditions', conditions.map((x, i) => i === index ? { ...x, value: v } : x)))}</fieldset>)}<button type="button" onClick={() => update('conditions', [...conditions, { field: 'type', op: '==', value: '' }])}>+ Condition</button></div>
      }
      case 'map': return <textarea aria-label="Map operations JSON" value={JSON.stringify(config.ops ?? [], null, 2)} onChange={(e) => { try { update('ops', JSON.parse(e.target.value)) } catch { /* keep the last valid config until JSON parses */ } }} />
      case 'keyBy': return <label className="inspector-field">Field<select value={String(config.field ?? 'key')} onChange={(e) => update('field', e.target.value)}><option>key</option><option>payload.truckId</option></select></label>
      case 'window': return <>{input('Size (ms)', config.sizeMs, (v) => update('sizeMs', asNumber(v)), 'number')}{input('Out of orderness (ms)', config.outOfOrdernessMs ?? 5000, (v) => update('outOfOrdernessMs', asNumber(v)), 'number')}<textarea aria-label="Window aggregations JSON" value={JSON.stringify(config.aggregations ?? [], null, 2)} onChange={(e) => { try { update('aggregations', JSON.parse(e.target.value)) } catch { /* keep the last valid config until JSON parses */ } }} /></>
      case 'rule': return <>{input('Rule ID', config.ruleId, (v) => update('ruleId', v))}<textarea aria-label="Rule condition JSON" value={JSON.stringify(config.condition ?? {}, null, 2)} onChange={(e) => { try { update('condition', JSON.parse(e.target.value)) } catch { /* keep the last valid config until JSON parses */ } }} />{input('For windows', config.forWindows, (v) => update('forWindows', asNumber(v)), 'number')}{input('Message', config.message, (v) => update('message', v))}</>
      case 'sink': return <label className="inspector-field">Kind<select value={String(config.kind ?? 'postgres.windows')} onChange={(e) => update('kind', e.target.value)}><option>postgres.windows</option><option>postgres.alerts</option></select></label>
      default: return null
    }
  }
  return <aside className="pipeline-inspector"><h2>Inspector</h2><div className="inspector-tabs"><button type="button" aria-pressed={tab === 'config'} onClick={() => setTab('config')}>Config</button><button type="button" aria-pressed={tab === 'json'} onClick={() => setTab('json')}>JSON</button></div>{tab === 'json' ? <pre className="pipeline-json">{pipeline}</pre> : <div className="inspector-form"><strong>{node.data.label || node.id}</strong>{renderConfig()}</div>}</aside>
}
