import type { CheckpointInfo, ExecutionEvent } from '../../api/types'

export interface TimedExecutionEvent extends ExecutionEvent {
  ts: number
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
  durationMs: number | null
}

export interface WorkerDeathMarker {
  timestamp: number
  workerId: string
}

export interface RecoveredMarker {
  timestamp: number
  durationMs: number
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
      recoveryStart = event
      recoveries.push({
        start: event.ts,
        end: now,
        checkpointId: event.checkpointId ?? event.restoredFromCheckpoint ?? 0,
        generation: event.generation,
        durationMs: null,
      })
    } else if (event.event === 'RECOVERED' && recoveryStart) {
      const durationMs = Math.max(0, event.ts - recoveryStart.ts)
      const band = recoveries.at(-1)
      if (band && band.start === recoveryStart.ts) {
        band.end = event.ts
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
