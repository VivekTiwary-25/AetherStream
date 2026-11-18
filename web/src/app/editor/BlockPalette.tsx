import type { DragEvent } from 'react'
import type { NodeType } from './types'

const blockTypes: Array<{ type: NodeType; icon: string; title: string; description: string }> = [
  { type: 'source', icon: '◉', title: 'Source', description: 'Read event stream' },
  { type: 'filter', icon: '⌕', title: 'Filter', description: 'Keep matching events' },
  { type: 'map', icon: '⇄', title: 'Map', description: 'Transform event fields' },
  { type: 'keyBy', icon: '⌘', title: 'Key-by', description: 'Group by event key' },
  { type: 'window', icon: '▤', title: 'Window', description: 'Aggregate over time' },
  { type: 'rule', icon: '◇', title: 'Rule', description: 'Raise an alert' },
  { type: 'sink', icon: '↗', title: 'Sink', description: 'Write results' },
]

export function BlockPalette() {
  const drag = (event: DragEvent<HTMLButtonElement>, type: NodeType) => {
    event.dataTransfer.setData('application/aether-node', type)
    event.dataTransfer.effectAllowed = 'move'
  }
  return <aside className="pipeline-palette" aria-label="Operator palette">
    <h2>Operators</h2>
    {blockTypes.map((block) => <button key={block.type} className="pipeline-palette__item" draggable onDragStart={(event) => drag(event, block.type)} type="button" aria-label={`Add ${block.title}`}>
      <span className={`pipeline-icon pipeline-icon--${block.type}`} aria-hidden="true">{block.icon}</span>
      <span><strong>{block.title}</strong><small>{block.description}</small></span>
    </button>)}
    <p className="pipeline-hint">Drag an operator onto the canvas</p>
  </aside>
}
