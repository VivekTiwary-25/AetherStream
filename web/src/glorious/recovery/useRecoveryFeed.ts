import { useEffect, useRef, useState } from 'react'
import { getCheckpoints, getCurrentExecution } from '../../api/client'
import type { CheckpointInfo, ExecutionInfo } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
import { createBackoff } from '../data/backoff'
import { mergeHistory, type RecoveryEntry } from './model'

async function recoveryRequest(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message || `Request failed (HTTP ${response.status})`)
  }
  return response
}

export async function simulateBreakdown(truckId: string): Promise<void> {
  await recoveryRequest('/api/simulator/scenario', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'breakdown', truckId }),
  })
}

export function useRecoveryFeed() {
  const [history, setHistory] = useState<RecoveryEntry[]>([])
  const [historyLoaded, setHistoryLoaded] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [execution, setExecution] = useState<ExecutionInfo | null>(null)
  const [executionError, setExecutionError] = useState<string | null>(null)
  const [checkpointData, setCheckpointData] = useState<{ executionId: string; rows: CheckpointInfo[] } | null>(null)
  const [checkpointError, setCheckpointError] = useState<string | null>(null)
  const [checkpointsLoaded, setCheckpointsLoaded] = useState(false)
  const executionRevision = useRef(0)
  const historyRevision = useRef(0)
  const checkpointRequest = useRef<Promise<CheckpointInfo[]> | null>(null)
  const refreshCheckpoints = useRef<(() => void) | null>(null)
  const status = useAetherMessages((message) => {
    // The shared classic client union predates the additive server `recovery` frame.
    const type: string = message.type
    if (type === 'recovery') {
      const entry = message.data as RecoveryEntry
      if (!entry || typeof entry.id !== 'number') return
      historyRevision.current++
      setHistory((previous) => mergeHistory(previous, [{ ...entry, observedAt: Date.now() }]))
    }
    if (type === 'snapshot') {
      const snapshot = message.data as { recoveryHistory?: RecoveryEntry[]; execution?: ExecutionInfo | null } | null
      if (!snapshot) return
      if (Array.isArray(snapshot.recoveryHistory)) {
        historyRevision.current++
        setHistory((previous) => mergeHistory(previous, snapshot.recoveryHistory!))
        setHistoryLoaded(true)
        setHistoryError(null)
      }
      if ('execution' in snapshot) {
        executionRevision.current++
        setExecution(snapshot.execution ?? null)
        setExecutionError(null)
      }
    }
    if (type === 'execution' && message.data && typeof message.data === 'object') {
      executionRevision.current++
      setExecution(message.data as ExecutionInfo)
      setExecutionError(null)
    }
  })

  useEffect(() => {
    let active = true
    let timer: number | undefined
    const controller = new AbortController()
    const backoff = createBackoff('Recovery history')
    const refresh = async () => {
      const historyVersion = historyRevision.current
      let delay = 5_000
      try {
        const response = await recoveryRequest('/api/recovery/history', { signal: controller.signal })
        const entries = await response.json() as RecoveryEntry[]
        if (!active) return
        backoff.success()
        // History is additive: merge REST facts even if live entries arrived in flight.
        setHistory((previous) => mergeHistory(previous, entries))
        setHistoryLoaded(true)
        setHistoryError(null)
      } catch (error) {
        if (!active) return
        delay = backoff.failure(error)
        if (historyRevision.current === historyVersion) setHistoryError((error as Error).message)
      } finally {
        if (active) timer = window.setTimeout(() => { void refresh() }, delay)
      }
    }
    void refresh()
    return () => {
      active = false
      controller.abort()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [])

  useEffect(() => {
    let active = true
    let timer: number | undefined
    const backoff = createBackoff('Recovery execution')
    const refresh = async () => {
      const executionVersion = executionRevision.current
      let delay = 5_000
      try {
        const current = await getCurrentExecution()
        if (!active) return
        backoff.success()
        if (executionRevision.current === executionVersion) {
          setExecution(current)
          setExecutionError(null)
        }
      } catch (error) {
        if (!active) return
        delay = backoff.failure(error)
        if (executionRevision.current === executionVersion) setExecutionError((error as Error).message)
      } finally {
        if (active) timer = window.setTimeout(() => { void refresh() }, delay)
      }
    }
    void refresh()
    return () => { active = false; if (timer !== undefined) window.clearTimeout(timer) }
  }, [])

  const executionId = execution?.id
  const latestCheckpoint = history.find((entry) => entry.executionId === executionId && entry.event === 'CHECKPOINT_COMPLETED')?.id
  useEffect(() => {
    if (!executionId) return
    let active = true
    let pending = false
    let unavailable = false
    let timer: number | undefined
    const backoff = createBackoff('Recovery checkpoints')
    const refresh = async () => {
      if (pending || !active) return
      pending = true
      if (timer !== undefined) window.clearTimeout(timer)
      let delay = 5_000
      let request: Promise<CheckpointInfo[]> | undefined
      try {
        // The client cannot abort checkpoint requests. Wait for an old execution's
        // request to settle before starting one for the new execution.
        if (checkpointRequest.current) await checkpointRequest.current.catch(() => {})
        if (!active) return
        request = getCheckpoints(executionId)
        checkpointRequest.current = request
        const rows = await request
        if (!active) return
        unavailable = false
        backoff.success()
        setCheckpointData({ executionId, rows: rows.filter((row) => row.state === 'COMPLETED') })
        setCheckpointError(null)
        setCheckpointsLoaded(true)
      } catch (error) {
        if (!active) return
        unavailable = true
        delay = backoff.failure(error)
        setCheckpointError(error instanceof Error ? error.message : 'Checkpoints unavailable')
      } finally {
        if (checkpointRequest.current === request) checkpointRequest.current = null
        pending = false
        if (active) timer = window.setTimeout(() => { void refresh() }, delay)
      }
    }
    refreshCheckpoints.current = () => { if (!unavailable) void refresh() }
    setCheckpointsLoaded(false)
    setCheckpointError(null)
    void refresh()
    return () => {
      active = false
      refreshCheckpoints.current = null
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [executionId])

  useEffect(() => {
    if (latestCheckpoint !== undefined) refreshCheckpoints.current?.()
  }, [latestCheckpoint])

  return {
    history, historyLoaded, historyError, execution, executionError, status,
    checkpoints: checkpointData && checkpointData.executionId === executionId ? checkpointData.rows : [],
    checkpointsLoaded, checkpointError,
  }
}
