import type { ErrorView } from './api'

/** Honest error box: the message plus any validation errors (with node ids). */
export function ErrorList({ error, onSelectNode }: { error: ErrorView; onSelectNode?: (nodeId: string) => void }) {
  return <div className="gl-alert gl-alert--error" role="alert">
    <p><strong>{error.message}</strong></p>
    {error.errors.length > 0 && <ul className="gl-error-list">
      {error.errors.map((item, index) => <li key={`${item.nodeId ?? ''}-${index}`}>
        {item.nodeId && (onSelectNode
          ? <button type="button" className="gl-link-button" onClick={() => onSelectNode(item.nodeId!)}>{item.nodeId}</button>
          : <code>{item.nodeId}</code>)}
        {item.nodeId ? ': ' : ''}{item.message}
      </li>)}
    </ul>}
  </div>
}
