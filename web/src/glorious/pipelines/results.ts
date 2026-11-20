import type { ResultsPage, RunResult, RunState } from './api'

export const MAX_RESULTS = 500

export interface RunView {
  runId: string
  /** Highest seq received; sent as ?after= on the next poll. */
  after: number
  state?: RunState
  error?: string
  startedAt?: number | string
  eventsIn?: number
  results: RunResult[]
  /** Transport problem on the latest poll (the run itself may still be fine). */
  pollError?: string
}

export type RunAction =
  | { type: 'page'; page: ResultsPage }
  | { type: 'pollError'; message: string }
  | { type: 'reset'; runId: string }

export const initialRunView = (runId: string): RunView => ({ runId, after: 0, results: [] })

export function runReducer(view: RunView, action: RunAction): RunView {
  switch (action.type) {
    case 'reset': return initialRunView(action.runId)
    case 'pollError': return { ...view, pollError: action.message }
    case 'page': {
      const { page } = action
      if (page.runId && page.runId !== view.runId) return view
      // Only accept results newer than what we already have; a repeated or out-of-order page adds nothing twice.
      const fresh = (page.results ?? []).filter((result) => typeof result.seq === 'number' && result.seq > view.after).sort((a, b) => a.seq - b.seq)
      const after = fresh.length ? fresh[fresh.length - 1]!.seq : view.after
      return {
        ...view, after, state: page.state, error: page.error, startedAt: page.startedAt, eventsIn: page.eventsIn,
        results: fresh.length ? [...view.results, ...fresh].slice(-MAX_RESULTS) : view.results, pollError: undefined,
      }
    }
  }
}

/** Column names for a set of results: data keys in first-seen order. */
export function resultColumns(results: RunResult[]): string[] {
  const columns: string[] = []
  for (const result of results) for (const key of Object.keys(result.data ?? {})) if (!columns.includes(key)) columns.push(key)
  return columns
}

export function formatCell(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(2)
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

export function formatTime(ts: number | string | undefined): string {
  if (ts === undefined) return ''
  const date = new Date(ts)
  return Number.isNaN(date.getTime()) ? String(ts) : date.toLocaleTimeString()
}
