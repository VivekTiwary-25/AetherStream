import { describe, expect, it } from 'vitest'
import type { CheckpointInfo, ExecutionEvent } from '../../../api/types'
import { buildTimelineModel, formatApiDuration, type TimedExecutionEvent } from '../timelineModel'

const execution = (event: ExecutionEvent['event'], ts: number, extras: Partial<TimedExecutionEvent> = {}): TimedExecutionEvent => ({
  id: 'exec-1', pipelineId: 'demo', pipelineVersion: 1, state: 'RUNNING', generation: 4,
  restoredFromCheckpoint: 12, lastCompletedCheckpoint: 12, createdAt: new Date(ts).toISOString(),
  event, ts, ...extras,
})

const checkpoint: CheckpointInfo = {
  checkpointId: 12, state: 'COMPLETED', generation: 3, durationMs: 84,
  completedAt: new Date(90_000).toISOString(), sourceOffsets: { '0': 55 },
}

describe('buildTimelineModel', () => {
  it('builds a recovery band without inventing a duration when the API gives none', () => {
    const model = buildTimelineModel([
      execution('WORKER_DEAD', 100_000, { workerId: 'worker-2' }),
      execution('RECOVERING', 101_000, { checkpointId: 12, generation: 4 }),
      execution('RECOVERED', 104_500, { generation: 5 }),
    ], [checkpoint], 110_000)

    expect(model.checkpoints).toEqual([{ checkpoint, timestamp: 90_000 }])
    expect(model.workerDeaths).toEqual([{ timestamp: 100_000, workerId: 'worker-2' }])
    expect(model.recoveries).toEqual([{ start: 101_000, end: 104_500, checkpointId: 12, generation: 4, closed: true, durationMs: null }])
    expect(model.recovered).toEqual([{ timestamp: 104_500, durationMs: null }])
    expect(formatApiDuration(model.recovered[0]!.durationMs)).toBe('-')
  })

  it('uses the recovery duration the API reports', () => {
    const model = buildTimelineModel([
      execution('RECOVERING', 101_000, { checkpointId: 12 }),
      execution('RECOVERED', 104_500, { durationMs: 1_234 }),
    ], [], 110_000)
    expect(model.recovered).toEqual([{ timestamp: 104_500, durationMs: 1_234 }])
    expect(formatApiDuration(1_234)).toBe('1.2 s')
    expect(formatApiDuration(84, 'ms')).toBe('84 ms')
    expect(formatApiDuration(undefined, 'ms')).toBe('-')
  })

  it('keeps an active recovery band open through now', () => {
    const model = buildTimelineModel([execution('RECOVERING', 105_000, { checkpointId: 12 })], [], 110_000)
    expect(model.recoveries[0]).toEqual({ start: 105_000, end: 110_000, checkpointId: 12, generation: 4, closed: false, durationMs: null })
  })
})
