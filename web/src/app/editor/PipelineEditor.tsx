import { useCallback, useMemo, useState, type DragEvent } from 'react'
import { addEdge, Background, Controls, MiniMap, ReactFlow, useEdgesState, useNodesState, type Connection, type Edge, type ReactFlowInstance } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import './editor.css'
import { BlockPalette } from './BlockPalette'
import { ConfigInspector } from './ConfigInspector'
import { getDemo, savePipeline, startExecution, validatePipeline } from './editorApi'
import { OperatorNode } from './OperatorNode'
import { fromFlow, layeredLayout, toFlow, type FlowNode } from './pipelineModel'
import { ValidationList } from './ValidationList'
import type { NodeType, PipelineDefinition, ValidationResult } from './types'

interface Props { open: boolean; onClose(): void; onStarted?(executionId: string): void }
const nodeTypes = { source: OperatorNode, filter: OperatorNode, map: OperatorNode, keyBy: OperatorNode, window: OperatorNode, rule: OperatorNode, sink: OperatorNode }
const emptyPipeline: PipelineDefinition = { name: 'Untitled pipeline', nodes: [], edges: [] }
export default function PipelineEditor({ open, onClose, onStarted }: Props) {

  const [flow, setFlow] = useState<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const [pipeline, setPipeline] = useState<PipelineDefinition>(emptyPipeline)
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const [selectedId, setSelectedId] = useState<string>()
  const [validation, setValidation] = useState<ValidationResult>()
  const [busy, setBusy] = useState(false)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState('')
  const flowPipeline = useMemo(() => fromFlow(pipeline, nodes, edges), [pipeline, nodes, edges])
  const selected = nodes.find((node) => node.id === selectedId)

  const replacePipeline = (definition: PipelineDefinition) => {
    const flow = toFlow(definition)
    const layered = layeredLayout(definition)
    const nextNodes = flow.nodes.map((node) => ({ ...node, position: definition.nodes.find((item) => item.id === node.id)?.position ?? layered.get(node.id) ?? node.position }))
    setPipeline(definition)
    setNodes(nextNodes)
    setEdges(flow.edges)
    setSelectedId(undefined)
    setValidation(undefined)
    setMessage('')
  }
  const modifyNodes = (change: Parameters<typeof onNodesChange>[0]) => { onNodesChange(change); if (change.some((item) => item.type !== 'select')) setValidation(undefined) }
  const modifyEdges = (change: Parameters<typeof onEdgesChange>[0]) => { onEdgesChange(change); setValidation(undefined) }
  const connect = useCallback((connection: Connection) => { setEdges((current) => addEdge({ ...connection, animated: running || busy }, current)); setValidation(undefined) }, [setEdges, running, busy])
  const addNode = (type: NodeType, position: { x: number; y: number }) => {
    const count = nodes.filter((node) => node.type === type).length + 1
    let id = `${type}-${count}`
    while (nodes.some((node) => node.id === id)) id = `${type}-${Number(id.split('-').at(-1)) + 1}`
    const config: Record<string, unknown> = {
      source: { topic: 'truck-events', startFrom: 'latest' }, filter: { conditions: [{ field: 'type', op: '==', value: 'truck.position' }] },
      map: { ops: [{ op: 'scale', field: 'payload.speedKmh', factor: 1, as: 'payload.speedKmh' }] }, keyBy: { field: 'key' },
      window: { kind: 'tumbling', sizeMs: 60000, outOfOrdernessMs: 5000, aggregations: [{ name: 'count', fn: 'count' }] },
      rule: { ruleId: 'new-rule', condition: { field: 'count', op: '>', value: 0 }, forWindows: 1, message: '{key} alert' }, sink: { kind: 'postgres.windows' },
    }[type]
    const node: FlowNode = { id, type, position, data: { label: type === 'keyBy' ? 'Key-by' : type[0]!.toUpperCase() + type.slice(1), config } }
    setNodes((current) => [...current, node])
    setSelectedId(id)
    setValidation(undefined)
  }
  const drop = (event: DragEvent) => {
    event.preventDefault()
    const type = event.dataTransfer.getData('application/aether-node') as NodeType
    if (type in nodeTypes && flow) addNode(type, flow.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
  }
  const validate = async () => {
    setBusy(true); setMessage('Validating…')
    try {
      const result = await validatePipeline(flowPipeline)
      setValidation(result)
      setNodes((current) => current.map((node) => ({ ...node, data: { ...node.data, invalid: result.errors.some((error) => error.nodeId === node.id) } })))
      setMessage(result.valid && result.plan ? `Valid - ${result.plan.operators.length} operators, ${result.plan.partitions} partitions` : '')
    } catch (error) { setValidation(undefined); setMessage(error instanceof Error ? error.message : 'Validation failed') }
    finally { setBusy(false) }
  }
  const run = async () => {
    if (!validation?.valid) return
    setRunning(true); setBusy(true); setMessage('Starting…')
    try {
      const saved = await savePipeline(flowPipeline)
      const execution = await startExecution(saved.id ?? flowPipeline.id ?? '');
      onStarted?.(execution.id)
      onClose()
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not start pipeline') }
    finally { setRunning(false); setBusy(false) }
  }
  if (!open) return null
  const nodeWithStatus = nodes.map((node) => ({ ...node, data: { ...node.data, invalid: validation?.errors.some((error) => error.nodeId === node.id) ?? false } }))
  return <section className="pipeline-editor" role="dialog" aria-modal="true" aria-label="Pipeline editor">
    <header className="pipeline-editor__header"><h1>Pipeline editor</h1><button type="button" onClick={() => void getDemo().then(replacePipeline).catch((error: unknown) => setMessage(error instanceof Error ? error.message : 'Could not load demo'))}>Load demo</button><button type="button" onClick={() => void validate()} disabled={busy}>Validate</button><button className="clay-button clay-button--primary" type="button" onClick={() => void run()} disabled={!validation?.valid || busy}>Run</button><button type="button" aria-label="Close pipeline editor" onClick={onClose}>×</button></header>
    <div className="pipeline-editor__body"><BlockPalette /><div className="pipeline-canvas" onDrop={drop} onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = 'move' }}>
      <ReactFlow<FlowNode> nodes={nodeWithStatus} edges={edges.map((edge) => ({ ...edge, animated: running || busy }))} onInit={setFlow} onNodesChange={modifyNodes} onEdgesChange={modifyEdges} onConnect={connect} onNodeClick={(_, node) => setSelectedId(node.id)} onPaneClick={() => setSelectedId(undefined)} nodeTypes={nodeTypes} deleteKeyCode={['Backspace', 'Delete']} fitView proOptions={{ hideAttribution: true }}>
        <Background color="#334155" gap={20} /><Controls /><MiniMap pannable zoomable nodeColor={(node) => node.type === 'sink' ? '#4ade80' : node.type === 'rule' ? '#f87171' : '#5eead4'} />
      </ReactFlow>
    </div><ConfigInspector node={selected} onChange={(config) => { setNodes((current) => current.map((node) => node.id === selectedId ? { ...node, data: { ...node.data, config } } : node)); setValidation(undefined) }} pipeline={JSON.stringify(flowPipeline, null, 2)} /></div>
    <footer className="pipeline-editor__feedback" aria-live="polite">{message && <p className={validation?.valid ? 'pipeline-success' : validation ? 'pipeline-failure' : ''}>{message}</p>}{validation && <><ValidationList errors={validation.errors} onSelect={(id) => setSelectedId(id)} />{validation.valid && validation.plan && <p>Plan order: {validation.plan.operators.map((operator) => operator.id).join(' → ')}</p>}</>}</footer>
  </section>
}
