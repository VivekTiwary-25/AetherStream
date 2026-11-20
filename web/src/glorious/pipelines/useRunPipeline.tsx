import { useCallback, useState } from 'react'
import { useWindows } from '../shell/windows'
import { describeError, startRun, type ErrorView } from './api'
import type { Pipeline } from './model'
import { ResultsView } from './ResultsView'

export const RESULTS_WINDOW_ID = 'pipelines.results'

/** POST /runs, then show the "Pipeline results" GlassWindow for the new run. */
export function useRunPipeline() {
  const windows = useWindows()
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<ErrorView | null>(null)

  const run = async (pipeline: Pipeline) => {
    setRunning(true)
    setError(null)
    try {
      const { runId } = await startRun(pipeline)
      // key={runId} remounts the view so polling restarts cleanly for each new run.
      const render = () => <ResultsView key={runId} runId={runId} />
      const title = `Pipeline results - ${pipeline.name}`
      // Update before opening: an existing window may have closed while POST /runs was pending,
      // or still be in its closing animation. Reopening must show this run, not the old one.
      windows.update(RESULTS_WINDOW_ID, { title, render })
      windows.open(RESULTS_WINDOW_ID, {
        title, strong: true, render,
        initial: { x: Math.max(16, window.innerWidth - 740), y: 100, w: Math.min(700, window.innerWidth - 32), h: Math.min(520, window.innerHeight - 120) },
      })
      return true
    } catch (caught) {
      setError(describeError(caught))
      return false
    } finally {
      setRunning(false)
    }
  }
  const clearError = useCallback(() => setError(null), [])
  return { run, running, error, clearError }
}
