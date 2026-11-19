import { useEffect, useMemo, useRef, useState } from 'react'
import { getCheckpoints, getCurrentExecution } from '../../api/client'
import type { CheckpointInfo, ExecutionEvent, ExecutionInfo, WebSocketMessage, WorkerInfo } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
import { GlassPanel } from '../ui/GlassPanel'
import { buildTimelineModel, formatApiDuration, type TimedExecutionEvent } from './timelineModel'

const WINDOW_MS = 120_000
const TRACK_WIDTH = 720

function addEvent(events: TimedExecutionEvent[], event: TimedExecutionEvent) {
  return [...events.filter((item) => !(item.event === event.event && item.ts === event.ts)), event].slice(-100)
}

function timePosition(timestamp: number, now: number) {
  return Math.max(0, Math.min(100, ((timestamp - (now - WINDOW_MS)) / WINDOW_MS) * 100))
}

export function CheckpointTimeline() {
  const [execution, setExecution] = useState<ExecutionInfo | null>(null)
  const [checkpoints, setCheckpoints] = useState<CheckpointInfo[]>([])
  const [events, setEvents] = useState<TimedExecutionEvent[]>([])
  const [now, setNow] = useState(Date.now())
  const [requestError, setRequestError] = useState(false)
  const trackRef = useRef<HTMLDivElement>(null)
  const workersAlive = useRef(new Map<string, boolean>())
  const executionRef = useRef<ExecutionInfo | null>(null)

  useEffect(() => {
    let active = true
    const refresh = async () => {
      if (document.visibilityState !== 'visible') return
      try {
        const current = await getCurrentExecution()
        if (!active) return
        executionRef.current = current
        setExecution(current)
        if (current) setCheckpoints(await getCheckpoints(current.id, 20))
        else setCheckpoints([])
        setRequestError(false)
      } catch {
        if (active) setRequestError(true)
      }
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), 5_000)
    const onVisibility = () => { if (document.visibilityState === 'visible') void refresh() }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      active = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [])

  // Every frame, in order: a WORKER_DEAD -> RECOVERING -> RECOVERED burst must leave all three entries.
  useAetherMessages((lastMessage: WebSocketMessage) => {
    if (lastMessage.type === 'snapshot' && lastMessage.data != null && typeof lastMessage.data === 'object') {
      const snapshot = lastMessage.data as { execution?: ExecutionInfo | null; workers?: WorkerInfo[] }
      if (snapshot.execution !== undefined) {
        executionRef.current = snapshot.execution
        setExecution(snapshot.execution)
      }
      if (Array.isArray(snapshot.workers)) snapshot.workers.forEach((worker) => { if (worker) workersAlive.current.set(worker.workerId, worker.alive) })
    }
    if (lastMessage.type === 'workers' && Array.isArray(lastMessage.data)) {
      const workers = lastMessage.data as WorkerInfo[]
      workers.forEach((worker) => {
        if (!worker) return
        const wasAlive = workersAlive.current.get(worker.workerId)
        const current = executionRef.current
        if (wasAlive && !worker.alive && current) {
          setEvents((events) => addEvent(events, {
            ...current, event: 'WORKER_DEAD', workerId: worker.workerId, ts: lastMessage.ts,
          }))
        }
        workersAlive.current.set(worker.workerId, worker.alive)
      })
    }
    if (lastMessage.type === 'execution' && lastMessage.data != null && typeof lastMessage.data === 'object') {
      const data = lastMessage.data as ExecutionEvent
      executionRef.current = data
      setExecution(data)
      setEvents((current) => addEvent(current, { ...data, ts: lastMessage.ts }))
    }
    // Truck frames (every 250 ms) carry nothing for the timeline; the 1 s clock below keeps it moving.
    if (lastMessage.type !== 'trucks') setNow(lastMessage.ts)
  })

  useEffect(() => {
    if (!execution) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [execution])

  const model = useMemo(() => buildTimelineModel(events, checkpoints, now), [events, checkpoints, now])
  useEffect(() => {
    const track = trackRef.current
    if (track) track.scrollLeft = track.scrollWidth
  }, [model, now])

  const recovering = execution?.state === 'RECOVERING'
  const pendingCheckpoint = checkpoints.some((checkpoint) => checkpoint.state === 'PENDING')
  const status = recovering ? 'Recovering' : pendingCheckpoint ? 'Checkpointing' : execution?.state ?? 'Waiting'
  const statusColor = recovering ? '#f59e0b' : pendingCheckpoint ? '#a78bfa' : execution?.state === 'RUNNING' ? '#4ade80' : execution?.state === 'FAILED' ? '#f87171' : '#94a3b8'
  const restored = execution?.restoredFromCheckpoint ?? model.recoveries.at(-1)?.checkpointId
  const latestCheckpoint = checkpoints.find((checkpoint) => checkpoint.checkpointId === execution?.lastCompletedCheckpoint)
  const recoveredMessage = model.recovered.at(-1)

  return (
    <GlassPanel title="Checkpoint & recovery timeline" status={<span style={{ color: statusColor }}>{status}</span>}>
      <div style={{ color: '#cbd5e1', fontSize: 13 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
          <span>Last 2 minutes · oldest <span aria-hidden="true">←</span> newest</span>
          {requestError && <span role="status" style={{ color: '#fca5a5' }}>Timeline refresh failed</span>}
        </div>
        <div ref={trackRef} style={{ overflowX: 'auto', marginTop: 18, paddingBottom: 8 }}>
          <div aria-label="Checkpoint and recovery events, last two minutes" role="img" style={{ position: 'relative', width: '100%', minWidth: TRACK_WIDTH, height: 94, borderBottom: '1px solid #475569' }}>
            {model.recoveries.map((band, index) => {
              const left = timePosition(band.start, now)
              const right = timePosition(band.end, now)
              return <div key={`recovery-${band.start}-${index}`} title={`Recovering from checkpoint #${band.checkpointId} (generation ${band.generation} → ${band.generation + 1})`} style={{ position: 'absolute', left: `${left}%`, width: `${Math.max(1, right - left)}%`, top: 5, bottom: 0, background: 'rgb(245 158 11 / 18%)', borderLeft: '2px solid #f59e0b', borderRight: band.closed ? '2px solid #f59e0b' : undefined, color: '#fbbf24', fontSize: 11, padding: '3px 5px', overflow: 'hidden', whiteSpace: 'nowrap' }}>Recovering from checkpoint #{band.checkpointId} (generation {band.generation} → {band.generation + 1})</div>
            })}
            {model.checkpoints.map(({ checkpoint, timestamp }) => <span key={`checkpoint-${checkpoint.checkpointId}`} title={`Checkpoint #${checkpoint.checkpointId} · ${formatApiDuration(checkpoint.durationMs, 'ms')} · COMPLETED`} aria-label={`Completed checkpoint #${checkpoint.checkpointId}, duration ${formatApiDuration(checkpoint.durationMs, 'ms')}`} style={{ position: 'absolute', left: `${timePosition(timestamp, now)}%`, top: 47, height: 24, borderLeft: '3px solid #a78bfa', zIndex: 1 }} />)}
            {checkpoints.filter((checkpoint) => checkpoint.state === 'ABORTED' && checkpoint.completedAt).map((checkpoint) => {
              const timestamp = Date.parse(checkpoint.completedAt!)
              if (timestamp < now - WINDOW_MS || timestamp > now) return null
              return <span key={`aborted-${checkpoint.checkpointId}`} title={`Checkpoint #${checkpoint.checkpointId} · ABORTED`} aria-label={`Aborted checkpoint #${checkpoint.checkpointId}`} style={{ position: 'absolute', left: `${timePosition(timestamp, now)}%`, top: 47, height: 24, borderLeft: '3px solid #94a3b8', zIndex: 1 }} />
            })}
            {model.workerDeaths.map((death, index) => <span key={`dead-${death.timestamp}-${index}`} title={`${death.workerId} lost`} style={{ position: 'absolute', left: `${timePosition(death.timestamp, now)}%`, top: 32, zIndex: 2, color: '#f87171', fontSize: 11, whiteSpace: 'nowrap', transform: 'translateX(-50%)' }}>✕ {death.workerId} lost</span>)}
            {model.recovered.map((marker, index) => <span key={`recovered-${marker.timestamp}-${index}`} title={`Recovered in ${formatApiDuration(marker.durationMs)}`} style={{ position: 'absolute', left: `${timePosition(marker.timestamp, now)}%`, top: 73, zIndex: 2, color: '#4ade80', fontSize: 11, whiteSpace: 'nowrap', transform: 'translateX(-50%)' }}>✓ Recovered in {formatApiDuration(marker.durationMs)}</span>)}
            {model.checkpoints.map(({ checkpoint, timestamp }) => <span key={`label-${checkpoint.checkpointId}`} style={{ position: 'absolute', left: `${timePosition(timestamp, now)}%`, top: 72, color: '#c4b5fd', fontSize: 10, whiteSpace: 'nowrap', transform: 'translateX(-50%)' }}>#{checkpoint.checkpointId}</span>)}
          </div>
        </div>
        <div aria-live="polite" aria-atomic="true" style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: '4px 18px' }}>
          <span>Generation {execution?.generation ?? '—'}</span>
          <span>Last checkpoint #{execution?.lastCompletedCheckpoint ?? '—'}{latestCheckpoint ? ` (${formatApiDuration(latestCheckpoint.durationMs, 'ms')})` : ''}</span>
          <span>Restored from {restored == null ? '—' : `#${restored}`}</span>
          {recovering && <span style={{ color: '#fbbf24' }}>Recovering from checkpoint #{execution?.restoredFromCheckpoint ?? model.recoveries.at(-1)?.checkpointId ?? 0}</span>}
          {!recovering && recoveredMessage && <span style={{ color: '#4ade80' }}>Recovered in {formatApiDuration(recoveredMessage.durationMs)}</span>}
        </div>
        <div aria-live="polite" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clipPath: 'inset(50%)' }}>
          {events.length ? `${events.at(-1)?.event?.toLowerCase().replace('_', ' ')} at ${new Date(events.at(-1)!.ts).toLocaleTimeString()}` : ''}
        </div>
      </div>
    </GlassPanel>
  )
}

