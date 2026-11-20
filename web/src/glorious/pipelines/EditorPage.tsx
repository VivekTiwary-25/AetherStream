import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { addEdge, Background, Controls, ReactFlow, useEdgesState, useNodesState, type Connection, type Edge, type ReactFlowInstance } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { ClayButton } from '../shell/ClayButton'
import { GlassSurface } from '../shell/GlassSurface'
import { describeError, getFields, validatePipeline, type ErrorView, type ValidationResult } from './api'
import { ErrorList } from './ErrorList'
import { EXAMPLES } from './examples'
import { flowToPipeline, nextNodeId, pipelineToFlow, type StepNode } from './graph'
import { CONTRACT_FIELDS, defaultParams, DISPLAY_NAMES, isNodeKind, MAX_NODES, NODE_KINDS, readEditorDraft, readHandoff, storeEditorDraft, type FieldInfo, type NodeKind, type Pipeline, type PipelineNode } from './model'
import { StepInspector } from './StepInspector'
import { StepNodeView } from './StepNodeView'
import { useRunPipeline } from './useRunPipeline'
import './pipelines.css'

const nodeTypes = { step: StepNodeView }
const DRAG_TYPE = 'application/x-glorious-step'

function initialPipeline(): { pipeline: Pipeline; note: string } {
  const draft = readEditorDraft()
  if (draft) return { pipeline: draft, note: 'Restored this tab’s pipeline draft.' }
  const from = new URLSearchParams(window.location.search).get('from')
  if (from === 'ai') {
    const handed = readHandoff()
    if (handed) return { pipeline: handed, note: 'Loaded the pipeline from Ask AI.' }
    return { pipeline: { name: 'Untitled pipeline', nodes: [], edges: [] }, note: 'No pipeline was handed over from Ask AI. Start from a step or load an example.' }
  }
  return { pipeline: { name: 'Untitled pipeline', nodes: [], edges: [] }, note: '' }
}

