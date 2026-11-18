import { useEffect, useMemo, useRef, useState } from 'react'
import { createPipeline, getDemoPipeline, startExecution } from '../../api/client'
import type { Alert, DeadLetter, WindowResult, WorkerInfo } from '../../api/types'
import { useDashboard } from '../state/store'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { StatusPill } from '../ui/StatusPill'

const number = new Intl.NumberFormat('en-US')

function PanelState({ loading, disconnected, empty }: { loading: boolean; disconnected: boolean; empty: string }) {
  if (loading) return <div className="panel-skeleton" role="status" aria-label="Loading panel data"><span /><span /><span /></div>
  if (disconnected) return <p className="panel-empty" role="status">Live connection lost — reconnecting…</p>
  return <p className="panel-empty">{empty}</p>
}

function relativeAlertTime(eventTime: number, simTime: number | null) {
  if (simTime === null) return simClock(eventTime)
  const seconds = Math.max(0, Math.floor((simTime - eventTime) / 1000))
  return `${number.format(seconds)} s ago`
}

function simClock(timestamp: number) {
  return new Date(timestamp).toISOString().slice(11, 16)
}

function Sparkline({ values, bars = false }: { values: number[]; bars?: boolean }) {
  if (!values.length) return <span aria-label="No history">—</span>
  const width = 120
  const height = 28
  const max = Math.max(...values, 1)
  if (bars) return <svg aria-label="Recent deliveries per minute" role="img" viewBox={`0 0 ${width} ${height}`} className="panel-sparkline">{values.map((value, index) => {
    const barHeight = (value / max) * height
    return <rect key={index} x={index * (width / values.length) + 1} y={height - barHeight} width={Math.max(2, width / values.length - 2)} height={barHeight} rx="1" fill="currentColor" />
  })}</svg>
  const points = values.map((value, index) => `${values.length === 1 ? width / 2 : (index / (values.length - 1)) * width},${height - (value / max) * (height - 3)}`).join(' ')
  return <svg aria-label="Last 10 speed windows" role="img" viewBox={`0 0 ${width} ${height}`} className="panel-sparkline"><polyline points={points} fill="none" stroke="currentColor" strokeWidth="2" /></svg>
}

