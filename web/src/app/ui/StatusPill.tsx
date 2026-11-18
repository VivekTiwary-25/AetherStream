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

export function StatusPill({ state }: { state: StatusState }) {
  return (
    <span className={`status-pill status-pill--${state}`}>
      <span className="status-pill__dot" aria-hidden="true" />
      {labels[state]}
    </span>
  )
}
