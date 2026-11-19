import { useEffect, useState } from 'react'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { createSavepoint, getCurrentExecution, getSavepoints, rescaleExecution, restoreSavepoint, type Savepoint } from './opsApi'

const RESTORE_CONFIRMATION = 'Restart the pipeline from this savepoint?'
const RESCALE_EXPLANATION = 'Pauses at the next checkpoint, moves partitions, resumes - a controlled pause, not live migration.'

export function OpsPanel() {
  const [executionId, setExecutionId] = useState<string | null>(null)
  const [executionState, setExecutionState] = useState<string | null>(null)
  const [savepoints, setSavepoints] = useState<Savepoint[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [savepointNotice, setSavepointNotice] = useState('')

  useEffect(() => {
    let mounted = true
    void Promise.all([getCurrentExecution(), getSavepoints()]).then(([execution, recent]) => {
      if (!mounted) return
      setExecutionState(execution?.state ?? null)
      setExecutionId(execution?.id ?? null)
      setSavepoints(recent)
    }).catch((reason: unknown) => {
      if (mounted) setError(reason instanceof Error ? reason.message : 'The control API could not load operations data.')
    })
    return () => { mounted = false }
  }, [])

  async function takeSavepoint() {
    if (pending || !executionId) return
    setPending(true)
    setError('')
    setSavepointNotice('')
    try {
      const saved = await createSavepoint(executionId)
      setSavepoints((current) => [saved, ...current.filter((item) => item.savepointId !== saved.savepointId)].slice(0, 5))
      setSavepointNotice(`Savepoint ${saved.savepointId} at checkpoint #${saved.checkpointId}`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The savepoint could not be created.')
    } finally {
      setPending(false)
    }
  }

  async function restore(savepointId: string) {
    if (pending || !executionId || !window.confirm(RESTORE_CONFIRMATION)) return
    setPending(true)
    setError('')
    try {
      await restoreSavepoint(executionId, { savepointId, idempotencyKey: crypto.randomUUID() })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The savepoint could not be restored.')
    } finally {
      setPending(false)
    }
  }

  async function rescale() {
    if (pending || !executionId) return
    setPending(true)
    setError('')
    try {
      await rescaleExecution(executionId)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The execution could not be rescaled.')
    } finally {
      setPending(false)
    }
  }

  const executionStatus = executionState ? executionState.charAt(0) + executionState.slice(1).toLowerCase() : 'No active execution'
  const statusColor = executionState === 'RUNNING' ? '#4ade80' : executionState === 'FAILED' ? '#f87171' : executionState ? '#fbbf24' : '#94a3b8'

  return (
    <GlassPanel title="Operations" status={<span style={{ color: statusColor }}>{executionStatus}</span>}>
      <div className="ops-panel__controls" aria-busy={pending}>
        <ClayButton type="button" disabled={pending || !executionId} onClick={() => void takeSavepoint()}>
          Take savepoint
        </ClayButton>
        {savepointNotice && <p className="ops-panel__success" role="status">{savepointNotice}</p>}
        <div className="ops-panel__savepoints" aria-label="Recent savepoints">
          {savepoints.map((savepoint) => (
            <div className="ops-panel__savepoint" key={savepoint.savepointId}>
              <span className="ops-panel__savepoint-id" title={`${savepoint.savepointId} · checkpoint #${savepoint.checkpointId}`}>{savepoint.savepointId} · checkpoint #{savepoint.checkpointId}</span>
              <ClayButton type="button" variant="ghost" disabled={pending || !executionId} onClick={() => void restore(savepoint.savepointId)}>
                Restore
              </ClayButton>
            </div>
          ))}
          {savepoints.length === 0 && <p className="ops-panel__muted">No savepoints yet.</p>}
        </div>
        <div className="ops-panel__rescale">
          <ClayButton type="button" variant="ghost" disabled={pending || !executionId} onClick={() => void rescale()}>Rescale</ClayButton>
          <p>{RESCALE_EXPLANATION}</p>
        </div>
      </div>
      {error && <p className="ops-panel__error" role="alert">{error}</p>}
      <style>{`
        .ops-panel__controls { display: grid; gap: var(--space-3); }
        .ops-panel__savepoints { display: grid; gap: var(--space-2); }
        .ops-panel__savepoint { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); padding: var(--space-2) var(--space-3); border: 1px solid rgb(148 163 184 / 20%); border-radius: var(--radius-md); color: var(--color-muted, #aebdca); }
        .ops-panel__savepoint-id { min-width: 0; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; overflow-wrap: anywhere; font-size: 0.85rem; line-height: 1.4; }
        .ops-panel__rescale { display: grid; gap: var(--space-2); }
        .ops-panel__rescale p, .ops-panel__muted { margin: 0; color: var(--color-muted, #aebdca); font-size: 0.85rem; line-height: 1.4; }
        .ops-panel__success { margin: 0; color: #4ade80; }
        .ops-panel__error { margin: var(--space-3) 0 0; color: #f87171; }
      `}</style>
    </GlassPanel>
  )
}
