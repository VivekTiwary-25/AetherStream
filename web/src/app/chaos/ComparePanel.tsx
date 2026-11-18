import { useEffect, useState } from 'react'
import './chaos.css'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { StatusPill } from '../ui/StatusPill'
import { compareWithReference, getCurrentExecution, type CompareResult, type ExecutionInfo } from './chaosApi'

const number = new Intl.NumberFormat('en-US')

export function ComparePanel() {
  const [result, setResult] = useState<CompareResult | null>(null)
  const [execution, setExecution] = useState<ExecutionInfo | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    getCurrentExecution().then((current) => {
      if (active) setExecution(current)
    }).catch(() => {
      // Comparison remains available when the current execution summary cannot be loaded.
    })
    return () => { active = false }
  }, [])

  async function compare() {
    setLoading(true)
    setError('')
    setResult(null)
    try {
      const comparison = await compareWithReference()
      setResult(comparison)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Comparison failed. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <GlassPanel title="Reference comparison" status={<StatusPill state={result ? (result.match ? 'healthy' : 'failed') : 'healthy'} />}>
      <div className="chaos-panel__actions">
        <ClayButton disabled={loading} onClick={() => void compare()}>
          {loading ? 'Comparing…' : 'Compare with reference'}
        </ClayButton>
        {loading && <div className="compare-panel__shimmer" role="status" aria-label="Comparison in progress" />}
        {error && <p className="chaos-panel__error" role="alert">{error}</p>}
        <p className="compare-panel__explanation">The reference is a single-process executor running the same operators on the same Kafka events.</p>
        {!result && execution && <p className="chaos-panel__muted">Latest completed checkpoint: #{number.format(execution.lastCompletedCheckpoint)}</p>}
        {result && (
          <section className={`compare-panel__result${result.match ? ' compare-panel__result--match' : ' compare-panel__result--mismatch'}`} aria-live="polite">
            {result.match ? (
              <h3>{number.format(result.matched)} / {number.format(result.referenceResults)} results match</h3>
            ) : (
              <>
                <h3>{number.format(result.mismatches.length)} mismatches</h3>
                <p>{number.format(result.matched)} / {number.format(result.referenceResults)} results match</p>
                <div className="compare-panel__table-wrap">
                  <table>
                    <thead><tr><th>Type</th><th>Sink</th><th>Key</th><th>Window start</th><th>Detail</th></tr></thead>
                    <tbody>{result.mismatches.map((mismatch, index) => (
                      <tr key={`${mismatch.type}-${mismatch.sinkId}-${mismatch.key}-${mismatch.windowStart}-${index}`}>
                        <td>{mismatch.type}</td><td>{mismatch.sinkId}</td><td>{mismatch.key}</td>
                        <td>{number.format(mismatch.windowStart)}</td><td>{mismatch.detail}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              </>
            )}
            <p className="chaos-panel__muted">Checked up to checkpoint #{number.format(result.checkpointId)} - replayed {number.format(result.recordsReplayed)} events in {number.format(result.durationMs)} ms</p>
          </section>
        )}
      </div>
    </GlassPanel>
  )
}
