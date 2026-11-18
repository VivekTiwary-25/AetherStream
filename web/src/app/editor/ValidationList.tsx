import type { CompilerError } from './types'

export function ValidationList({ errors, onSelect }: { errors: CompilerError[]; onSelect: (nodeId: string) => void }) {
  if (!errors.length) return null
  return <section className="validation-list" aria-label="Validation errors">
    {errors.map((error, index) => <div key={`${error.nodeId}-${error.code}-${index}`} className="validation-list__item">
      <span className="validation-list__code">{error.code}</span><span>{error.message}</span>{error.nodeId && <><small>{error.nodeId}</small><button type="button" className="validation-list__show" onClick={() => onSelect(error.nodeId!)}>Show me</button></>}
    </div>)}
  </section>
}