export function EditorPage() {
  const [start] = useState(initialPipeline)
  const startFlow = useMemo(() => pipelineToFlow(start.pipeline), [start])
  const [name, setName] = useState(start.pipeline.name)
  const [nodes, setNodes, onNodesChange] = useNodesState<StepNode>(startFlow.nodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(startFlow.edges)
  const [selectedId, setSelectedId] = useState<string>()
  const [flow, setFlow] = useState<ReactFlowInstance<StepNode, Edge> | null>(null)
  const [canvasKey, setCanvasKey] = useState(0)
  const [note, setNote] = useState(start.note)
  const [fields, setFields] = useState<FieldInfo[]>(CONTRACT_FIELDS)
  const [fieldsNote, setFieldsNote] = useState('')
  const [validation, setValidation] = useState<ValidationResult | null>(null)
  const [validating, setValidating] = useState(false)
  const [error, setError] = useState<ErrorView | null>(null)
  const validationRequest = useRef(0)
  const runner = useRunPipeline()
  const { clearError } = runner

  useEffect(() => {
    let cancelled = false
    getFields().then((result) => {
      const valid = Array.isArray(result.fields) ? result.fields.filter((field) => typeof field?.name === 'string' && (field.type === 'number' || field.type === 'string')) : []
      if (!cancelled && valid.length) setFields(valid)
    }).catch((caught: unknown) => {
      if (!cancelled) setFieldsNote(`${describeError(caught).message}: field list comes from the shared contract until it is back.`)
    })
    return () => { cancelled = true }
  }, [])

  const pipeline = useMemo(() => flowToPipeline(name, nodes, edges), [name, nodes, edges])
  useEffect(() => { storeEditorDraft(pipeline) }, [pipeline])
  useEffect(() => () => { validationRequest.current++ }, [])
  const selected = nodes.find((node) => node.id === selectedId)

  const invalidate = useCallback(() => {
    validationRequest.current++
    setValidating(false)
    setValidation(null)
    setError(null)
    clearError()
  }, [clearError])

  // Errors to show on nodes: from the last Validate, or from a 400 on Run.
  const shownErrors = validation && !validation.valid ? validation.errors : runner.error?.errors ?? []
  const errorsByNode = new Map<string, string[]>()
  for (const item of shownErrors) if (item.nodeId) errorsByNode.set(item.nodeId, [...(errorsByNode.get(item.nodeId) ?? []), item.message])
  const displayNodes = nodes.map((node) => {
    const errors = errorsByNode.get(node.id)
    return errors || node.data.errors ? { ...node, data: { ...node.data, errors } } : node
  })

  const load = (next: Pipeline, message: string) => {
    const graph = pipelineToFlow(next)
    setName(next.name)
    setNodes(graph.nodes)
    setEdges(graph.edges)
    setSelectedId(undefined)
    setNote(message)
    invalidate()
    setCanvasKey((key) => key + 1)
  }

  const addStep = (kind: NodeKind, position?: { x: number; y: number }) => {
    if (nodes.length >= MAX_NODES) { setNote(`A pipeline can have at most ${MAX_NODES} steps.`); return }
    const id = nextNodeId(nodes)
    const lowest = nodes.reduce((max, node) => Math.max(max, node.position.y), -120)
    const at = position ?? { x: selected ? selected.position.x : 40, y: lowest + 150 }
    setNodes((current) => [...current.map((node) => ({ ...node, selected: false })), { id, type: 'step', position: at, selected: true, data: { kind, params: defaultParams(kind) } }])
    // Click-to-add after a selected non-output step connects it, so a keyboard user can build a chain.
    if (!position && selected && selected.data.kind !== 'output') setEdges((current) => [...current, { id: `${selected.id}->${id}`, source: selected.id, target: id }])
    setSelectedId(id)
    invalidate()
  }

  const isValidConnection = (connection: Connection | Edge) => connection.source !== connection.target
    && !edges.some((edge) => edge.target === connection.target)
  const connect = (connection: Connection) => {
    if (!isValidConnection(connection)) return
    setEdges((current) => addEdge({ ...connection, id: `${connection.source}->${connection.target}` }, current))
    invalidate()
  }

  const setInput = (target: string, from: string) => {
    setEdges((current) => [...current.filter((edge) => edge.target !== target), ...(from ? [{ id: `${from}->${target}`, source: from, target }] : [])])
    invalidate()
  }
  const deleteSelected = () => {
    if (!selectedId) return
    setNodes((current) => current.filter((node) => node.id !== selectedId))
    setEdges((current) => current.filter((edge) => edge.source !== selectedId && edge.target !== selectedId))
    setSelectedId(undefined)
    invalidate()
  }
  const setParams = (params: PipelineNode['params']) => {
    setNodes((current) => current.map((node) => node.id === selectedId ? { ...node, data: { ...node.data, params } } : node))
    invalidate()
  }

  const drop = (event: DragEvent) => {
    event.preventDefault()
    const kind = event.dataTransfer.getData(DRAG_TYPE)
    if (!isNodeKind(kind) || !flow) return
    addStep(kind, flow.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
  }

  const validate = async () => {
    const request = ++validationRequest.current
    setValidating(true)
    setValidation(null)
    setError(null)
    clearError()
    try {
      const result = await validatePipeline(pipeline)
      if (request === validationRequest.current) setValidation(result)
    } catch (caught) {
      if (request === validationRequest.current) { setValidation(null); setError(describeError(caught)) }
    } finally {
      if (request === validationRequest.current) setValidating(false)
    }
  }
  const run = async () => { setValidation(null); setError(null); await runner.run(pipeline) }

  const focusNode = (id: string) => {
    setSelectedId(id)
    setNodes((current) => current.map((node) => ({ ...node, selected: node.id === id })))
    const node = nodes.find((item) => item.id === id)
    if (node && flow) void flow.setCenter(node.position.x + 110, node.position.y + 50, { duration: 0 })
  }

  const candidates = selected ? nodes.filter((node) => node.id !== selected.id && node.data.kind !== 'output').map((node) => node.id) : []
  const selectedInput = selected ? edges.find((edge) => edge.target === selected.id)?.source ?? '' : ''

  return <main className="gl-pe">
    <GlassSurface strong className="gl-pe__header">
      <div className="gl-pe__name">
        <label htmlFor="gl-pe-name">Pipeline name</label>
        <input id="gl-pe-name" type="text" value={name} onChange={(event) => { setName(event.target.value); invalidate() }} />
      </div>
      <div className="gl-pe__name">
        <label htmlFor="gl-pe-example">Load example</label>
        <select id="gl-pe-example" value="" onChange={(event) => {
          const example = EXAMPLES.find((item) => item.id === event.target.value)
          if (example) load(example.pipeline, `Loaded example: "${example.prompt}".`)
        }}>
          <option value="">Choose...</option>
          {EXAMPLES.map((example) => <option key={example.id} value={example.id}>{example.prompt}</option>)}
        </select>
      </div>
      <div className="gl-actions">
        <ClayButton onClick={() => void validate()} disabled={validating || nodes.length === 0}>{validating ? 'Validating...' : 'Validate'}</ClayButton>
        <ClayButton data-testid="pipeline-run" onClick={() => void run()} disabled={runner.running || nodes.length === 0}>{runner.running ? 'Starting...' : 'Run'}</ClayButton>
        <a className="gl-pe__back" href="/pipelines">Back to Pipelines</a>
      </div>
    </GlassSurface>

    <div className="gl-pe__body">
      <GlassSurface strong className="gl-pe__palette">
        <h2 id="gl-pe-palette">Steps</h2>
        <p className="gl-muted gl-small">Click to add (connects after the selected step) or drag onto the canvas.</p>
        <div role="group" aria-labelledby="gl-pe-palette" className="gl-pe__palette-list">
          {NODE_KINDS.map((kind) => <button key={kind} type="button" draggable className={`gl-palette-item gl-step--${kind}`}
            onClick={() => addStep(kind)} onDragStart={(event) => { event.dataTransfer.setData(DRAG_TYPE, kind); event.dataTransfer.effectAllowed = 'move' }}>
            Add {DISPLAY_NAMES[kind]}
          </button>)}
        </div>
      </GlassSurface>

      <div className="gl-pe__canvas" onDrop={drop} onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = 'move' }}>
        {nodes.length === 0 && <p className="gl-pe__empty">Empty pipeline. Add a step from the palette or load an example.</p>}
        <ReactFlow<StepNode, Edge> key={canvasKey} nodes={displayNodes} edges={edges} nodeTypes={nodeTypes} onInit={setFlow}
          onNodesChange={(changes) => {
            onNodesChange(changes)
            if (changes.some((change) => change.type === 'remove')) { setSelectedId(undefined); invalidate() }
          }}
          onEdgesChange={(changes) => { onEdgesChange(changes); if (changes.some((change) => change.type === 'remove')) invalidate() }}
          onConnect={connect} isValidConnection={isValidConnection}
          onNodeClick={(_, node) => setSelectedId(node.id)} onPaneClick={() => setSelectedId(undefined)}
          deleteKeyCode={['Backspace', 'Delete']} fitView fitViewOptions={{ maxZoom: 1.1 }} proOptions={{ hideAttribution: true }}
          aria-label="Pipeline canvas">
          <Background gap={22} color="rgba(25, 27, 23, 0.18)" />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>

      <GlassSurface strong className="gl-pe__inspector">
        {selected
          ? <StepInspector key={selected.id} node={selected} fields={fields} candidates={candidates} input={selectedInput}
            onParams={setParams} onInput={(from) => setInput(selected.id, from)} onDelete={deleteSelected} />
          : <div><h2>Step settings</h2><p className="gl-muted">Select a step on the canvas to edit it. Delete or Backspace removes the selected step or connection.</p></div>}
        {fieldsNote && <p className="gl-alert gl-alert--warn">{fieldsNote}</p>}
      </GlassSurface>
    </div>

    <div className="gl-pe__feedback" aria-live="polite">
      {note && <p className="gl-muted">{note}</p>}
      {validation?.valid && <p className="gl-alert gl-alert--ok" role="status"><strong>Valid.</strong> This pipeline can run.</p>}
      {validation && !validation.valid && <ErrorList error={{ message: 'The pipeline is not valid', errors: validation.errors }} onSelectNode={focusNode} />}
      {error && <ErrorList error={error} onSelectNode={focusNode} />}
      {runner.error && <ErrorList error={runner.error} onSelectNode={focusNode} />}
    </div>
  </main>
}
