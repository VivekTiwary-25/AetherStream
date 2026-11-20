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

  it('holds the confirmed death clock through its paired restart and recovery', () => {
    const history = mergeHistory([], [
      entry('WORKER_DEAD', 10_000),
      entry('WORKER_RESTARTED', 10_008),
      entry('RECOVERED', 11_108),
    ])
    expect(state(history, 12_999)).toBe('dead')
    expect(state(history, 13_000)).toBe('rejoined')
    expect(state(history, 25_000)).toBe('running')
  })

  it.each([false, true])('holds re-registration deaths from event time, paired restart: %s', (restarted) => {
    const history = mergeHistory([], [
      entry('WORKER_DEAD', 10_000, { reason: 're-registered', observedAt: 11_000 }),
      ...(restarted ? [entry('WORKER_RESTARTED', 10_008, { observedAt: 11_008 })] : []),
      ...(restarted ? [entry('RECOVERED', 11_108)] : []),
    ])
    expect(state(history, 12_999)).toBe('dead')
    expect(state(history, 13_000)).toBe(restarted ? 'rejoined' : 'running')
    if (restarted) expect(state(history, 23_000)).toBe('running')
    expect(state(history, 12_000, { ...worker, workerId: 'worker-1' })).toBe('running')
  })

  it('does not show Rejoined for a standalone restart without a death in its cycle', () => {
    const history = mergeHistory([], [
      entry('WORKER_DEAD', 1_000),
      entry('RECOVERED', 2_000),
      entry('WORKER_RESTARTED', 10_000, { observedAt: 11_000 }),
    ])
    expect(state(history, 13_999)).toBe('dead')
    expect(state(history, 14_000)).toBe('running')
  })

  it('keeps survivors out of Rejoined and lets a live recovered worker beat stale heartbeat data', () => {
    const lifecycle = [
      entry('RECOVERED', 11_108, { workerId: 'worker-1' }),
      entry('WORKER_RESTARTED', 10_008, { workerId: 'worker-1' }),
      entry('WORKER_DEAD', 10_000, { workerId: 'worker-1' }),
    ]
    expect(state(lifecycle, 13_000, { ...worker, workerId: 'worker-1', lastHeartbeatMs: 9_000 })).toBe('rejoined')
    expect(state(lifecycle, 13_000, { ...worker, lastHeartbeatMs: 9_000 })).toBe('running')
    expect(state(lifecycle, 14_000, { ...worker, workerId: 'worker-1', lastHeartbeatMs: 9_000 })).toBe('rejoined')
  })

  it('does not let an old RUNNING sample erase a death or let another execution affect this worker', () => {
    expect(state([entry('WORKER_DEAD', 13_000)], 17_000)).toBe('dead')
    expect(state([entry('WORKER_DEAD', 13_000, { executionId: 'another-execution' })], 13_001)).toBe('running')
    expect(state([entry('WORKER_DEAD', 1_000)], 50_000)).toBe('running')
    expect(state([], 15_000, { ...worker, state: 'RESTORING' })).toBe('recovering')
    expect(state([], 15_000, { ...worker, state: 'IDLE' })).toBe('idle')
  })

  it('requires a newer heartbeat before clearing a confirmed death', () => {
    const history = [entry('WORKER_DEAD', 13_000)]
    for (const lastHeartbeatMs of [undefined, null, Number.NaN]) {
      const missingHeartbeat = { ...worker, lastHeartbeatMs } as unknown as WorkerInfo
      expect(state(history, 17_000, missingHeartbeat)).toBe('dead')
    }
    expect(state(history, 17_000, { ...worker, lastHeartbeatMs: 16_000 })).toBe('running')
  })
})

describe('history hydration and recovery timing', () => {
  it('merges an older REST response without losing live entries or replaying the dead hold', () => {
    const live = entry('WORKER_RESTARTED', 10_008, { observedAt: 11_000 })
    const merged = mergeHistory([live, entry('WORKER_DEAD', 10_000), entry('RECOVERED', 10_100)], [
      entry('WORKER_RESTARTED', 10_008), entry('WORKER_DEAD', 10_000), entry('KILL_REQUESTED', 9_000),
    ])
    expect(merged.map((item) => item.event)).toEqual(['RECOVERED', 'WORKER_RESTARTED', 'WORKER_DEAD', 'KILL_REQUESTED'])
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

describe('fake audit: missing workers', () => {
  it('does not keep a restarted worker marked dead forever when it is absent from /api/workers', () => {
    const restarted = [entry('WORKER_RESTARTED', 10_000)]
    const missing = (history: RecoveryEntry[], now: number) => deriveWorkerState(undefined, 'worker-2', history, execution, now)
    expect(missing(restarted, 10_000 + DEAD_HOLD_MS - 1)).toBe('dead')
    expect(missing(restarted, 10_000 + DEAD_HOLD_MS + 1)).toBe('unavailable')
    expect(missing([entry('WORKER_DEAD', 10_000)], 60_000)).toBe('dead')
  })
})
