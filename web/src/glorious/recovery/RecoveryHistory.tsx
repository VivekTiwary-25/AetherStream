import { eventLabel, recoveryDuration, seconds, type RecoveryEntry } from './model'

export function RecoveryHistory({ entries, loaded, unavailable = false, currentOwners = {} }: {
  entries: RecoveryEntry[]
  loaded: boolean
  unavailable?: boolean
  currentOwners?: Record<number, string>
}) {
  return <section aria-labelledby="recovery-activity-title">
    <h2 id="recovery-activity-title">Activity & failure history</h2>
    <p className="recovery-muted">Newest event time first (ID breaks ties) · The last 200 events kept by the server, including failures triggered from the terminal.</p>
    {!entries.length ? <p>{unavailable ? 'Recovery history could not be loaded.' : loaded ? 'No recovery activity yet.' : 'Loading recovery history…'}</p> :
      <ol className="recovery-history">{entries.map((entry) => {
        const duration = recoveryDuration(entry, entries)
        return <li key={entry.id} className={`recovery-event recovery-event--${entry.event.toLowerCase()}`}>
          <div className="recovery-event-heading"><strong>{eventLabel(entry.event)}</strong>
            <time dateTime={new Date(entry.at).toISOString()}>{new Date(entry.at).toLocaleString()}</time>
          </div>
          <p className="recovery-event-meta">
            {entry.workerId && <span className="recovery-event-worker">{entry.workerId}</span>}
            {entry.executionId && <span>Execution {entry.executionId}</span>}
            {entry.generation != null && <span>Generation {entry.generation}</span>}
            {entry.actor && <span>Actor: {entry.actor}</span>}
          </p>
          {entry.reason && <p>{entry.reason}</p>}
          {(entry.event === 'RECOVERING' || entry.event === 'RECOVERED') && <p>
            {entry.restoredFromCheckpoint != null ? `Restored from checkpoint #${entry.restoredFromCheckpoint}` : 'Checkpoint used: not recorded'}
            {entry.generation != null ? ` · generation ${entry.generation}` : ''}
          </p>}
          {entry.event === 'RECOVERED' && <p className="recovery-pause">{duration !== null
            ? `Processing paused for ${seconds(duration)} s during recovery.`
            : 'Pause length unknown (timing was not fully recorded).'}</p>}
          {entry.event === 'CHECKPOINT_COMPLETED' && entry.checkpointId != null && <p>Checkpoint #{entry.checkpointId}
            {entry.durationMs != null ? ` · ${entry.durationMs} ms` : ''}</p>}
          {!!entry.partitionChanges?.length && <ul className="recovery-changes">{entry.partitionChanges.filter((change) =>
            currentOwners[change.partition] === change.newOwner).map((change) =>
            <li key={change.partition}>P{change.partition}: {change.oldOwner ?? 'Unassigned'} → {change.newOwner ?? 'Unassigned'}</li>)}</ul>}
        </li>
      })}</ol>}
  </section>
}
