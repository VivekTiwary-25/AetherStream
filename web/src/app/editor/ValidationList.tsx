import type { CompilerError } from './types'

export function ValidationList({ errors, onSelect }: { errors: CompilerError[]; onSelect: (nodeId: string) => void }) {
  if (!errors.length) return null
  return <section className="validation-list" aria-label="Validation errors">
    {errors.map((error, index) => <button key={`${error.nodeId}-${error.code}-${index}`} type="button" className="validation-list__item" onClick={() => error.nodeId && onSelect(error.nodeId)} disabled={!error.nodeId}>
      <span className="validation-list__code">{error.code}</span><span>{error.message}</span>{error.nodeId && <small>{error.nodeId}</small>}
    </button>)}
  </section>
}
