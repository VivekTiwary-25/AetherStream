import { useState } from 'react'
import { useAetherMessages } from '../../api/ws'
import type { Alert, DeadLetter, Flow, SimulatorTruck, WebSocketMessage, WindowResult } from '../../api/types'
import { useDashboard } from '../../app/state/store'
import { useWindows } from '../shell/windows'
import { Icon } from '../shell/Icon'
import { Bars, ChartRow, LineChart } from './charts/Charts'
import { groupTruckWindows, mapFailedRecords, windowLengthLabel } from './data'
import './analytics.css'

export type AnalyticsId = 'truck-speeds' | 'deliveries' | 'alerts' | 'events-processed' | 'failed-records'
type FlowSample = Flow & { at: number }
type TruckSample = { truckId: string; speed: number; at: number }

export function AnalyticsPanel({ id }: { id: AnalyticsId }) {
  const windowsApi = useWindows()
  const { windows, alerts, deadLetters, socketStatus, execution } = useDashboard()
  const [trucks, setTrucks] = useState<SimulatorTruck[]>([])
  const [speedSamples, setSpeedSamples] = useState<TruckSample[]>([])
  const [flowSamples, setFlowSamples] = useState<FlowSample[]>([])
  const [selectedTruck, setSelectedTruck] = useState<string | null>(null)
  const [selectedFailure, setSelectedFailure] = useState<DeadLetter | null>(null)
  const [failureKind, setFailureKind] = useState<'ALL' | DeadLetter['kind']>('ALL')
  const [snapshotTrucks, setSnapshotTrucks] = useState<SimulatorTruck[]>([])

  useAetherMessages((message: WebSocketMessage) => {
    if (message.type === 'snapshot' && message.data && typeof message.data === 'object') {
      const snapshot = message.data as { simulator?: { trucks?: SimulatorTruck[] } }
      if (Array.isArray(snapshot.simulator?.trucks)) setSnapshotTrucks(snapshot.simulator.trucks)
    }
    if (message.type === 'trucks' && message.data && typeof message.data === 'object' && 'trucks' in message.data) {
      const data = message.data as { trucks: SimulatorTruck[] }
      if (!Array.isArray(data.trucks)) return
      setTrucks(data.trucks)
      const at = Date.now()
      setSpeedSamples((samples) => [...samples, ...data.trucks.filter((truck) => Number.isFinite(truck.speedKmh)).map((truck) => ({ truckId: truck.truckId, speed: truck.speedKmh, at }))].slice(-600))
    }
    if (message.type === 'flow' && message.data && typeof message.data === 'object') {
      setFlowSamples((samples) => [...samples, { ...(message.data as Flow), at: Date.now() }].slice(-300))
    }
  })

  const currentTrucks = trucks.length ? trucks : snapshotTrucks

  const speedWindows = windows.filter((window) => window?.sinkId === 'speedSink')
  const deliveryWindows = windows.filter((window) => window?.sinkId === 'delSink')
  const truckRows = groupTruckWindows(speedWindows)
  const speedByTruck = new Map(truckRows.map((row) => [row.truckId, row.latest]))
  const truckIds = [...new Set([...currentTrucks.map((truck) => truck.truckId), ...speedByTruck.keys()])].sort((a, b) => a.localeCompare(b))
  const latestDeliveryByTruck = new Map<string, WindowResult>()
  const deliveryByWindow = new Map<number, number>()
  for (const row of deliveryWindows) {
    const previous = latestDeliveryByTruck.get(row.key)
    if (!previous || previous.windowEnd < row.windowEnd) latestDeliveryByTruck.set(row.key, row)
    const deliveries = row.values?.deliveries
    if (Number.isFinite(row.windowEnd) && typeof deliveries === 'number' && Number.isFinite(deliveries)) deliveryByWindow.set(row.windowEnd, (deliveryByWindow.get(row.windowEnd) ?? 0) + deliveries)
  }
  const deliveryEntries = [...deliveryByWindow].sort(([a], [b]) => a - b).slice(-15)
  const sortedAlerts = [...alerts].sort((a, b) => b.eventTime - a.eventTime).slice(0, 8)
  const alertByMinute = new Map<number, number>()
  for (const alert of alerts) {
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
      <p className="an-caption">Current speed from live simulator readings · completed averages and reading counts use simulation windows.</p>
      {truckIds.length ? <><TableHead columns={['Truck', 'Now', 'Window avg · readings']} /><ul className="an-list" aria-label="Truck speeds">{truckIds.map((truckId) => {
        const current = currentTrucks.find((truck) => truck.truckId === truckId)
        const latest = speedByTruck.get(truckId)
        return <li key={truckId}><button className="an-row" type="button" data-testid={`truck-row-${truckId}`} onClick={() => showTruck(truckId)}>
          <strong>{truckId}</strong><span>{current ? `${current.speedKmh.toFixed(1)} km/h now` : 'Live speed unavailable'}</span>
          <span>{latest ? `${typeof latest.values.avgSpeedKmh === 'number' ? `${latest.values.avgSpeedKmh.toFixed(1)} km/h ${windowLengthLabel(latest)}` : 'No average'} · ${latest.eventCount} readings` : 'No completed speed window'}</span>
        </button></li>
      })}</ul></> : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No live trucks or completed speed windows yet." />}
    </section>}
    {id === 'deliveries' && <section>
      <p className="an-caption">Per-truck counts use each truck's latest completed delSink window; the trend sums by simulation window end.</p>
      {latestDeliveryByTruck.size > 0 && <TableHead columns={['Truck', 'Latest window', 'Window end']} />}
      <div className="an-list">{latestDeliveryByTruck.size ? [...latestDeliveryByTruck].sort(([a], [b]) => a.localeCompare(b)).map(([truckId, row]) => <div className="an-row" key={truckId}><strong>{truckId}</strong><span>{row.values.deliveries ?? 0} deliveries</span><span>{simClock(row.windowEnd)} sim time</span></div>) : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No delivery windows yet." />}</div>
      <Bars values={deliveryEntries.map(([, count]) => count)} label="Total deliveries per completed window" unit="deliveries" />
    </section>}
    {id === 'alerts' && <section className="an-cols-alerts">
      <p className="an-caption">Alert event times are simulation time.</p>
      <Bars values={alertEntries.map(([, count]) => count)} label="Alerts per simulation minute" unit="alerts/min" />
      {sortedAlerts.length ? <><TableHead columns={['Truck', 'Alert', 'Sim time']} /><div className="an-list">{sortedAlerts.map((alert) => <AlertRow key={alert.alertId} alert={alert} />)}</div></> : <EmptyState socketStatus={socketStatus} execution={execution?.state} message="No alerts yet." />}
    </section>}
    {id === 'events-processed' && <section className="an-cols-2">
      <p className="an-caption">Rates are live flow messages. Per-partition values are estimates divided evenly across owned partitions.</p>
      <LineChart values={flowSamples.map((sample) => sample.eventsPerSec)} label="Events processed" unit="events/s" times={flowTimes(flowSamples)} />
      <ChartRow>
        <LineChart values={flowSamples.map((sample) => sample.rejectedPerSec)} label="Rejected" unit="records/s" />
        <LineChart values={flowSamples.map((sample) => sample.latePerSec)} label="Late" unit="records/s" />
      </ChartRow>
      {flowSamples.length > 0 && <TableHead columns={['Partition', 'Estimated rate']} />}
      {flowSamples.length > 0 && <div className="an-list">{Object.keys(flowSamples.at(-1)!.byPartition).sort((a, b) => Number(a) - Number(b)).map((partition) => <div className="an-row" key={partition}><strong>Partition {partition}</strong><span>Estimated {flowSamples.at(-1)!.byPartition[partition]!.toFixed(2)} events/s</span></div>)}</div>}
      {!flowSamples.length && <EmptyState socketStatus={socketStatus} execution={execution?.state} message="Waiting for live flow samples." />}
    </section>}
    {id === 'failed-records' && <section>
      <p className="an-caption">Event timestamps are simulation time; partition and offset identify the consumed record.</p>
      <div className="an-tabs" aria-label="Failed record kind">{(['ALL', 'REJECTED', 'LATE'] as const).map((kind) => <button type="button" key={kind} aria-pressed={failureKind === kind} onClick={() => setFailureKind(kind)}>{kind === 'ALL' ? 'All' : kind}</button>)}</div>
      {failedRows.length ? <div className="an-list">{failedRows.map((row) => <button className="an-row an-row--failure" type="button" key={`${row.partition}:${row.offset}:${row.eventId}`} onClick={() => showFailure(deadLetters.find((item) => item.eventId === row.eventId)!)}>
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
    <p className="an-caption">Window averages ({duration}) with live speed samples collected since this window opened.</p>
    <LineChart values={history.map((row) => row.values.avgSpeedKmh).filter((value): value is number => typeof value === 'number' && Number.isFinite(value))} label="Completed speed-window averages" unit="km/h" />
    <LineChart values={samples.map((sample) => sample.speed)} label={`Live samples since ${samples[0] ? clock(samples[0].at) : 'window opened'}`} unit="km/h" times={samples[0] ? [clock(samples[0].at), clock(samples.at(-1)!.at)] : undefined} />
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
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString().slice(11, 19) : 'time unavailable'
}

function clock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString()
}

function flowTimes(samples: FlowSample[]): string[] | undefined {
  if (!samples.length) return undefined
  return [clock(samples[0]!.at), clock(samples.at(-1)!.at)]
}
