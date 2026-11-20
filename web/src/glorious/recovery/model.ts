import type { ExecutionInfo, WorkerInfo } from '../../api/types'

export interface PartitionChange {
  partition: number
  oldOwner: string | null
  newOwner: string | null
}

export interface RecoveryEntry {
  id: number
  event: string
  at: number
  workerId: string | null
  executionId: string | null
  generation: number | null
  restoredFromCheckpoint: number | null
  actor?: string | null
  reason?: string | null
  durationMs?: number | null
  checkpointId?: number | null
  partitionChanges?: PartitionChange[]
  /** Client receipt time only for live failure facts; never used to measure recovery duration. */
  observedAt?: number
}

export const DEAD_HOLD_MS = 3_000
export const REJOINED_HOLD_MS = 10_000
export const REASSIGNMENT_HOLD_MS = 15_000

export function mergeHistory(current: RecoveryEntry[], incoming: RecoveryEntry[]): RecoveryEntry[] {
  const byId = new Map(current.map((entry) => [entry.id, entry]))
  for (const entry of incoming) {
    const previous = byId.get(entry.id)
    byId.set(entry.id, { ...entry, observedAt: previous?.observedAt ?? entry.observedAt })
  }
  return [...byId.values()].sort((a, b) => b.at - a.at || b.id - a.id).slice(0, 200)
}

export type WorkerDisplayState = 'running' | 'dead' | 'recovering' | 'rejoined' | 'idle' | 'unavailable'

export function deriveWorkerState(
  worker: WorkerInfo | undefined,
  workerId: string,
  history: RecoveryEntry[],
  execution: Pick<ExecutionInfo, 'id' | 'state'> | null,
  now: number,
): WorkerDisplayState {
  // History is newest first. Only explicit lifecycle facts identify a failed worker;
  // KILL_REQUESTED is not confirmation that a worker died.
  const entries = history.filter((entry) => entry.executionId === execution?.id)
  const loss = entries.find((entry) => entry.workerId === workerId &&
    (entry.event === 'WORKER_DEAD' || entry.event === 'WORKER_RESTARTED'))
  if (worker && !worker.alive) return 'dead'
  if (loss && now < (loss.observedAt ?? loss.at) + DEAD_HOLD_MS) return 'dead'
  // A restart is not a death: once its hold ends, a worker missing from /api/workers is just unavailable.
  if (!worker) return loss?.event === 'WORKER_DEAD' ? 'dead' : 'unavailable'
  // A pre-death RUNNING sample must not erase an authoritative death event.
  if (loss?.event === 'WORKER_DEAD' && (!Number.isFinite(worker.lastHeartbeatMs) || worker.lastHeartbeatMs <= loss.at)) return 'dead'
  if (worker.state === 'RESTORING' || execution?.state === 'RECOVERING') return 'recovering'
  const recovered = loss && entries.find((entry) => entry.event === 'RECOVERED' && entry.at >= loss.at)
  const joinedAt = recovered?.at ?? (loss?.event === 'WORKER_RESTARTED' ? loss.at : null)
  if (loss && joinedAt !== null && now < Math.max(joinedAt, (loss.observedAt ?? loss.at) + DEAD_HOLD_MS) + REJOINED_HOLD_MS) return 'rejoined'
  return worker.state === 'RUNNING' ? 'running' : 'idle'
}

export function recoveryDuration(entry: RecoveryEntry, history: RecoveryEntry[]): number | null {
  if (entry.event !== 'RECOVERED') return null
  if (entry.durationMs != null && Number.isFinite(entry.durationMs) && entry.durationMs >= 0) return entry.durationMs
  const start = history.find((candidate) => candidate.event === 'RECOVERING' &&
    candidate.executionId === entry.executionId && candidate.generation === entry.generation && candidate.at <= entry.at)
  return start ? entry.at - start.at : null
}

export function recentPartitionChange(partition: number, history: RecoveryEntry[], executionId: string | undefined, now: number) {
  for (const entry of history) {
    if (entry.executionId !== executionId || now - entry.at > REASSIGNMENT_HOLD_MS) continue
    const change = entry.partitionChanges?.find((candidate) => candidate.partition === partition)
    if (change) return change
  }
  return undefined
}

export const eventLabel = (event: string) => ({
  KILL_REQUESTED: 'Kill requested (not yet confirmed)',
  WORKER_DEAD: 'Worker died',
  WORKER_RESTARTED: 'Worker restarted',
  RECOVERING: 'Recovery started',
  RECOVERED: 'Recovery finished',
  REBALANCED: 'Partitions reassigned',
  CHECKPOINT_COMPLETED: 'Checkpoint saved',
}[event] ?? event)

export const seconds = (ms: number) => (Math.max(0, ms) / 1_000).toFixed(1)
