import type { CheckpointInfo, ExecutionEvent } from '../../api/types'

export interface TimedExecutionEvent extends ExecutionEvent {
  ts: number
}

/** Formats a duration the API returned; "-" when it returned none. Never derived from client clocks. */
/** Human label for a raw execution state enum (RUNNING -> Running). */
export function humanState(state: string | null | undefined): string {
  if (!state) return ''
  return state.charAt(0) + state.slice(1).toLowerCase().replace(/_/g, ' ')
}

export function formatApiDuration(durationMs: number | null | undefined, unit: 'ms' | 's' = 's'): string {
  if (typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs < 0) return '-'
  if (unit === 'ms') return `${Math.round(durationMs)} ms`
  return durationMs < 100 ? '< 0.1 s' : `${(durationMs / 1000).toFixed(1)} s`
}

function apiDuration(event: TimedExecutionEvent): number | null {
  const value = event.durationMs
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
}

export interface CheckpointTick {
  checkpoint: CheckpointInfo
  timestamp: number
}

export interface RecoveryBand {
  start: number
  end: number
  checkpointId: number
  generation: number
  /** false while the recovery is still in progress. */
  closed: boolean
  /** API-reported recovery duration, or null when the API gave none. */
  durationMs: number | null
}

export interface WorkerDeathMarker {
  timestamp: number
  workerId: string
}

export interface RecoveredMarker {
  timestamp: number
  /** API-reported recovery duration, or null when the API gave none. */
  durationMs: number | null
}

export interface TimelineModel {
  checkpoints: CheckpointTick[]
  recoveries: RecoveryBand[]
  workerDeaths: WorkerDeathMarker[]
  recovered: RecoveredMarker[]
}

export function buildTimelineModel(
  events: TimedExecutionEvent[],
  checkpoints: CheckpointInfo[],
  now: number,
  windowMs = 120_000,
): TimelineModel {
  const cutoff = now - windowMs
  const ordered = [...events].sort((a, b) => a.ts - b.ts)
  const completed = checkpoints
    .filter((checkpoint) => checkpoint.state === 'COMPLETED' && checkpoint.completedAt !== null)
    .map((checkpoint) => ({ checkpoint, timestamp: Date.parse(checkpoint.completedAt!) }))
    .filter(({ timestamp }) => Number.isFinite(timestamp) && timestamp >= cutoff && timestamp <= now)
    .sort((a, b) => a.timestamp - b.timestamp)

  const recoveries: RecoveryBand[] = []
  const workerDeaths: WorkerDeathMarker[] = []
  const recovered: RecoveredMarker[] = []
  let recoveryStart: TimedExecutionEvent | null = null

  for (const event of ordered) {
    if (event.ts < cutoff || event.ts > now) continue
    if (event.event === 'WORKER_DEAD') {
      workerDeaths.push({ timestamp: event.ts, workerId: event.workerId ?? 'worker' })
    } else if (event.event === 'RECOVERING') {
      // A recovery already open (e.g. first seen from the execution state, then labelled by the server) stays one band.
      if (recoveryStart) continue
      recoveryStart = event
      recoveries.push({
        start: event.ts,
        end: now,
        checkpointId: event.checkpointId ?? event.restoredFromCheckpoint ?? 0,
        generation: event.generation,
        closed: false,
        durationMs: null,
      })
    } else if (event.event === 'RECOVERED') {
      const durationMs = apiDuration(event)
      const band = recoveries.at(-1)
      const previous = recovered.at(-1)
      if (!recoveryStart && previous && event.ts - previous.timestamp < 10_000) {
        // The same recovery reported twice (state transition, then the server's RECOVERED): keep one marker.
        if (durationMs !== null && previous.durationMs === null) {
          previous.durationMs = durationMs
          if (band?.closed && band.end === previous.timestamp) band.durationMs = durationMs
        }
        continue
      }
      if (band && band.start === recoveryStart?.ts) {
        band.end = event.ts
        band.closed = true
        band.durationMs = durationMs
      }
      recovered.push({ timestamp: event.ts, durationMs })
      recoveryStart = null
    }
  }

  return {
    checkpoints: completed,
    recoveries: recoveries.filter((band) => band.end >= cutoff && band.start <= now),
    workerDeaths,
    recovered,
  }
}

/**
 * Keeps the newest checkpoint label and drops any older one closer than `gapPx` (at the track's minimum width) to the
 * label kept after it, so neighbours never merge into one unreadable label ("#17" + "#18" -> "#1718", rehearsal-1 #11).
 */
export function spacedLabels<T extends { timestamp: number }>(ticks: T[], now: number, windowMs = 120_000, trackWidth = 720, gapPx = 30): T[] {
  const kept: T[] = []
  let lastX = Number.POSITIVE_INFINITY
  for (const tick of [...ticks].sort((a, b) => b.timestamp - a.timestamp)) {
    const x = Math.max(0, Math.min(1, (tick.timestamp - (now - windowMs)) / windowMs)) * trackWidth
    if (lastX - x < gapPx) continue
    kept.push(tick)
    lastX = x
  }
  return kept.reverse()
}
