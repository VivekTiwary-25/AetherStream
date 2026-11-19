import { describe, expect, it } from 'vitest'
import type { WorkerInfo } from '../../api/types'
import { DEAD_HOLD_MS, deriveWorkerState, mergeHistory, recentPartitionChange, recoveryDuration, type RecoveryEntry } from './model'

const execution = { id: 'execution-1', state: 'RUNNING' as const }
const worker: WorkerInfo = {
  workerId: 'worker-2', alive: true, state: 'RUNNING', partitions: [1, 4],
  recordsPerSec: 21, sourceQueue: { size: 2, capacity: 64 }, sinkQueue: { size: 0, capacity: 64 },
  backpressured: false, lastHeartbeatMs: 12_000,
}
const entry = (event: string, at: number, extra: Partial<RecoveryEntry> = {}): RecoveryEntry => ({
  id: at, event, at, workerId: 'worker-2', executionId: execution.id,
  generation: 3, restoredFromCheckpoint: 7, ...extra,
})
const state = (history: RecoveryEntry[], now: number, current = worker, phase = execution.state as 'RUNNING' | 'RECOVERING') =>
  deriveWorkerState(current, current.workerId, history, { ...execution, state: phase }, now)

describe('worker lifecycle from sampled workers and recovery audit facts', () => {
  it('does not treat an accepted kill as confirmed death', () => {
    expect(state([entry('KILL_REQUESTED', 10_000)], 11_000)).toBe('running')
    expect(state([], 11_000, { ...worker, alive: false })).toBe('dead')
  })

  it('holds a fast restart visibly dead even when recovery completes between worker samples', () => {
    const history = mergeHistory([], [entry('WORKER_RESTARTED', 10_000, { observedAt: 11_000 }), entry('RECOVERED', 10_100)])
    expect(state(history, 11_000)).toBe('dead')
    expect(state(history, 11_000 + DEAD_HOLD_MS - 1)).toBe('dead')
    expect(state(history, 11_000 + DEAD_HOLD_MS)).toBe('rejoined')
    expect(state(history, 25_000)).toBe('running')
  })

  it('moves from dead to recovering to rejoined without marking survivors dead', () => {
    const history = [entry('WORKER_DEAD', 10_000)]
    expect(state(history, 10_010)).toBe('dead')
    expect(state(history, 14_000, worker, 'RECOVERING')).toBe('recovering')
    expect(state(history, 14_000, { ...worker, workerId: 'worker-1' }, 'RECOVERING')).toBe('recovering')
    expect(state([entry('RECOVERED', 14_001), ...history], 14_002)).toBe('rejoined')
  })

  it('does not let an old RUNNING sample erase a death or let another execution affect this worker', () => {
    expect(state([entry('WORKER_DEAD', 13_000)], 17_000)).toBe('dead')
    expect(state([entry('WORKER_DEAD', 13_000, { executionId: 'another-execution' })], 13_001)).toBe('running')
    expect(state([entry('WORKER_DEAD', 1_000)], 50_000)).toBe('running')
    expect(state([], 15_000, { ...worker, state: 'RESTORING' })).toBe('recovering')
    expect(state([], 15_000, { ...worker, state: 'IDLE' })).toBe('idle')
  })
})

describe('history hydration and recovery timing', () => {
  it('merges an older REST response without losing live entries or replaying the dead hold', () => {
    const live = entry('WORKER_RESTARTED', 10_000, { observedAt: 11_000 })
    const merged = mergeHistory([live, entry('RECOVERED', 10_100)], [entry('WORKER_RESTARTED', 10_000), entry('KILL_REQUESTED', 9_000)])
    expect(merged.map((item) => item.event)).toEqual(['RECOVERED', 'WORKER_RESTARTED', 'KILL_REQUESTED'])
    expect(merged[1].observedAt).toBe(11_000)
    expect(state(merged, 11_000 + DEAD_HOLD_MS)).toBe('rejoined')
  })

  it('uses coordinator duration, otherwise matching recovery boundaries, never kill-to-rejoin', () => {
    const end = entry('RECOVERED', 15_250)
    const history = [end, entry('RECOVERING', 14_000), entry('KILL_REQUESTED', 10_000)]
    expect(recoveryDuration(end, history)).toBe(1_250)
    expect(recoveryDuration({ ...end, durationMs: 900 }, history)).toBe(900)
    expect(recoveryDuration({ ...end, durationMs: 0 }, history)).toBe(0)
    expect(recoveryDuration(end, [entry('RECOVERING', 14_000, { generation: 2 })])).toBeNull()
    expect(recoveryDuration(end, [entry('KILL_REQUESTED', 10_000)])).toBeNull()
  })

  it('retains reassignment arrows briefly, without leaking a previous execution', () => {
    const change = { partition: 1, oldOwner: 'worker-2', newOwner: 'worker-1' }
    const history = [entry('REBALANCED', 10_000, { partitionChanges: [change] })]
    expect(recentPartitionChange(1, history, execution.id, 11_000)).toEqual(change)
    expect(recentPartitionChange(1, history, 'another-execution', 11_000)).toBeUndefined()
    expect(recentPartitionChange(1, history, execution.id, 26_000)).toBeUndefined()
  })
})
