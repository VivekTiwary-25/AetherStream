import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react'
import { getAlerts, getDeadLetters, getSummary, getWindows, getWorkers } from '../../api/client'
import type { Alert, DeadLetter, ExecutionEvent, ExecutionInfo, Flow, SnapshotMessage, Summary, WebSocketMessage, WindowResult, WorkerInfo } from '../../api/types'
import { useAetherMessages } from '../../api/ws'

export interface RecoveryInfo {
  /** Client time the recovery was seen to finish. */
  at: number
  /** Server-measured duration (contract 04 RECOVERED durationMs), or null when the server sent none. */
  durationMs: number | null
  checkpointId: number | null
  fromGeneration: number | null
  toGeneration: number | null
}

export interface DashboardState {
  execution: ExecutionInfo | null
  lastExecutionEvent: ExecutionEvent | null
  workers: WorkerInfo[]
  windows: WindowResult[]
  alerts: Alert[]
  deadLetters: DeadLetter[]
  summary: Summary | null
  flow: Flow | null
  simTime: number | null
  /** Increments every time the execution enters recovery, so the UI can show it even if RECOVERED follows at once. */
  recoveries: number
  recoveryCheckpoint: number | null
  /** Generation before the last REBALANCED event (contract 04: partitions moved without a WORKER_DEAD). */
  rebalancedFromGeneration: number | null
  socketStatus: 'connecting' | 'connected' | 'disconnected'
  /** The recovery in progress, first seen at `since` (client clock), entered by a RECOVERING event or execution state. */
  recovering: { since: number; fromGeneration: number | null; checkpointId: number | null } | null
  /**
   * The last finished recovery (rehearsal-1 #3). Set by a RECOVERED event, or by the execution leaving RECOVERING for
   * RUNNING: the server only labels RECOVERING/RECOVERED when it saw the worker die, and a fast worker restart can hide
   * the death, so the state transition is the fallback. Survives later CHECKPOINT_COMPLETED events.
   */
  lastRecovery: RecoveryInfo | null
  /** Workers killed from the Chaos panel: workerId -> client time of the kill (rehearsal-1 #12). */
  killedWorkers: Record<string, number>
}

type Hydration = Partial<Pick<DashboardState, 'workers' | 'windows' | 'alerts' | 'deadLetters' | 'summary'>>

export type Action =
  | { type: 'message'; message: WebSocketMessage; at?: number }
  | { type: 'socket'; status: DashboardState['socketStatus'] }
  | { type: 'hydrate'; data: Hydration }
  | { type: 'workerKilled'; workerId: string; at?: number }

export const initialDashboardState: DashboardState = {
  execution: null,
  lastExecutionEvent: null,
  workers: [],
  windows: [],
  alerts: [],
  deadLetters: [],
  summary: null,
  flow: null,
  simTime: null,
  recoveries: 0,
  recoveryCheckpoint: null,
  rebalancedFromGeneration: null,
  socketStatus: 'connecting',
  recovering: null,
  lastRecovery: null,
  killedWorkers: {},
}

const finite = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null

/** Follows recovery across execution updates (event name or execution state), so a finished recovery is never missed. */
function trackRecovery(previous: DashboardState, next: DashboardState, event: ExecutionEvent | null, at: number): DashboardState {
  const state = next.execution?.state
  if (state === 'STOPPED' || state === 'FAILED') return next.recovering ? { ...next, recovering: null } : next
  const eventName = event?.event as string | undefined
  if (eventName === 'RECOVERING' || state === 'RECOVERING') {
    if (previous.recovering) return next
    const checkpointId = finite(event?.checkpointId) ?? finite(next.execution?.restoredFromCheckpoint) ?? finite(previous.execution?.lastCompletedCheckpoint)
    return { ...next, recovering: { since: at, fromGeneration: finite(previous.execution?.generation), checkpointId } }
  }
  const durationMs = eventName === 'RECOVERED' ? finite(event?.durationMs) : null
  const finished = eventName === 'RECOVERED' || (state === 'RUNNING' && (previous.recovering !== null || previous.execution?.state === 'RECOVERING'))
  if (!finished) return next
  const last = previous.lastRecovery
  // A RECOVERED event just after the state transition was already counted: only add the server-measured duration.
  if (!previous.recovering && last && at - last.at < 10_000) {
    return durationMs !== null && last.durationMs === null ? { ...next, lastRecovery: { ...last, durationMs } } : next
  }
  return {
    ...next,
    recovering: null,
    lastRecovery: {
      at,
      durationMs,
      // Only RECOVERED's checkpointId is the restored one; on other events (e.g. CHECKPOINT_COMPLETED) it is a new checkpoint.
      checkpointId: (eventName === 'RECOVERED' ? finite(event?.checkpointId) : null) ?? finite(next.execution?.restoredFromCheckpoint) ?? previous.recovering?.checkpointId ?? null,
      fromGeneration: previous.recovering?.fromGeneration ?? null,
      toGeneration: finite(next.execution?.generation),
    },
  }
}

