import { useEffect, useMemo, useRef, useState } from 'react'
import './panels.css'
import { createPipeline, getDemoPipeline, startExecution } from '../../api/client'
import type { Alert, DeadLetter, WindowResult, WorkerInfo } from '../../api/types'
import { useExecutionStatus, type ExecutionStatus } from '../state/executionStatus'
import { useDashboard } from '../state/store'
import { formatApiDuration, humanState } from '../timeline/timelineModel'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { StatusPill, type StatusState } from '../ui/StatusPill'

const number = new Intl.NumberFormat('en-US')


function PanelState({ loading, disconnected, empty }: { loading: boolean; disconnected: boolean; empty: string }) {
  if (loading) return <div className="panel-skeleton" role="status" aria-label="Loading panel data"><span /><span /><span /></div>
  if (disconnected) return <p className="panel-empty" role="status">Live connection lost — reconnecting…</p>
  return <p className="panel-empty">{empty}</p>
}

function relativeAlertTime(eventTime: number, simTime: number | null) {
  if (!Number.isFinite(eventTime)) return '—'
  if (simTime === null || !Number.isFinite(simTime)) return simClock(eventTime)
  const seconds = Math.max(0, Math.floor((simTime - eventTime) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${number.format(minutes)} min ago`
  const hours = Math.floor(minutes / 60)
  return `${number.format(hours)} h ${minutes % 60} min ago`
}

function simClock(timestamp: number) {
  const date = new Date(timestamp)
  return Number.isFinite(date.getTime()) ? date.toISOString().slice(11, 16) : '—'
}

const pipelineActive = (state: string | undefined) => state === 'RUNNING' || state === 'RECOVERING'

export function ExecutionStatusPill({ status }: { status: ExecutionStatus }) {
  return status === 'stopped'
    ? <span className="status-pill status-pill--stopped"><span className="status-pill__dot" aria-hidden="true" />Stopped</span>
    : <StatusPill state={status} />
}

function Sparkline({ values, bars = false }: { values: number[]; bars?: boolean }) {
  const finiteValues = values.filter(Number.isFinite)
  if (!finiteValues.length) return <span className="panel-sparkline panel-sparkline--empty" aria-label="No history">—</span>
  const width = 120
  const height = 28
  const max = Math.max(...finiteValues, 1)
  if (bars) return <svg aria-label="Recent deliveries per minute" role="img" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="panel-sparkline panel-sparkline--bars">{finiteValues.map((value, index) => {
    const barHeight = (value / max) * height
    return <rect key={index} className={index === finiteValues.length - 1 ? 'panel-bar panel-bar--latest' : 'panel-bar'} x={index * (width / finiteValues.length) + 1} y={height - barHeight} width={Math.max(2, width / finiteValues.length - 2)} height={barHeight} rx="1" fill="currentColor" />
  })}</svg>
  // A single window is drawn as a short flat segment so the row never shows an empty gap.
  const series = finiteValues.length === 1 ? [finiteValues[0]!, finiteValues[0]!] : finiteValues
  const points = series.map((value, index) => `${(index / (series.length - 1)) * width},${finiteValues.length === 1 ? height / 2 : height - 1.5 - (value / max) * (height - 3)}`).join(' ')
  return <svg aria-label="Last 10 speed windows" role="img" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="panel-sparkline"><polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" /></svg>
}

export function SpeedWindowsPanel() {
  const { windows, socketStatus, execution } = useDashboard()
  const trucks = useMemo(() => {
    const byTruck = new Map<string, WindowResult[]>()
    windows.filter((window) => window && window.sinkId === 'speedSink' && window.values != null && Number.isFinite(window.windowEnd)).forEach((window) => {
      const list = byTruck.get(window.key) ?? []
      list.push(window)
      byTruck.set(window.key, list)
    })
    return [...byTruck.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [windows])
  // Row order matters to the UI e2e cross-check: <strong> truck, first <span> speed, <time> window minute.
  return <GlassPanel title="Speed windows">{trucks.length > 0 && <p className="panel-caption panel-caption--top">Avg km/h · last 10 one-minute windows · sim time</p>}<div className="panel-list panel-list--speed">{trucks.length ? trucks.map(([truck, rows]) => {
    const ordered = [...rows].sort((a, b) => a.windowEnd - b.windowEnd)
    const latest = ordered.at(-1)!
    const average = latest.values?.avgSpeedKmh
    return <div className="panel-row panel-row--speed" key={truck}><strong>{truck}</strong><span className="panel-row__value">{typeof average === 'number' && Number.isFinite(average) ? `${average.toFixed(1)} km/h` : '—'}</span><Sparkline values={ordered.slice(-10).map((row) => row.values?.avgSpeedKmh).filter((value): value is number => typeof value === 'number' && Number.isFinite(value))} /><time>{simClock(latest.windowEnd)}</time></div>
  }) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty={pipelineActive(execution?.state) ? 'Waiting for the first 1-minute window…' : 'No speed windows yet — start the demo pipeline.'} />}</div></GlassPanel>
}

export function DeliveriesPanel() {
  const { windows, summary, socketStatus, execution } = useDashboard()
  const rows = windows.filter((window) => window && window.sinkId === 'delSink' && Number.isFinite(window.windowEnd)).sort((a, b) => a.windowEnd - b.windowEnd)
  const minuteTotals = new Map<number, number>()
  rows.forEach((window) => {
    const deliveries = window.values?.deliveries
    if (typeof deliveries === 'number' && Number.isFinite(deliveries)) minuteTotals.set(window.windowEnd, (minuteTotals.get(window.windowEnd) ?? 0) + deliveries)
  })
  const entries = [...minuteTotals.entries()].slice(-15)
  const values = entries.map(([, value]) => value)
  const latest = values.at(-1) ?? (typeof summary?.deliveriesLastMinute === 'number' && Number.isFinite(summary.deliveriesLastMinute) ? summary.deliveriesLastMinute : undefined)
  return <GlassPanel title="Deliveries / min">{latest !== undefined
    ? <div className="panel-metric">
      <p className="panel-metric__value"><strong>{number.format(latest)}</strong> <span>{latest === 1 ? 'delivery' : 'deliveries'} in the last completed sim minute</span></p>
      <Sparkline values={values} bars />
      {entries.length > 1 && <div className="panel-axis" aria-hidden="true"><time>{simClock(entries[0]![0])}</time><span>sim minute · latest highlighted</span><time>{simClock(entries.at(-1)![0])}</time></div>}
    </div>
    : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty={pipelineActive(execution?.state) ? 'Waiting for the first completed sim minute…' : 'No delivery totals computed yet.'} />}</GlassPanel>
}

function AlertRow({ alert, simTime }: { alert: Alert; simTime: number | null }) {
  const row = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const target = row.current
    if (!target || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const animation = target.animate([{ opacity: 0, transform: 'translateY(-5px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 180, easing: 'ease-out' })
    return () => animation.cancel()
  }, [])
  return <div ref={row} className="panel-row panel-row--alert"><span>{alert.message}</span><span className="panel-row__meta">since {simClock(alert.eventTime)} · <time>{relativeAlertTime(alert.eventTime, simTime)}</time></span></div>
}

export function AlertsPanel() {
  const { alerts, simTime, socketStatus, execution } = useDashboard()
  const visible = alerts.filter((alert) => alert && Number.isFinite(alert.eventTime))
  return <GlassPanel title="Alerts" status={<span className="panel-count">{number.format(visible.length)}</span>}><div className="panel-list" aria-live="polite">{visible.length ? [...visible].sort((a, b) => b.eventTime - a.eventTime).slice(0, 8).map((alert) => <AlertRow key={alert.alertId} alert={alert} simTime={simTime} />) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty={pipelineActive(execution?.state) ? 'No alerts — every truck is moving.' : 'No alerts yet — start the demo pipeline.'} />}</div></GlassPanel>
}

export function DeadLettersPanel() {
  const { deadLetters, socketStatus } = useDashboard()
  const [kind, setKind] = useState<DeadLetter['kind']>('REJECTED')
  const shown = deadLetters.filter((item) => item && item.kind === kind).slice(0, 5)
  const rejectedCount = deadLetters.filter((item) => item?.kind === 'REJECTED').length
  const lateCount = deadLetters.filter((item) => item?.kind === 'LATE').length
  // The dashboard keeps at most 200 dead letters, so a full list means "200 or more".
  const countLabel = (count: number) => count >= 200 ? '200+' : number.format(count)
  const tabLabels = { REJECTED: `Rejected (${countLabel(rejectedCount)})`, LATE: `Late (${countLabel(lateCount)})` }
  return <GlassPanel title="Dead letters"><div className="panel-tabs" role="tablist" aria-label="Dead letter kind">{(['REJECTED', 'LATE'] as const).map((item) => <button key={item} type="button" role="tab" className="panel-tab" aria-selected={kind === item} aria-pressed={kind === item} onClick={() => setKind(item)}>{tabLabels[item]}</button>)}</div><div className="panel-list">{shown.length ? shown.map((item) => <div className="panel-row panel-row--split" key={`${item.eventId}-${item.kind}`}><span className="panel-row__text" title={item.reason}>{item.reason}</span><time>{simClock(item.eventTime)}</time></div>) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty={kind === 'REJECTED' ? 'No rejected records yet.' : 'No late records yet.'} />}</div></GlassPanel>
}


function queuePercent(size: number, capacity: number) {
  return capacity > 0 ? Math.min(100, Math.max(0, (size / capacity) * 100)) : 0
}

export function WorkersPanel() {
  const { workers, socketStatus } = useDashboard()
  const displayable = workers.filter((worker) => worker && Number.isFinite(worker.sourceQueue?.size) && Number.isFinite(worker.sourceQueue?.capacity) && Number.isFinite(worker.sinkQueue?.size) && Number.isFinite(worker.sinkQueue?.capacity))
  const alive = displayable.filter((worker) => worker.alive).length
  return <GlassPanel title="Workers" status={<span className="panel-count">{number.format(alive)} alive</span>}><div className="panel-list panel-list--workers">{displayable.length ? displayable.map((worker: WorkerInfo) => {
    const state: StatusState = !worker.alive ? 'failed' : worker.state === 'RESTORING' ? 'recovering' : worker.backpressured ? 'backpressured' : worker.state === 'RUNNING' ? 'running' : 'paused'
    const label = !worker.alive ? 'Dead' : worker.state === 'RESTORING' ? 'Recovering' : worker.state === 'RUNNING' ? 'Running' : 'Idle'
    const recordsPerSec = Number.isFinite(worker.recordsPerSec) ? worker.recordsPerSec.toFixed(1) : '—'
    const partitions = worker.partitions ?? []
    return <div className={`worker-row${worker.alive ? '' : ' worker-row--dead'}`} key={worker.workerId}>
      <div className="worker-row__heading"><strong>{worker.workerId}</strong><StatusPill state={state === 'backpressured' ? 'running' : state} label={label} />{worker.alive && worker.backpressured && <StatusPill state="backpressured" />}</div>
      {partitions.length
        ? <span className="worker-row__meta">Partitions {partitions.map((partition) => number.format(partition)).join(', ')} · {recordsPerSec} records/s</span>
        : <span className="worker-row__meta worker-row__meta--idle">{worker.alive ? 'Idle (no partitions)' : 'No partitions'}</span>}
      <QueueBar label="Source queue" size={worker.sourceQueue.size} capacity={worker.sourceQueue.capacity} />
      <QueueBar label="Sink queue" size={worker.sinkQueue.size} capacity={worker.sinkQueue.capacity} />
    </div>
  }) : <PanelState loading={socketStatus === 'connecting'} disconnected={socketStatus === 'disconnected'} empty="No workers connected yet." />}</div></GlassPanel>
}

function QueueBar({ label, size, capacity }: { label: string; size: number; capacity: number }) {
  return <div className="queue-bar"><span className="queue-bar__label">{label}</span><progress max={100} value={queuePercent(size, capacity)} aria-label={`${label} ${size} of ${capacity}`} /><span className="queue-bar__value">{size}/{capacity}</span></div>
}

export function ExecutionBanner() {
  const { execution, lastExecutionEvent, socketStatus } = useDashboard()
  const { status, isRecovering, recoveringCheckpoint } = useExecutionStatus()
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const checkpointRef = useRef<HTMLElement>(null)
  const completedCheckpoint = Math.max(lastExecutionEvent?.event === 'CHECKPOINT_COMPLETED' ? lastExecutionEvent.checkpointId ?? 0 : 0, execution?.lastCompletedCheckpoint ?? 0)
  // Contract 04: RECOVERED events carry the server-measured durationMs.
  const recoveredMs = (lastExecutionEvent as { durationMs?: unknown } | null)?.durationMs
  let label = !execution ? 'No execution' : humanState(execution.state)
  let shortLabel: string | null = null
  if (isRecovering) {
    label = `Recovering from checkpoint #${number.format(recoveringCheckpoint)}`
    shortLabel = `Recovering · #${number.format(recoveringCheckpoint)}`
  } else if (lastExecutionEvent?.event === 'WORKER_DEAD') label = `Worker ${lastExecutionEvent.workerId ?? 'unknown'} lost`
  else if (lastExecutionEvent?.event === 'RECOVERED') label = typeof recoveredMs === 'number' ? `Recovered in ${formatApiDuration(recoveredMs)}` : 'Recovered'
  else if (completedCheckpoint > 0 && execution?.state === 'RUNNING') label = `Checkpoint #${number.format(completedCheckpoint)} saved`
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
  useEffect(() => {
    if (lastExecutionEvent?.event === 'CHECKPOINT_COMPLETED' && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      checkpointRef.current?.animate([{ opacity: 0.35 }, { opacity: 1 }], { duration: 220, easing: 'ease-out' })
    }
  }, [lastExecutionEvent])
  const connected = socketStatus === 'connected'
  return <section className={`execution-banner execution-banner--${status}`} aria-label="Execution status" aria-live="polite">
    <ExecutionStatusPill status={status} />
    <strong ref={checkpointRef} className="execution-banner__label">{shortLabel
      ? <><span className="execution-banner__full">{label}</span><span className="execution-banner__short" aria-hidden="true">{shortLabel}</span></>
      : label}</strong>
    <span className={`execution-banner__connection execution-banner__connection--${socketStatus}`}>
      <span className="execution-banner__connection-icon" aria-hidden="true">{connected ? '●' : '⚠'}</span>
      {connected ? 'Live' : socketStatus === 'connecting' ? 'Connecting…' : 'Live connection lost — reconnecting…'}
    </span>
    {!execution && socketStatus !== 'disconnected' && <ClayButton onClick={startDemo} disabled={starting}>{starting ? 'Starting…' : 'Start demo pipeline'}</ClayButton>}
    {error && <span role="alert" className="execution-banner__error">{error}</span>}
  </section>
}
