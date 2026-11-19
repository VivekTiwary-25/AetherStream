export type StatusState =
  | 'running'
  | 'backpressured'
  | 'checkpointing'
  | 'recovering'
  | 'paused'
  | 'failed'
  | 'healthy'

const labels: Record<StatusState, string> = {
  running: 'Running',
  backpressured: 'Backpressured',
  checkpointing: 'Checkpointing',
  recovering: 'Recovering',
  paused: 'Paused',
  failed: 'Failed',
  healthy: 'Healthy',
}

export function StatusPill({ state, label }: { state: StatusState; label?: string }) {
  // One marker only: an icon glyph for states that need more than colour (failed/recovering/paused), otherwise a dot.
  const glyph = state === 'failed' || state === 'recovering' ? '!' : state === 'paused' ? 'Ⅱ' : null
  return (
    <span className={`status-pill status-pill--${state}`}>
      {glyph
        ? <span className="status-pill__glyph" aria-hidden="true">{glyph}</span>
        : <span className="status-pill__dot" aria-hidden="true" />}
      <span>{label ?? labels[state]}</span>
    </span>
  )
}