export function SpeedWindowsPanel() {
  const { windows, socketStatus } = useDashboard()
  const trucks = useMemo(() => {
    const byTruck = new Map<string, WindowResult[]>()
    windows.filter((window) => window.sinkId === 'speedSink').forEach((window) => {
      const list = byTruck.get(window.key) ?? []
      list.push(window)
      byTruck.set(window.key, list)
    })
    return [...byTruck.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [windows])
  return <GlassPanel title="Speed windows"><div className="panel-list">{trucks.length ? trucks.map(([truck, rows]) => {
    const ordered = [...rows].sort((a, b) => a.windowEnd - b.windowEnd)
    const latest = ordered.at(-1)!
    const average = latest.values.avgSpeedKmh
    return <div className="panel-row" key={truck}><strong>{truck}</strong><span>{average === undefined ? '—' : `${average.toFixed(1)} km/h`}</span><Sparkline values={ordered.slice(-10).map((row) => row.values.avgSpeedKmh ?? 0)} /><time>{simClock(latest.windowEnd)}</time></div>
  }) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty="No speed windows yet — start the demo pipeline." />}</div></GlassPanel>
}

export function DeliveriesPanel() {
  const { windows, summary, socketStatus } = useDashboard()
  const rows = windows.filter((window) => window.sinkId === 'delSink').sort((a, b) => a.windowEnd - b.windowEnd)
  const minuteTotals = new Map<number, number>()
  rows.forEach((window) => minuteTotals.set(window.windowEnd, (minuteTotals.get(window.windowEnd) ?? 0) + (window.values.deliveries ?? 0)))
  const values = [...minuteTotals.values()].slice(-15)
  const latest = values.at(-1) ?? summary?.deliveriesLastMinute
  return <GlassPanel title="Deliveries / min">{rows.length ? <><div className="panel-metric"><strong>{number.format(latest ?? 0)}</strong><Sparkline values={values} bars /></div><p className="panel-caption">Latest completed simulation minute</p></> : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty="No deliveries yet — start the demo pipeline." />}</GlassPanel>
}

function AlertRow({ alert, simTime }: { alert: Alert; simTime: number | null }) {
  const row = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const target = row.current
    if (!target || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const animation = target.animate([{ opacity: 0, transform: 'translateY(-5px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 180, easing: 'ease-out' })
    return () => animation.cancel()
  }, [])
  return <div ref={row} className="panel-row panel-row--alert"><span>{alert.message}</span><time>{relativeAlertTime(alert.eventTime, simTime)}</time></div>
}

export function AlertsPanel() {
  const { alerts, simTime, socketStatus } = useDashboard()
  return <GlassPanel title="Alerts" status={<span>{number.format(alerts.length)}</span>}><div className="panel-list" aria-live="polite">{alerts.length ? [...alerts].sort((a, b) => b.eventTime - a.eventTime).slice(0, 8).map((alert) => <AlertRow key={alert.alertId} alert={alert} simTime={simTime} />) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty="No alerts yet — start the demo pipeline." />}</div></GlassPanel>
}

export function DeadLettersPanel() {
  const { deadLetters, socketStatus } = useDashboard()
  const [kind, setKind] = useState<DeadLetter['kind']>('REJECTED')
  const shown = deadLetters.filter((item) => item.kind === kind).slice(0, 5)
  return <GlassPanel title="Dead letters" status={<span>Rejected {number.format(deadLetters.filter((item) => item.kind === 'REJECTED').length)} · Late {number.format(deadLetters.filter((item) => item.kind === 'LATE').length)}</span>}><div className="panel-tabs" role="tablist" aria-label="Dead letter kind">{(['REJECTED', 'LATE'] as const).map((item) => <button key={item} type="button" role="tab" aria-selected={kind === item} aria-pressed={kind === item} onClick={() => setKind(item)}>{item}</button>)}</div><div className="panel-list">{shown.length ? shown.map((item) => <div className="panel-row" key={`${item.eventId}-${item.kind}`}><span>{item.reason}</span><time>{simClock(item.eventTime)}</time></div>) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty={`No ${kind.toLowerCase()} records yet.`} />}</div></GlassPanel>
}


function queuePercent(size: number, capacity: number) {
  return capacity > 0 ? Math.min(100, Math.max(0, (size / capacity) * 100)) : 0
}

export function WorkersPanel() {
  const { workers, socketStatus } = useDashboard()
  const alive = workers.filter((worker) => worker.alive).length
  return <GlassPanel title="Workers" status={<span>{number.format(alive)} alive</span>}><div className="panel-list">{workers.length ? workers.map((worker: WorkerInfo) => {
    const state = !worker.alive ? 'failed' : worker.backpressured ? 'backpressured' : 'healthy'
    return <div className="worker-row" key={worker.workerId}><div className="worker-row__heading"><strong>{worker.workerId}</strong>{!worker.alive ? <span className="status-pill status-pill--failed">! Dead</span> : <StatusPill state={state} />}</div><span>Partitions {worker.partitions.map((partition) => number.format(partition)).join(', ') || '—'} · {worker.recordsPerSec.toFixed(1)} records/s</span><QueueBar label="Source queue" size={worker.sourceQueue.size} capacity={worker.sourceQueue.capacity} /><QueueBar label="Sink queue" size={worker.sinkQueue.size} capacity={worker.sinkQueue.capacity} /></div>
  }) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty="No workers connected yet." />}</div></GlassPanel>
}

function QueueBar({ label, size, capacity }: { label: string; size: number; capacity: number }) {
  return <div className="queue-bar"><span>{label}</span><progress max={100} value={queuePercent(size, capacity)} aria-label={`${label} ${size} of ${capacity}`} /><span>{size}/{capacity}</span></div>
}

export function ExecutionBanner() {
  const { execution, lastExecutionEvent, socketStatus } = useDashboard()
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recoveredIn, setRecoveredIn] = useState<number | null>(null)
  const recoveryStarted = useRef<number | null>(null)
  const checkpointRef = useRef<HTMLElement>(null)
  const checkpoint = lastExecutionEvent?.checkpointId ?? execution?.lastCompletedCheckpoint ?? 0
  const isRecovering = execution?.state === 'RECOVERING' || lastExecutionEvent?.event === 'RECOVERING'
  const status = !execution ? 'paused' : execution.state === 'FAILED' ? 'failed' : isRecovering ? 'recovering' : lastExecutionEvent?.event === 'CHECKPOINT_COMPLETED' ? 'checkpointing' : execution.state === 'RUNNING' ? 'running' : 'paused'
  let label = !execution ? 'No execution' : execution.state
  if (lastExecutionEvent?.event === 'WORKER_DEAD') label = `Worker ${lastExecutionEvent.workerId ?? 'unknown'} lost`
  else if (isRecovering) label = `Recovering from checkpoint #${number.format(checkpoint)}`
  else if (lastExecutionEvent?.event === 'RECOVERED') label = `Recovered in ${((recoveredIn ?? 0) / 1000).toFixed(1)} s`
  else if (lastExecutionEvent?.event === 'CHECKPOINT_COMPLETED') label = `Checkpoint #${number.format(checkpoint)}`
  const startDemo = async () => {
    setStarting(true)
    setError(null)
    try {
      const demo = await getDemoPipeline()
      const saved = await createPipeline(demo)
      await startExecution(saved.id, { idempotencyKey: crypto.randomUUID() })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to start demo pipeline')
    } finally {
      setStarting(false)
    }
  }
  const eventName = lastExecutionEvent?.event
  useEffect(() => {
    if (eventName === 'RECOVERING') {
      recoveryStarted.current = Date.now()
      setRecoveredIn(null)
    } else if (eventName === 'RECOVERED') {
      setRecoveredIn(recoveryStarted.current === null ? 0 : Date.now() - recoveryStarted.current)
    }
    if (eventName === 'CHECKPOINT_COMPLETED' && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      checkpointRef.current?.animate([{ opacity: 0.35 }, { opacity: 1 }], { duration: 220, easing: 'ease-out' })
    }
  }, [eventName])
  return <section className="execution-banner" aria-label="Execution status" aria-live="polite"><StatusPill state={status} /><strong ref={checkpointRef}>{label}</strong><span className="execution-banner__connection">{socketStatus === 'connected' ? 'Live' : 'Live connection lost — reconnecting…'}</span>{!execution && socketStatus !== 'disconnected' && <ClayButton onClick={startDemo} disabled={starting}>{starting ? 'Starting…' : 'Start demo pipeline'}</ClayButton>}{error && <span role="alert">{error}</span>}</section>
}
