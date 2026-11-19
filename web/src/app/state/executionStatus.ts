import { useEffect, useRef, useState } from 'react'
import type { StatusState } from '../ui/StatusPill'
import { useDashboard } from './store'

/** Minimum time the UI keeps showing Recovering, so a sub-second recovery is still visible (visual QA X4). */
export const RECOVERY_HOLD_MS = 3000
const CHECKPOINT_FLASH_MS = 1500

export type ExecutionStatus = StatusState | 'stopped'

/**
 * One source of truth for the header badge and the execution banner (visual QA X3): both derive their state from
 * the same execution, last execution event and workers, with the same minimum recovery hold.
 */
export function useExecutionStatus() {
  const { execution, lastExecutionEvent, workers } = useDashboard()
  const liveRecovering = execution?.state === 'RECOVERING' || lastExecutionEvent?.event === 'RECOVERING' || workers.some((worker) => worker?.alive && worker?.state === 'RESTORING')
  const liveCheckpoint = lastExecutionEvent?.event === 'RECOVERING' && typeof lastExecutionEvent.checkpointId === 'number'
    ? lastExecutionEvent.checkpointId
    : execution?.restoredFromCheckpoint ?? execution?.lastCompletedCheckpoint ?? 0
  const startedAt = useRef(0)
  const heldCheckpoint = useRef(0)
  const [held, setHeld] = useState<number | null>(null)
  useEffect(() => {
    if (liveRecovering) {
      heldCheckpoint.current = liveCheckpoint
      if (!startedAt.current) startedAt.current = Date.now()
    }
  }, [liveRecovering, liveCheckpoint])
  useEffect(() => {
    if (liveRecovering || !startedAt.current) return
    const remaining = startedAt.current + RECOVERY_HOLD_MS - Date.now()
    startedAt.current = 0
    if (remaining <= 0) return
    setHeld(heldCheckpoint.current)
    const timer = window.setTimeout(() => setHeld(null), remaining)
    return () => {
      window.clearTimeout(timer)
      setHeld(null)
    }
  }, [liveRecovering])
  const [checkpointFlash, setCheckpointFlash] = useState(false)
  useEffect(() => {
    if (lastExecutionEvent?.event !== 'CHECKPOINT_COMPLETED') return
    setCheckpointFlash(true)
    const timer = window.setTimeout(() => setCheckpointFlash(false), CHECKPOINT_FLASH_MS)
    return () => window.clearTimeout(timer)
  }, [lastExecutionEvent])
  const isRecovering = liveRecovering || held !== null
  const backpressured = workers.some((worker) => worker?.alive && worker?.backpressured)
  const status: ExecutionStatus = execution?.state === 'FAILED'
    ? 'failed'
    : isRecovering
      ? 'recovering'
      : execution?.state === 'RUNNING' && backpressured
        ? 'backpressured'
        : execution?.state === 'RUNNING' && checkpointFlash
          ? 'checkpointing'
          : execution?.state === 'RUNNING'
            ? 'running'
            : 'stopped'
  return { status, isRecovering, recoveringCheckpoint: liveRecovering ? liveCheckpoint : held ?? liveCheckpoint }
}

