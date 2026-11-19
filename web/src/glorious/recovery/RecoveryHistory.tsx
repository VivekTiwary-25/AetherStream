import { eventLabel, recoveryDuration, seconds, type RecoveryEntry } from './model'

export function RecoveryHistory({ entries, loaded, unavailable = false }: { entries: RecoveryEntry[]; loaded: boolean; unavailable?: boolean }) {
  return <section aria-labelledby="recovery-activity-title">
    <h2 id="recovery-activity-title">Activity & failure history</h2>
    <p className="recovery-muted">Newest first · Up to 200 server-retained events, including failures from terminal commands.</p>
    {!entries.length ? <p>{unavailable ? 'Recovery history could not be loaded.' : loaded ? 'No recovery activity yet.' : 'Loading recovery history…'}</p> :
      <ol className="recovery-history">{entries.map((entry) => {
        const duration = recoveryDuration(entry, entries)
        return <li key={entry.id} className={`recovery-event recovery-event--${entry.event.toLowerCase()}`}>
          <div className="recovery-event-heading"><strong>{eventLabel(entry.event)}</strong>
            <time dateTime={new Date(entry.at).toISOString()}>{new Date(entry.at).toLocaleString()}</time>
          </div>
          <p className="recovery-event-meta">
            {entry.workerId && <span>{entry.workerId}</span>}
            {entry.executionId && <span>Execution {entry.executionId}</span>}
            {entry.generation != null && <span>Generation {entry.generation}</span>}
            {entry.actor && <span>Actor: {entry.actor}</span>}
          </p>
          {entry.reason && <p>{entry.reason}</p>}
          {(entry.event === 'RECOVERING' || entry.event === 'RECOVERED') && <p>
            {entry.restoredFromCheckpoint != null ? `Restored from checkpoint #${entry.restoredFromCheckpoint}` : 'Restored checkpoint not recorded'}
            {entry.generation != null ? ` · generation ${entry.generation}` : ''}
          </p>}
          {entry.event === 'RECOVERED' && <p className="recovery-pause">{duration !== null
            ? `Processing paused for ${seconds(duration)} s during recovery.`
            : 'Recovery pause duration unavailable (no complete timing recorded).'}</p>}
          {entry.event === 'CHECKPOINT_COMPLETED' && entry.checkpointId != null && <p>Checkpoint #{entry.checkpointId}
            {entry.durationMs != null ? ` · ${entry.durationMs} ms` : ''}</p>}
          {!!entry.partitionChanges?.length && <ul className="recovery-changes">{entry.partitionChanges.map((change) =>
            <li key={change.partition}>P{change.partition}: {change.oldOwner ?? 'Unassigned'} → {change.newOwner ?? 'Unassigned'}</li>)}</ul>}
        </li>
      })}</ol>}
  </section>
}
