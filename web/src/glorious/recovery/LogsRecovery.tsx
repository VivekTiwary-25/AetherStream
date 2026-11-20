import { useEffect, useState } from 'react'
import type { WorkerInfo } from '../../api/types'
import { useDashboard } from '../../app/state/store'
import { GlassSurface } from '../shell/GlassSurface'
import { deriveWorkerState, recentPartitionChange, seconds } from './model'
import { RecoveryHistory } from './RecoveryHistory'
import { useRecoveryFeed } from './useRecoveryFeed'
import './recovery.css'

const labels = { running: 'Running', dead: 'Dead', recovering: 'Recovering', rejoined: 'Rejoined', idle: 'Idle', unavailable: 'Unavailable' }
const number = (value: number | undefined) => value != null && Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: 1 }) : 'Unavailable'

export function LogsRecovery() {
  const { workers } = useDashboard()
  const feed = useRecoveryFeed()
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(timer)
  }, [])
  const currentHistory = feed.history.filter((entry) => entry.executionId === feed.execution?.id)
  const workerIds = [...new Set([...workers.map((worker) => worker.workerId), ...currentHistory.flatMap((entry) => entry.workerId ? [entry.workerId] : [])])].sort()
  const latestRecovery = currentHistory.find((entry) => entry.event === 'RECOVERING' || entry.event === 'RECOVERED')
  return <div className="recovery-panel" data-testid="logs-recovery">
    <div className="recovery-intro">
      <p>How the engine’s workers (the processes that share the work) are doing, which worker owns each partition (slice of the stream), and the checkpoints (saved progress) it recovers from after a failure.</p>
      <p role="status" className="recovery-muted">{feed.status === 'connected' ? 'Live connection' : `${feed.status === 'connecting' ? 'Connecting' : 'Disconnected'} · values may be stale`}</p>
    </div>
    {feed.historyError && <p role="alert">Recovery history unavailable: {feed.historyError}</p>}
    {feed.executionError && <p role="alert">Current run unavailable: {feed.executionError}</p>}
    {latestRecovery?.event === 'RECOVERING' && <p className="recovery-pause">Processing paused for {seconds(now - latestRecovery.at)} s during recovery
      {feed.status !== 'connected' ? ' (last recorded recovery; connection unavailable)' : ' (in progress)'}.</p>}
    <section aria-labelledby="recovery-workers-title">
      <h2 id="recovery-workers-title">Workers</h2>
      <p className="recovery-muted">A worker that died or restarted stays marked for at least 3 seconds so you can see it; its live numbers may already be newer.</p>
      <div className="recovery-workers">{workerIds.length ? workerIds.map((workerId, workerIndex) => {
        const worker = workers.find((candidate) => candidate.workerId === workerId)
        const state = deriveWorkerState(worker, workerId, feed.history, feed.execution, now)
        return <GlassSurface strong className={`recovery-worker recovery-worker--owner-${workerIndex % 3}`} key={workerId}>
          <article data-testid={`worker-card-${workerId}`}>
            <div className="recovery-worker-heading"><h3>{workerId}</h3><span className={`recovery-status recovery-status--${state}`}>{labels[state]}</span></div>
            <p className="recovery-rate"><strong>{number(worker?.recordsPerSec)}</strong> <span>records/s</span></p>
            <div className="recovery-chips" aria-label={`${workerId} assigned partitions`}>
              {worker && !Array.isArray(worker.partitions) ? <span>Partition data unavailable</span>
                : worker?.partitions.length ? worker.partitions.map((partition) => <span className={`recovery-chip recovery-chip--owner-${workerIndex % 3}`} key={partition}>P{partition}</span>) : <span>No assigned partitions</span>}
            </div>
            <WorkerQueue label="Source queue" queue={worker?.sourceQueue} />
            <WorkerQueue label="Sink queue" queue={worker?.sinkQueue} />
            <p>Slowed by backpressure: {worker ? (worker.backpressured ? 'Yes' : 'No') : 'Unavailable'}</p>
            <p className="recovery-muted">Last heartbeat: {worker?.lastHeartbeatMs && Number.isFinite(worker.lastHeartbeatMs)
              ? `${seconds(now - worker.lastHeartbeatMs)} s ago` : 'Unavailable'}</p>
          </article>
        </GlassSurface>
      }) : <p>No workers reported yet.</p>}</div>
    </section>
    <section aria-labelledby="recovery-partitions-title">
      <h2 id="recovery-partitions-title">Partition ownership</h2>
      <div className="recovery-partitions">{[0, 1, 2, 3, 4, 5].map((partition) => {
        const owners = workers.filter((worker) => worker.alive && worker.partitions?.includes(partition))
        const ownershipUnknown = workers.some((worker) => worker.alive && !Array.isArray(worker.partitions))
        const change = recentPartitionChange(partition, feed.history, feed.execution?.id, now)
        return <div className="recovery-partition" data-testid={`partition-chip-${partition}`} key={partition}>
          <strong>P{partition}</strong><span className="recovery-partition-owners">{owners.length ? owners.map((owner) => <span className={`recovery-owner recovery-owner--${workerIds.indexOf(owner.workerId) % 3}`} key={owner.workerId}>{owner.workerId}</span>) : ownershipUnknown ? 'Unavailable' : 'Unassigned'}</span>
          {change && <small>{change.oldOwner ?? 'Unassigned'} → {change.newOwner ?? 'Unassigned'}</small>}
        </div>
      })}</div>
    </section>
    <section aria-labelledby="recovery-checkpoints-title">
      <h2 id="recovery-checkpoints-title">Completed checkpoints</h2>
      <p className="recovery-muted">Each checkpoint saves progress so a failed worker’s partitions can restart from it; generation counts restarts.</p>
      {feed.checkpointError && <p role="alert">Checkpoint history unavailable: {feed.checkpointError}</p>}
      {!feed.execution ? <p>No pipeline run is active.</p> : !feed.checkpoints.length ? <p>{feed.checkpointError ? 'Checkpoint history could not be loaded.' : feed.checkpointsLoaded ? 'No completed checkpoints yet.' : 'Loading completed checkpoints…'}</p> :
        <div className="recovery-table-wrap"><table className="recovery-table"><caption>Execution {feed.execution.id}</caption>
          <thead><tr><th scope="col">Checkpoint</th><th scope="col">Generation</th><th scope="col">Completed</th><th scope="col">Duration</th><th scope="col">Source offsets</th></tr></thead>
          <tbody>{[...feed.checkpoints].sort((a, b) => b.checkpointId - a.checkpointId).map((checkpoint) => <tr key={checkpoint.checkpointId}>
            <th scope="row">#{checkpoint.checkpointId}</th><td>{checkpoint.generation}</td>
            <td>{checkpoint.completedAt ? new Date(checkpoint.completedAt).toLocaleString() : 'Not recorded'}</td><td>{number(checkpoint.durationMs)} ms</td>
            <td>{Object.entries(checkpoint.sourceOffsets ?? {}).map(([partition, offset]) => `P${partition}: ${offset}`).join(' · ') || 'Not recorded'}</td>
          </tr>)}</tbody>
        </table></div>}
      {feed.execution?.restoredFromCheckpoint != null && <p>Current run restarted from checkpoint #{feed.execution.restoredFromCheckpoint} · generation {feed.execution.generation}</p>}
    </section>
    <RecoveryHistory entries={feed.history} loaded={feed.historyLoaded} unavailable={!!feed.historyError} />
  </div>
}

function WorkerQueue({ label, queue }: { label: string; queue: WorkerInfo['sourceQueue'] | undefined }) {
  return <div className="recovery-queue"><span>{label}: {queue ? `${number(queue.size)} / ${number(queue.capacity)}` : 'Unavailable'}</span>
    {queue && queue.capacity > 0 && <progress aria-label={label} max={queue.capacity} value={queue.size} />}
  </div>
}

