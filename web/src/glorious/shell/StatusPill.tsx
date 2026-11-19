import { GlassSurface } from './GlassSurface'
import { useEngineStatus } from './status'

export function StatusPill() {
  const { label, eventsPerSec } = useEngineStatus()
  return <GlassSurface strong className="gl-status">
    <span className={`gl-status__state gl-status__state--${label.toLowerCase()}`} role="status"><span className="gl-status__dot" aria-hidden="true" />{label}</span>
    <span className="gl-status__rate" aria-label={eventsPerSec === null ? 'Event rate unavailable' : `${eventsPerSec.toLocaleString(undefined, { maximumFractionDigits: 1 })} events per second`}>
      {eventsPerSec === null ? '—' : eventsPerSec.toLocaleString(undefined, { maximumFractionDigits: 1 })} <span>ev/s</span>
    </span>
  </GlassSurface>
}
