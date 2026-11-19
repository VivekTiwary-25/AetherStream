import { useEffect, useReducer, useRef, useState } from 'react'
import { ClayButton } from '../shell/ClayButton'
import { describeError, getResults, stopRun, type RunResult } from './api'
import { formatCell, formatTime, initialRunView, resultColumns, runReducer } from './results'

const POLL_MS = 1000
const SHOWN_ROWS = 100
const STATE_LABEL = { RUNNING: 'Running', STOPPED: 'Stopped', FAILED: 'Failed' } as const

/** Polls GET .../results?after=seq every second while mounted (the window is open) and the run is RUNNING. */
export function ResultsView({ runId }: { runId: string }) {
  const [view, dispatch] = useReducer(runReducer, runId, initialRunView)
  const [stopping, setStopping] = useState(false)
  const [stopError, setStopError] = useState('')
  const finished = view.state === 'STOPPED' || view.state === 'FAILED'

  const afterRef = useRef(view.after)
  useEffect(() => { afterRef.current = view.after }, [view.after])

  useEffect(() => {
    if (finished) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const controller = new AbortController()
    const poll = async () => {
      try {
        const page = await getResults(runId, afterRef.current, controller.signal)
        if (cancelled) return
        dispatch({ type: 'page', page })
        if (page.state !== 'RUNNING') return
      } catch (error) {
        if (cancelled) return
        const status = (error as { status?: number }).status
        dispatch({ type: 'pollError', message: status === 404 ? 'Run not found. The pipeline service may have restarted.' : describeError(error).message })
      }
      // Next poll one interval after the previous one finished, so requests never overlap.
      timer = setTimeout(() => void poll(), POLL_MS)
    }
    void poll()
    return () => { cancelled = true; controller.abort(); if (timer) clearTimeout(timer) }
  }, [runId, finished])

  const stop = async () => {
    setStopping(true)
    setStopError('')
    try { await stopRun(runId) } catch (error) { setStopError(describeError(error).message) } finally { setStopping(false) }
  }

  const latest = view.results.slice(-SHOWN_ROWS).reverse()
  const columns = resultColumns(latest)
  const stateClass = view.state ? `gl-run-state--${view.state.toLowerCase()}` : 'gl-run-state--pending'
  return <div className="gl-results" data-testid="pipeline-results">
    <div className="gl-results__bar">
      <p className={`gl-run-state ${stateClass}`} aria-live="polite"><span className="gl-run-state__dot" aria-hidden="true" />{view.state ? STATE_LABEL[view.state] : 'Connecting...'}</p>
      <p className="gl-results__count">Events in: <strong>{view.eventsIn ?? '-'}</strong></p>
      <p className="gl-results__count">Results: <strong>{view.results.length}</strong></p>
      <ClayButton onClick={() => void stop()} disabled={finished || stopping || !view.state}>{stopping ? 'Stopping...' : 'Stop'}</ClayButton>
    </div>
    {view.state === 'FAILED' && <p className="gl-alert gl-alert--error" role="alert">Run failed{view.error ? `: ${view.error}` : ''}</p>}
    {view.pollError && <p className="gl-alert gl-alert--error" role="alert">{view.pollError}</p>}
    {stopError && <p className="gl-alert gl-alert--error" role="alert">Could not stop: {stopError}</p>}
    {latest.length === 0
      ? <p className="gl-muted">{view.state === 'RUNNING' ? 'Waiting for matching events. No results yet.' : view.state ? 'No results.' : 'Waiting for the first update...'}</p>
      : <div className="gl-results__table-wrap"><table className="gl-results__table">
        <caption className="gl-sr-only">Latest {latest.length} pipeline results, newest first</caption>
        <thead><tr><th scope="col">Time</th><th scope="col">Kind</th><th scope="col">Output</th>{columns.map((column) => <th scope="col" key={column}>{column}</th>)}</tr></thead>
        <tbody>{latest.map((result: RunResult) => <tr key={result.seq} className={result.kind === 'alert' ? 'gl-results__alert' : undefined}>
          <td>{formatTime(result.ts)}</td>
          <td>{result.kind === 'alert' ? <strong>Alert</strong> : result.kind === 'average' ? 'Average' : 'Row'}</td>
          <td>{result.output}</td>
          {columns.map((column) => <td key={column}>{formatCell(result.data?.[column])}</td>)}
        </tr>)}</tbody>
      </table></div>}
  </div>
}