export function dashboardReducer(state: DashboardState, action: Action): DashboardState {
  if (action.type === 'socket') return { ...state, socketStatus: action.status }
  if (action.type === 'workerKilled') return { ...state, killedWorkers: { ...state.killedWorkers, [action.workerId]: action.at ?? Date.now() } }
  if (action.type === 'hydrate') {
    const data = action.data
    const windows = Array.isArray(data.windows) ? data.windows.filter((item) => item != null) : null
    const alerts = Array.isArray(data.alerts) ? data.alerts.filter((item) => item != null) : null
    const deadLetters = Array.isArray(data.deadLetters) ? data.deadLetters.filter((item) => item != null) : null
    return {
      ...state,
      workers: Array.isArray(data.workers) ? data.workers : state.workers,
      summary: data.summary ?? state.summary,
      windows: windows ? [...state.windows, ...windows].slice(0, 200) : state.windows,
      alerts: alerts ? [...state.alerts, ...alerts].slice(0, 200) : state.alerts,
      deadLetters: deadLetters ? [...state.deadLetters, ...deadLetters].slice(0, 200) : state.deadLetters,
    }
  }
  const message = action.message
  if (!message || message.data == null) return state
  const at = action.at ?? Date.now()
  const { type, data } = message
  switch (type) {
    case 'snapshot': {
      if (typeof data !== 'object') return state
      const snapshot = data as Partial<SnapshotMessage>
      return trackRecovery(state, {
        ...state,
        execution: snapshot.execution ?? null,
        workers: Array.isArray(snapshot.workers) ? snapshot.workers.filter((worker) => worker != null) : [],
        summary: snapshot.summary ?? null,
        simTime: typeof snapshot.simulator?.simTime === 'number' && Number.isFinite(snapshot.simulator.simTime) ? snapshot.simulator.simTime : state.simTime,
      }, null, at)
    }
    case 'trucks': {
      if (typeof data !== 'object' || !('simTime' in data)) return state
      const simTime = data.simTime
      if (typeof simTime !== 'number' || !Number.isFinite(simTime) || simTime === state.simTime) return state
      return { ...state, simTime }
    }
    case 'flow':
      return typeof data === 'object' ? { ...state, flow: data as Flow } : state
    case 'windows':
      return Array.isArray(data) ? { ...state, windows: [...data.filter((item) => item != null), ...state.windows].slice(0, 200) } : state
    case 'alerts':
      return Array.isArray(data) ? { ...state, alerts: [...data.filter((item) => item != null), ...state.alerts].slice(0, 200) } : state
    case 'deadLetters':
      return Array.isArray(data) ? { ...state, deadLetters: [...data.filter((item) => item != null), ...state.deadLetters].slice(0, 200) } : state
    case 'workers':
      return Array.isArray(data) ? { ...state, workers: data.filter((worker) => worker != null) as WorkerInfo[] } : state
    case 'execution': {
      if (typeof data !== 'object') return state
      const event = data as ExecutionEvent
      if (isRebalanced(event)) {
        // A rebalance (e.g. a killed worker rejoining) is not a recovery: never count it as one...
        const extra = event as { previousGeneration?: unknown; oldGeneration?: unknown; fromGeneration?: unknown }
        const reported = [extra.previousGeneration, extra.oldGeneration, extra.fromGeneration].find((value) => typeof value === 'number' && Number.isFinite(value)) as number | undefined
        const before = state.execution?.generation
        const from = reported ?? (typeof before === 'number' && before !== event.generation ? before : null)
        // ...unless it arrives in state RECOVERING: the coordinator only enters RECOVERING after a worker death, so this
        // is a recovery whose death the server did not observe (the worker restarted too fast). Count it like RECOVERING.
        const recovery = event.state === 'RECOVERING' && state.execution?.state !== 'RECOVERING'
        const checkpoint = finite(event.restoredFromCheckpoint) ?? finite(event.lastCompletedCheckpoint)
        return trackRecovery(state, {
          ...state,
          execution: event,
          lastExecutionEvent: event,
          rebalancedFromGeneration: from,
          recoveries: recovery ? state.recoveries + 1 : state.recoveries,
          recoveryCheckpoint: recovery && checkpoint !== null ? checkpoint : state.recoveryCheckpoint,
        }, event, at)
      }
      const entersRecovery = event.event === 'RECOVERING' || (event.state === 'RECOVERING' && state.execution?.state !== 'RECOVERING')
      if (!entersRecovery) return trackRecovery(state, { ...state, execution: event, lastExecutionEvent: event }, event, at)
      const checkpoint = event.checkpointId ?? event.restoredFromCheckpoint ?? event.lastCompletedCheckpoint
      return trackRecovery(state, {
        ...state,
        execution: event,
        lastExecutionEvent: event,
        recoveries: state.recoveries + 1,
        recoveryCheckpoint: typeof checkpoint === 'number' && Number.isFinite(checkpoint) ? checkpoint : state.recoveryCheckpoint,
      }, event, at)
    }
    default:
      return state
  }
}

