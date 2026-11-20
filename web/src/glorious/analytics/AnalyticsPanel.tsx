import { useEffect, useRef, useState } from 'react'
import { useAetherMessages } from '../../api/ws'
import type { Alert, DeadLetter, SimulatorTruck, WebSocketMessage, WindowResult } from '../../api/types'
import { useDashboard } from '../../app/state/store'
import { useWindows } from '../shell/windows'
import { Icon } from '../shell/Icon'
import { Bars, ChartRow, LineChart } from './charts/Charts'
import { finiteNumber, groupTruckWindows, mapFailedRecords, readFlow, uniqueAlerts, uniqueWindows, validTruckReadings, windowLengthLabel, type FlowReading } from './data'
import './analytics.css'

export type AnalyticsId = 'truck-speeds' | 'deliveries' | 'alerts' | 'events-processed' | 'failed-records'
type FlowSample = FlowReading & { at: number }
type TruckSample = { truckId: string; speed: number | undefined; at: number }

function gapTruckSamples(samples: TruckSample[], at: number): TruckSample[] {
  const latest = new Map(samples.map((sample) => [sample.truckId, sample]))
  return [...samples, ...[...latest.values()].filter((sample) => sample.speed !== undefined).map(({ truckId }) => ({ truckId, speed: undefined, at }))].slice(-600)
}

