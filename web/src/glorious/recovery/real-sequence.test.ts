import { describe, expect, it } from 'vitest'
import type { WorkerInfo } from '../../api/types'
import { deriveWorkerState, type RecoveryEntry } from './model'
import sequence from './__fixtures__/kill-recovery-sequence.json'


describe('real worker-kill recovery captures', () => {
  it('labels each observed stage from the captured API worker, execution, and audit history', () => {
    const observed = sequence.snapshots.map((snapshot) => {
      const history = [...snapshot.history].sort((a, b) => b.at - a.at) as RecoveryEntry[]
      const execution = {
        id: sequence.executionId,
        state: snapshot.executionState as 'RUNNING' | 'RECOVERING',
        generation: snapshot.generation,
      }
      return {
        name: snapshot.name,
        states: Object.fromEntries(['worker-1', 'worker-2', 'worker-3'].map((workerId) => [workerId,
          deriveWorkerState(snapshot.workers.find((worker) => worker.workerId === workerId) as WorkerInfo,
            workerId, history, execution, snapshot.at)])),
      }
    })

    expect(observed).toEqual([
      { name: '09-after-3s', states: { 'worker-1': 'dead', 'worker-2': 'recovering', 'worker-3': 'recovering' } },
      { name: '09-after-6s', states: { 'worker-1': 'rejoined', 'worker-2': 'running', 'worker-3': 'running' } },
      { name: '09-after-12s', states: { 'worker-1': 'rejoined', 'worker-2': 'running', 'worker-3': 'running' } },
    ])
  })
})
