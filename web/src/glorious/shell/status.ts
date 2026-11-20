import { useDashboard } from '../../app/state/store'

export type EngineStatusLabel = 'Running' | 'Degraded' | 'Recovering' | 'Stopped' | 'Failed' | 'Disconnected' | 'Unavailable'
export interface EngineStatus { label: EngineStatusLabel; eventsPerSec: number | null; lastReported: boolean }

/** The existing DashboardProvider hydrates REST results and consumes /ws snapshots, execution, workers and flow frames. */
export function useEngineStatus(): EngineStatus {
  const { execution, workers, recovering, socketStatus, flow, summary } = useDashboard()
  const lastReported = socketStatus !== 'connected'
  if (!execution) return { label: lastReported ? 'Disconnected' : 'Unavailable', eventsPerSec: null, lastReported: false }
  // The store has no last-message timestamp. Object identity and a missing rate do not indicate staleness.
  if (execution.state === 'FAILED') return { label: 'Failed', eventsPerSec: null, lastReported }
  if (execution.state === 'CREATED' || execution.state === 'STOPPED') return { label: 'Stopped', eventsPerSec: null, lastReported }
  const rate = flow?.eventsPerSec ?? summary?.eventsPerSec
  const eventsPerSec = !lastReported && typeof rate === 'number' && Number.isFinite(rate) && rate >= 0 ? rate : null
  if (recovering || execution.state === 'RECOVERING') return { label: 'Recovering', eventsPerSec, lastReported }
  if (workers.some((worker) => !worker.alive || worker.backpressured || worker.state === 'RESTORING')) return { label: 'Degraded', eventsPerSec, lastReported }
  return { label: 'Running', eventsPerSec, lastReported }
}