/** Contract 04 REBALANCED (additive event; api/types.ts does not list it yet). */
export function isRebalanced(event: { event?: unknown } | null | undefined): boolean {
  return (event?.event as string | undefined) === 'REBALANCED'
}

export const DashboardContext = createContext<DashboardState>(initialDashboardState)
/** Lets panels record UI actions (e.g. a Chaos kill) in the dashboard. A no-op without a provider. */
export const DashboardDispatchContext = createContext<(action: Action) => void>(() => undefined)

export function DashboardProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(dashboardReducer, initialDashboardState)
  // Every frame is dispatched in arrival order; React applies all queued actions through the reducer, so frames that
  // land in the same batch (e.g. RECOVERING immediately followed by RECOVERED) are never dropped.
  const status = useAetherMessages((message) => dispatch({ type: 'message', message, at: Date.now() }))
  useEffect(() => dispatch({ type: 'socket', status }), [status])
  useEffect(() => {
    let active = true
    getWorkers().then((workers) => { if (active) dispatch({ type: 'hydrate', data: { workers } }) }).catch(() => undefined)
    getWindows('speedSink').then((rows) => { if (active) dispatch({ type: 'hydrate', data: { windows: rows } }) }).catch(() => undefined)
    getWindows('delSink').then((rows) => { if (active) dispatch({ type: 'hydrate', data: { windows: rows } }) }).catch(() => undefined)
    getAlerts().then((alerts) => { if (active) dispatch({ type: 'hydrate', data: { alerts } }) }).catch(() => undefined)
    getDeadLetters('REJECTED').then((rows) => { if (active) dispatch({ type: 'hydrate', data: { deadLetters: rows } }) }).catch(() => undefined)
    getDeadLetters('LATE').then((rows) => { if (active) dispatch({ type: 'hydrate', data: { deadLetters: rows } }) }).catch(() => undefined)
    getSummary().then((summary) => { if (active) dispatch({ type: 'hydrate', data: { summary } }) }).catch(() => undefined)
    return () => { active = false }
  }, [])
  const value = useMemo(() => state, [state])
  return <DashboardDispatchContext.Provider value={dispatch}><DashboardContext.Provider value={value}>{children}</DashboardContext.Provider></DashboardDispatchContext.Provider>
}

export function useDashboard() {
  return useContext(DashboardContext)
}

/** Records a worker killed from the UI, so the Workers panel shows the crash before the cluster state catches up. */
export function useMarkWorkerKilled() {
  const dispatch = useContext(DashboardDispatchContext)
  return useCallback((workerId: string) => dispatch({ type: 'workerKilled', workerId, at: Date.now() }), [dispatch])
}
