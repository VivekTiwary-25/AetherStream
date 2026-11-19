import { useEffect, useRef, useState } from 'react'
import { getCheckpoints, getCurrentExecution } from '../../api/client'
import type { CheckpointInfo, ExecutionInfo } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
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
    let pending = false
    const refresh = async () => {
      if (pending) return
      pending = true
      const historyVersion = historyRevision.current
      const executionVersion = executionRevision.current
      try {
        const response = await recoveryRequest('/api/recovery/history')
        const entries = await response.json() as RecoveryEntry[]
        if (active) {
          setHistory((previous) => mergeHistory(previous, entries))
          setHistoryLoaded(true)
          setHistoryError(null)
        }
      } catch (error) {
        if (active && historyRevision.current === historyVersion) setHistoryError((error as Error).message)
      } finally { pending = false }
      getCurrentExecution().then((current) => {
        if (active && executionRevision.current === executionVersion) {
          setExecution(current)
          setExecutionError(null)
        }
      }).catch((error: Error) => {
        if (active && executionRevision.current === executionVersion) setExecutionError(error.message)
      })
      if (active) timer = window.setTimeout(() => { void refresh() }, 5_000)
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
    const refresh = async () => {
      if (pending) return
      pending = true
      try {
        const rows = await getCheckpoints(executionId)
        if (active) {
          setCheckpointData({ executionId, rows: rows.filter((row) => row.state === 'COMPLETED') })
          setCheckpointError(null)
          setCheckpointsLoaded(true)
        }
      } catch (error) {
        if (active) setCheckpointError(error instanceof Error ? error.message : 'Checkpoints unavailable')
      } finally { pending = false }
    }
    setCheckpointsLoaded(false)
    setCheckpointError(null)
    void refresh()
    const timer = window.setInterval(() => { void refresh() }, 5_000)
    return () => { active = false; window.clearInterval(timer) }
  }, [executionId, latestCheckpoint])

  return {
    history, historyLoaded, historyError, execution, executionError, status,
    checkpoints: checkpointData && checkpointData.executionId === executionId ? checkpointData.rows : [],
    checkpointsLoaded, checkpointError,
  }
}
