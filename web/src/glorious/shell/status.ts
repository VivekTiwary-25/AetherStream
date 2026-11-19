import { useDashboard } from '../../app/state/store'

export type EngineStatusLabel = 'Running' | 'Degraded' | 'Recovering' | 'Stopped' | 'Disconnected'
export interface EngineStatus { label: EngineStatusLabel; eventsPerSec: number | null }

/** The existing DashboardProvider hydrates REST results and consumes /ws snapshots, execution, workers and flow frames. */
export function useEngineStatus(): EngineStatus {
  const { execution, workers, recovering, socketStatus, flow, summary } = useDashboard()
  if (socketStatus !== 'connected') return { label: 'Disconnected', eventsPerSec: null }
  if (!execution || execution.state === 'CREATED' || execution.state === 'STOPPED' || execution.state === 'FAILED') return { label: 'Stopped', eventsPerSec: null }
  const rate = flow?.eventsPerSec ?? summary?.eventsPerSec
  const eventsPerSec = typeof rate === 'number' && Number.isFinite(rate) && rate >= 0 ? rate : null
  if (recovering || execution.state === 'RECOVERING') return { label: 'Recovering', eventsPerSec }
  if (workers.some((worker) => !worker.alive || worker.backpressured || worker.state === 'RESTORING')) return { label: 'Degraded', eventsPerSec }
  return { label: 'Running', eventsPerSec }
}
