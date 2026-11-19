import { useEffect, useRef, useState } from 'react'
import type { StatusState } from '../ui/StatusPill'
import { isRebalanced, useDashboard } from './store'

/** Minimum time the UI keeps showing Recovering, so a sub-second recovery is still visible (visual QA X4; >= 1.5 s). */
export const RECOVERY_HOLD_MS = 3000
const CHECKPOINT_FLASH_MS = 1500

/** How long the banner keeps "Recovered …" after a recovery, whatever checkpoint events follow (rehearsal-1 #3). */
export const RECOVERED_SHOW_MS = 10_000
/** A worker killed from the UI shows as killed for at least this long, and at most KILL_MARK_MAX_MS (rehearsal-1 #12). */
export const KILL_MARK_MIN_MS = 3_000
export const KILL_MARK_MAX_MS = 20_000

export type ExecutionStatus = StatusState | 'stopped'

/** Re-renders once at `until` (epoch ms) and returns the current time; no timer when `until` is null or past. */
export function useNowUntil(until: number | null) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (until === null) return
    const remaining = until - Date.now()
    if (remaining <= 0) return
    const timer = window.setTimeout(() => setNow(Date.now()), remaining + 20)
    return () => window.clearTimeout(timer)
  }, [until])
  return Math.max(now, Date.now())
}

/** The last finished recovery while it is still recent enough to show, else null. */
export function useRecentRecovery(showMs = RECOVERED_SHOW_MS) {
  const { lastRecovery } = useDashboard()
  const until = lastRecovery ? lastRecovery.at + showMs : null
  const now = useNowUntil(until)
  return lastRecovery && until !== null && now < until ? lastRecovery : null
}

/** Worker ids killed from the UI that should still show as killed. */
export function useKilledWorkers(): Set<string> {
  const { killedWorkers, lastRecovery } = useDashboard()
  const ends = Object.values(killedWorkers).flatMap((at) => [at + KILL_MARK_MIN_MS, at + KILL_MARK_MAX_MS])
  const now = useNowUntil(ends.filter((end) => end > Date.now()).sort((a, b) => a - b)[0] ?? null)
  return new Set(Object.entries(killedWorkers).filter(([, at]) => {
    const age = now - at
    if (age < 0 || age >= KILL_MARK_MAX_MS) return false
    // Once a recovery finished after the kill (and the minimum has passed), the worker is back.
    return !(lastRecovery && lastRecovery.at > at && age >= KILL_MARK_MIN_MS)
  }).map(([workerId]) => workerId))
}

/**
 * One source of truth for the header badge and the execution banner (visual QA X3): both derive their state from
 * the same execution, last execution event and workers, with the same minimum recovery hold.
 *
 * The hold starts when recovery is first seen, either live (execution/event RECOVERING, a worker RESTORING) or via
 * the store's `recoveries` counter (RECOVERING and RECOVERED delivered in one React batch never render as live).
 * It is measured from that first sighting, so repeated signals cannot keep re-arming it, and it is dropped as soon
 * as the execution stops or fails. A REBALANCED event (partitions moved without a WORKER_DEAD) is not a recovery.
 */
export function useExecutionStatus() {
  const { execution, lastExecutionEvent, workers, recoveries, recoveryCheckpoint } = useDashboard()
  const rebalanced = isRebalanced(lastExecutionEvent)
  const liveRecovering = execution?.state === 'RECOVERING'
    || lastExecutionEvent?.event === 'RECOVERING'
    || (!rebalanced && execution?.state !== 'RUNNING' && workers.some((worker) => worker?.alive && worker?.state === 'RESTORING'))
  const liveCheckpoint = lastExecutionEvent?.event === 'RECOVERING' && typeof lastExecutionEvent.checkpointId === 'number'
    ? lastExecutionEvent.checkpointId
    : execution?.restoredFromCheckpoint ?? execution?.lastCompletedCheckpoint ?? 0

  // Latest checkpoint values, read by the hold effect without making it re-run (a re-run would cancel the hold).
  const checkpoints = useRef({ live: liveCheckpoint, counted: recoveryCheckpoint })
  useEffect(() => {
    checkpoints.current = { live: liveCheckpoint, counted: recoveryCheckpoint }
  })

  const startedAt = useRef(0)
  const heldCheckpoint = useRef(0)
  const seenRecoveries = useRef(recoveries)
  const [held, setHeld] = useState<number | null>(null)
  useEffect(() => {
    const counted = recoveries !== seenRecoveries.current
    seenRecoveries.current = recoveries
    if (liveRecovering || counted) {
      if (!startedAt.current) startedAt.current = Date.now()
      heldCheckpoint.current = liveRecovering ? checkpoints.current.live : checkpoints.current.counted ?? checkpoints.current.live
    }
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
  }, [liveRecovering, recoveries])

  const [checkpointFlash, setCheckpointFlash] = useState(false)
  useEffect(() => {
    if (lastExecutionEvent?.event !== 'CHECKPOINT_COMPLETED') return
    setCheckpointFlash(true)
    const timer = window.setTimeout(() => setCheckpointFlash(false), CHECKPOINT_FLASH_MS)
    return () => window.clearTimeout(timer)
  }, [lastExecutionEvent])

  const ended = execution?.state === 'STOPPED' || execution?.state === 'FAILED'
  const isRecovering = liveRecovering || (held !== null && !ended)
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
