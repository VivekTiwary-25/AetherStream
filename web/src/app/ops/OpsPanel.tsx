import { useEffect, useState } from 'react'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { createSavepoint, getCurrentExecution, getSavepoints, OpsApiError, rescaleExecution, restoreSavepoint, type Savepoint } from './opsApi'

const RESTORE_CONFIRMATION = 'Restart the pipeline from this savepoint?'
const RESCALE_EXPLANATION = 'Pauses at the next checkpoint, moves partitions, resumes - a controlled pause, not live migration.'
/** Only the newest few savepoints of the current pipeline are offered (rehearsal-1 #9). */
const MAX_SAVEPOINTS = 3

function newestFirst(savepoints: Savepoint[], pipelineId: string | null) {
  return savepoints
    .filter((savepoint) => !pipelineId || !savepoint.pipelineId || savepoint.pipelineId === pipelineId)
    .sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0))
    .slice(0, MAX_SAVEPOINTS)
}

function errorText(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback
}

export function OpsPanel() {
  const [executionId, setExecutionId] = useState<string | null>(null)
  const [executionState, setExecutionState] = useState<string | null>(null)
  const [pipelineId, setPipelineId] = useState<string | null>(null)
  const [savepoints, setSavepoints] = useState<Savepoint[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  // One feedback line for every action, rendered right under the buttons so it is never below the panel's fold.
  const [notice, setNotice] = useState('')
  const [confirmRestore, setConfirmRestore] = useState<string | null>(null)

  useEffect(() => {
    let mounted = true
    void Promise.all([getCurrentExecution(), getSavepoints()]).then(([execution, recent]) => {
      if (!mounted) return
      setExecutionState(execution?.state ?? null)
      setExecutionId(execution?.id ?? null)
      setPipelineId(execution?.pipelineId ?? null)
      setSavepoints(Array.isArray(recent) ? recent : [])
    }).catch((reason: unknown) => {
      if (mounted) setError(errorText(reason, 'The control API could not load operations data.'))
    })
    return () => { mounted = false }
  }, [])

  function begin() {
    setPending(true)
    setError('')
    setNotice('')
  }

  async function takeSavepoint() {
    if (pending || !executionId) return
    begin()
    try {
      const saved = await createSavepoint(executionId)
      setSavepoints((current) => [saved, ...current.filter((item) => item.savepointId !== saved.savepointId)])
      setNotice(`Savepoint ${saved.savepointId} at checkpoint #${saved.checkpointId}`)
    } catch (reason) {
      setError(errorText(reason, 'The savepoint could not be created.'))
    } finally {
      setPending(false)
    }
  }

  async function restore(savepoint: Savepoint) {
    if (pending || !executionId) return
    setConfirmRestore(null)
    begin()
    try {
      const restored = await restoreSavepoint(executionId, { savepointId: savepoint.savepointId, idempotencyKey: crypto.randomUUID() })
      const checkpoint = restored?.restoredFromCheckpoint ?? savepoint.checkpointId
      if (restored?.id) {
        setExecutionId(restored.id)
        if (restored.state) setExecutionState(restored.state)
        if (restored.pipelineId) setPipelineId(restored.pipelineId)
        setNotice(`Restored from checkpoint #${checkpoint} → new execution ${restored.id}`)
      } else {
        setNotice(`Restored from checkpoint #${checkpoint}`)
      }
    } catch (reason) {
      setError(errorText(reason, 'The savepoint could not be restored.'))
    } finally {
      setPending(false)
    }
  }

  async function rescale() {
    if (pending || !executionId) return
    begin()
    try {
      const result = await rescaleExecution(executionId)
      setNotice(typeof result?.checkpointId === 'number' ? `Rescaled at checkpoint #${result.checkpointId}` : 'Rescale requested')
    } catch (reason) {
      // Name the refusal, so "does not need a rebalance" reads as the engine saying no (rehearsal-1 #8).
      const message = errorText(reason, 'The execution could not be rescaled.')
      setError(reason instanceof OpsApiError && reason.status === 409 ? `Refused (409 ${reason.code ?? 'CONFLICT'}): ${message}` : message)
    } finally {
      setPending(false)
    }
  }

  const executionStatus = executionState ? executionState.charAt(0) + executionState.slice(1).toLowerCase() : 'No active execution'
  const statusColor = executionState === 'RUNNING' ? '#4ade80' : executionState === 'FAILED' ? '#f87171' : executionState ? '#fbbf24' : '#94a3b8'
  const shown = newestFirst(savepoints, pipelineId)

  return (
    <GlassPanel title="Operations" status={<span style={{ color: statusColor }}>{executionStatus}</span>}>
      <div className="ops-panel__controls" aria-busy={pending}>
        <div className="ops-panel__actions">
          <ClayButton type="button" disabled={pending || !executionId} onClick={() => void takeSavepoint()}>
            Take savepoint
          </ClayButton>
          <ClayButton type="button" variant="ghost" disabled={pending || !executionId} onClick={() => void rescale()}>Rescale</ClayButton>
        </div>
        <p className="ops-panel__muted">{RESCALE_EXPLANATION}</p>
        {notice && <p className="ops-panel__success" role="status">{notice}</p>}
        {error && <p className="ops-panel__error" role="alert">{error}</p>}
        <div className="ops-panel__savepoints" aria-label="Recent savepoints">
          {shown.map((savepoint) => (
            <div className="ops-panel__savepoint" key={savepoint.savepointId}>
              <div className="ops-panel__savepoint-row">
                <span className="ops-panel__savepoint-id" title={`${savepoint.savepointId} · checkpoint #${savepoint.checkpointId}`}>{savepoint.savepointId} · checkpoint #{savepoint.checkpointId}</span>
                {confirmRestore !== savepoint.savepointId && (
                  <ClayButton type="button" variant="ghost" disabled={pending || !executionId} onClick={() => setConfirmRestore(savepoint.savepointId)}>
                    Restore
                  </ClayButton>
                )}
              </div>
              {confirmRestore === savepoint.savepointId && (
                <div className="ops-panel__confirm" aria-label="Confirm restore">
                  <p>{RESTORE_CONFIRMATION}</p>
                  <div className="ops-panel__actions">
                    <ClayButton type="button" variant="danger" disabled={pending} onClick={() => void restore(savepoint)}>Confirm restore</ClayButton>
                    <ClayButton type="button" variant="ghost" disabled={pending} onClick={() => setConfirmRestore(null)}>Cancel</ClayButton>
                  </div>
                </div>
              )}
            </div>
          ))}
          {shown.length === 0 && <p className="ops-panel__muted">No savepoints yet.</p>}
        </div>
      </div>
      <style>{`
        .ops-panel__controls { display: grid; gap: var(--space-3); }
        .ops-panel__actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
        .ops-panel__savepoints { display: grid; gap: var(--space-2); }
        .ops-panel__savepoint { display: grid; gap: var(--space-2); padding: var(--space-2) var(--space-3); border: 1px solid rgb(148 163 184 / 20%); border-radius: var(--radius-md); color: var(--color-muted, #aebdca); }
        .ops-panel__savepoint-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
        .ops-panel__savepoint-id { min-width: 0; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; overflow-wrap: anywhere; font-size: 0.85rem; line-height: 1.4; }
        .ops-panel__confirm { display: grid; gap: var(--space-2); }
        .ops-panel__confirm p { margin: 0; color: var(--color-text, #e2e8f0); font-size: 0.85rem; }
        .ops-panel__muted { margin: 0; color: var(--color-muted, #aebdca); font-size: 0.85rem; line-height: 1.4; }
        .ops-panel__success { margin: 0; color: #4ade80; overflow-wrap: anywhere; }
        .ops-panel__error { margin: 0; color: #f87171; overflow-wrap: anywhere; }
      `}</style>
    </GlassPanel>
  )
}