export function AnalyticsPanel({ id }: { id: AnalyticsId }) {
  const windowsApi = useWindows()
  const { windows, alerts, deadLetters, socketStatus, execution } = useDashboard()
  const [trucks, setTrucks] = useState<SimulatorTruck[]>([])
  const [speedSamples, setSpeedSamples] = useState<TruckSample[]>([])
  const [flowSamples, setFlowSamples] = useState<FlowSample[]>([])
  const [selectedTruck, setSelectedTruck] = useState<string | null>(null)
  const [selectedFailure, setSelectedFailure] = useState<DeadLetter | null>(null)
  const [failureKind, setFailureKind] = useState<'ALL' | DeadLetter['kind']>('ALL')
  const lastTruckAt = useRef<number | null>(null)
  const [trucksFresh, setTrucksFresh] = useState(false)
  const lastFlowAt = useRef<number | null>(null)
  const [flowFresh, setFlowFresh] = useState(false)

  const liveStatus = useAetherMessages((message: WebSocketMessage) => {
    if (message.type === 'snapshot' && message.data && typeof message.data === 'object') {
      const snapshot = message.data as { simulator?: { trucks?: SimulatorTruck[] } }
      setTrucks(Array.isArray(snapshot.simulator?.trucks) ? validTruckReadings(snapshot.simulator.trucks) : [])
      lastTruckAt.current = Date.now()
      setTrucksFresh(true)
      setSpeedSamples((samples) => gapTruckSamples(samples, Date.now()))
      lastFlowAt.current = null
      setFlowFresh(false)
      setFlowSamples((samples) => samples.length ? [...samples, { ...readFlow(null), at: Date.now() }].slice(-300) : samples)
    }
    if (message.type === 'trucks' && message.data && typeof message.data === 'object' && 'trucks' in message.data) {
      const data = message.data as { trucks: SimulatorTruck[] }
      if (!Array.isArray(data.trucks)) return
      const readings = validTruckReadings(data.trucks)
      setTrucks(readings)
      const at = Date.now()
      lastTruckAt.current = at
      setTrucksFresh(true)
      setSpeedSamples((samples) => {
        const present = new Set(readings.map((truck) => truck.truckId))
        const latest = new Map(samples.map((sample) => [sample.truckId, sample]))
        const removed = [...latest.values()].filter((sample) => sample.speed !== undefined && !present.has(sample.truckId))
        return [...samples,
          ...removed.map(({ truckId }) => ({ truckId, speed: undefined, at })),
          ...readings.map((truck) => ({ truckId: truck.truckId, speed: finiteNumber(truck.speedKmh), at })),
        ].slice(-600)
      })
    }
    if (message.type === 'flow') {
      const at = Date.now()
      lastFlowAt.current = at
      setFlowFresh(true)
      setFlowSamples((samples) => [...samples, { ...readFlow(message.data), at }].slice(-300))
    }
  })

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (lastTruckAt.current !== null && Date.now() - lastTruckAt.current > 4500) {
        lastTruckAt.current = null
        setTrucksFresh(false)
        setSpeedSamples((samples) => gapTruckSamples(samples, Date.now()))
      }
      if (lastFlowAt.current !== null && Date.now() - lastFlowAt.current > 4500) {
        lastFlowAt.current = null
        setFlowFresh(false)
        setFlowSamples((samples) => [...samples, { ...readFlow(null), at: Date.now() }].slice(-300))
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (liveStatus === 'connected') return
    lastTruckAt.current = null
    setSpeedSamples((samples) => gapTruckSamples(samples, Date.now()))
    setTrucksFresh(false)
    setFlowFresh(false)
    lastFlowAt.current = null
    setFlowSamples((samples) => samples.length ? [...samples, { ...readFlow(null), at: Date.now() }].slice(-300) : samples)
  }, [liveStatus])

  const latestPartitions = flowFresh && liveStatus === 'connected' ? flowSamples.at(-1)?.byPartition : undefined

  const currentTrucks = trucks

  const speedWindows = windows.filter((window) => window?.sinkId === 'speedSink')
  const deliveryWindows = uniqueWindows(windows.filter((window) => window?.sinkId === 'delSink' && typeof window.key === 'string' && Number.isFinite(window.windowStart) && Number.isFinite(window.windowEnd)))
  const truckRows = groupTruckWindows(speedWindows)
  const speedByTruck = new Map(truckRows.map((row) => [row.truckId, row.latest]))
  const truckIds = [...new Set([...currentTrucks.map((truck) => truck.truckId), ...speedByTruck.keys()])].sort((a, b) => a.localeCompare(b))
  const latestDeliveryByTruck = new Map<string, WindowResult>()
  const deliveryByWindow = new Map<number, number | undefined>()
  for (const row of deliveryWindows) {
    const previous = latestDeliveryByTruck.get(row.key)
    if (!previous || previous.windowEnd < row.windowEnd) latestDeliveryByTruck.set(row.key, row)
    const deliveries = finiteNumber(row.values?.deliveries)
    const previousTotal = deliveryByWindow.get(row.windowEnd)
    const total = deliveryByWindow.has(row.windowEnd) ? previousTotal : 0
    deliveryByWindow.set(row.windowEnd, deliveries !== undefined && deliveries >= 0 && total !== undefined ? total + deliveries : undefined)
  }
  const deliveryEntries = [...deliveryByWindow].sort(([a], [b]) => a - b).slice(-15)
  const distinctAlerts = uniqueAlerts(alerts)
  const sortedAlerts = [...distinctAlerts].sort((a, b) => b.eventTime - a.eventTime).slice(0, 8)
  const alertByMinute = new Map<number, number>()
  for (const alert of distinctAlerts) {
    if (Number.isFinite(alert.eventTime)) {
      const minute = Math.floor(alert.eventTime / 60_000) * 60_000
      alertByMinute.set(minute, (alertByMinute.get(minute) ?? 0) + 1)
    }
  }
  const alertEntries = [...alertByMinute].sort(([a], [b]) => a - b).slice(-15)
  const failedRows = mapFailedRecords(deadLetters).filter((record) => failureKind === 'ALL' || record.kind === failureKind).slice(0, 30)
  const heading = selectedTruck ? `Truck ${selectedTruck}` : selectedFailure ? 'Failed record details' : undefined

  function showTruck(truckId: string) {
    setSelectedTruck(truckId)
    windowsApi.update(`analytics.${id}`, { title: `Truck Speeds · ${truckId}`, onBack: () => { setSelectedTruck(null); windowsApi.update(`analytics.${id}`, { title: 'Truck Speeds', onBack: undefined }) } })
  }

  function showFailure(record: DeadLetter) {
    setSelectedFailure(record)
    windowsApi.update(`analytics.${id}`, { title: 'Failed record details', onBack: () => { setSelectedFailure(null); windowsApi.update(`analytics.${id}`, { title: 'Failed Records', onBack: undefined }) } })
  }

  const content = selectedTruck ? <TruckDetail truckId={selectedTruck} windows={speedWindows} liveSamples={speedSamples} /> : selectedFailure ? <FailureDetail record={selectedFailure} /> : <>
    {id === 'truck-speeds' && <section data-testid="analytics-truck-speeds">
      <p className="an-caption">“Now” is each truck’s latest live speed. Averages cover the last completed time window (a fixed slice of simulated time).</p>
      {truckIds.length ? <><TableHead columns={['Truck', 'Now', 'Recent average · readings']} /><ul className="an-list" aria-label="Truck speeds">{truckIds.map((truckId) => {
        const current = currentTrucks.find((truck) => truck.truckId === truckId)
        const latest = speedByTruck.get(truckId)
        return <li key={truckId}><button className="an-row" type="button" data-testid={`truck-row-${truckId}`} onClick={() => showTruck(truckId)}>
          <strong>{truckId}</strong><span>{current && trucksFresh && liveStatus === 'connected' && Number.isFinite(current.speedKmh) ? `${current.speedKmh.toFixed(1)} km/h now` : 'Live speed unavailable'}</span>
          <span>{latest ? `${finiteNumber(latest.values?.avgSpeedKmh) !== undefined ? `${latest.values.avgSpeedKmh!.toFixed(1)} km/h ${windowLengthLabel(latest)}` : 'No average'} · ${finiteNumber(latest.eventCount) !== undefined && latest.eventCount >= 0 ? `${latest.eventCount} readings` : 'readings unavailable'}` : 'No average yet'}</span>
        </button></li>
      })}</ul></> : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No truck speeds yet." />}
    </section>}
    {id === 'deliveries' && <section>
      <p className="an-caption">Deliveries per truck in its latest completed time window. The chart sums reported truck results for each window; unavailable counts leave a gap.</p>
      {latestDeliveryByTruck.size > 0 && <TableHead columns={['Truck', 'Latest window', 'Window ended']} />}
      <div className="an-list">{latestDeliveryByTruck.size ? [...latestDeliveryByTruck].sort(([a], [b]) => a.localeCompare(b)).map(([truckId, row]) => <div className="an-row" key={truckId}><strong>{truckId}</strong><span>{finiteNumber(row.values?.deliveries) !== undefined && row.values.deliveries! >= 0 ? `${row.values.deliveries} deliveries` : 'Delivery count unavailable'}</span><span>{simClock(row.windowEnd)} sim time</span></div>) : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No deliveries counted yet." />}</div>
      <Bars values={deliveryEntries.map(([, count]) => count)} label="Total deliveries per completed window" unit="deliveries" />
    </section>}
    {id === 'alerts' && <section className="an-cols-alerts">
      <p className="an-caption">Times are simulated time (the demo’s clock), not your local time. Counts cover loaded alerts; the chart shows only minutes with reported alerts.</p>
      <Bars values={alertEntries.map(([, count]) => count)} label="Alerts per simulation minute" unit="alerts/min" />
      {sortedAlerts.length ? <><TableHead columns={['Truck', 'Alert', 'Sim time']} /><div className="an-list">{sortedAlerts.map((alert) => <AlertRow key={alert.alertId} alert={alert} />)}</div></> : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No alerts yet." />}
    </section>}
    {id === 'events-processed' && <section className="an-cols-2">
      <p className="an-caption">Engine throughput samples since this panel opened (local clock). Charts retain history when live data is unavailable. Current per-partition rates are estimates, splitting each worker’s rate evenly across its partitions.</p>
      <LineChart values={flowSamples.map((sample) => sample.eventsPerSec)} label="Events processed" unit="events/s" times={flowTimes(flowSamples)} />
      <ChartRow>
        <LineChart values={flowSamples.map((sample) => sample.rejectedPerSec)} label="Rejected" unit="records/s" />
        <LineChart values={flowSamples.map((sample) => sample.latePerSec)} label="Late" unit="records/s" />
      </ChartRow>
      {latestPartitions && Object.keys(latestPartitions).length > 0 ? <><TableHead columns={['Partition', 'Estimated rate']} />
        <div className="an-list">{Object.keys(latestPartitions).sort((a, b) => Number(a) - Number(b)).map((partition) => <div className="an-row" key={partition}><strong>Partition {partition}</strong><span>{latestPartitions[partition] !== undefined ? `Estimated ${latestPartitions[partition].toFixed(2)} events/s` : 'Rate unavailable'}</span></div>)}</div></>
        : <p className="an-empty">{liveStatus !== 'connected' ? 'Disconnected — live throughput unavailable.' : !flowSamples.length ? 'Waiting for live throughput data.' : !flowFresh ? 'Live throughput unavailable — waiting for a fresh sample.' : latestPartitions ? 'No partition rates reported.' : 'Partition rates unavailable.'}</p>}
    </section>}
    {id === 'failed-records' && <section>
      <p className="an-caption">Records the engine set aside: rejected (invalid data) or late (arrived after their time window closed). Times are simulated time; partition and offset show exactly where each record was read.</p>
      <div className="an-tabs" aria-label="Failed record kind">{(['ALL', 'REJECTED', 'LATE'] as const).map((kind) => <button type="button" key={kind} aria-pressed={failureKind === kind} onClick={() => setFailureKind(kind)}>{kind === 'ALL' ? 'All' : kind === 'REJECTED' ? 'Rejected' : 'Late'}</button>)}</div>
      {failedRows.length ? <div className="an-list">{failedRows.map((row) => <button className="an-row an-row--failure" type="button" key={`${row.partition}:${row.offset}:${row.eventId}`} onClick={() => showFailure(row)}>
        <KindChip kind={row.kind} /><strong className="an-truncate" title={row.eventId}>{row.eventId}</strong><span className="an-reason">{row.reason}</span><span className="an-meta">{simClock(row.eventTime)} · partition {row.partition}, offset {row.offset}</span>
      </button>)}</div> : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No failed records yet." />}
    </section>}
  </>

  return <div className="an-window">{heading && <div className="an-detail-heading"><button type="button" className="an-back" aria-label="Back" onClick={() => {
    if (selectedTruck) { setSelectedTruck(null); windowsApi.update(`analytics.${id}`, { title: 'Truck Speeds', onBack: undefined }) }
    else { setSelectedFailure(null); windowsApi.update(`analytics.${id}`, { title: 'Failed Records', onBack: undefined }) }
  }}><Icon name="back" /></button><span>{heading}</span></div>}{content}</div>
}

function TruckDetail({ truckId, windows, liveSamples }: { truckId: string; windows: WindowResult[]; liveSamples: TruckSample[] }) {
  const history = groupTruckWindows(windows).find((truck) => truck.truckId === truckId)?.history ?? []
  const samples = liveSamples.filter((sample) => sample.truckId === truckId)
  const duration = history[0] ? windowLengthLabel(history[0]).replace(' avg', '') : 'completed windows'
  return <div data-testid="truck-speed-chart">
    <p className="an-caption">Averages for each completed time window ({duration}), plus live speed readings since you opened this panel.</p>
    <LineChart values={history.map((row) => finiteNumber(row.values?.avgSpeedKmh))} label="Average speed per completed window" unit="km/h" />
    <LineChart values={samples.map((sample) => sample.speed)} label={`Live readings since ${samples[0] ? clock(samples[0].at) : 'panel opened'}`} unit="km/h" times={samples[0] ? [clock(samples[0].at), clock(samples.at(-1)!.at)] : undefined} />
  </div>
}

function FailureDetail({ record }: { record: DeadLetter }) {
  return <dl className="an-details">{[
    ['Kind', record.kind], ['Reason', record.reason], ['Partition', record.partition], ['Offset', record.offset], ['Event ID', record.eventId], ['Event time (simulation)', simClock(record.eventTime)],
  ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{label === 'Kind' ? <KindChip kind={record.kind} /> : value}</dd></div>)}</dl>
}

function KindChip({ kind }: { kind: DeadLetter['kind'] }) {
  return <span className={`an-chip an-chip--${String(kind).toLowerCase()}`}>{kind}</span>
}

function TableHead({ columns }: { columns: string[] }) {
  return <div className="an-head" aria-hidden="true">{columns.map((column) => <span key={column}>{column}</span>)}</div>
}

function AlertRow({ alert }: { alert: Alert }) {
  return <div className="an-row"><strong>{alert.key}</strong><span>{alert.message}</span><time>{simClock(alert.eventTime)} sim time</time></div>
}

function EmptyState({ socketStatus, execution, message }: { socketStatus: string; execution?: string; message: string }) {
  return <p className="an-empty">{socketStatus === 'disconnected' ? 'Disconnected — live data unavailable.' : execution !== 'RUNNING' && execution !== 'RECOVERING' ? 'No data yet — start the demo pipeline.' : message}</p>
}

function simClock(timestamp: number): string {
  return Number.isFinite(timestamp) && Number.isFinite(new Date(timestamp).getTime()) ? new Date(timestamp).toISOString().slice(11, 19) : 'time unavailable'
}

function clock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString()
}

function flowTimes(samples: FlowSample[]): string[] | undefined {
  if (!samples.length) return undefined
  return [clock(samples[0]!.at), clock(samples.at(-1)!.at)]
}
