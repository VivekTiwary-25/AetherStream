import { createContext, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react'
import { getAlerts, getDeadLetters, getSummary, getWindows, getWorkers } from '../../api/client'
import type { Alert, DeadLetter, ExecutionEvent, ExecutionInfo, Flow, SnapshotMessage, Summary, WebSocketMessage, WindowResult, WorkerInfo } from '../../api/types'
import { useAetherMessages } from '../../api/ws'

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
}

type Hydration = Partial<Pick<DashboardState, 'workers' | 'windows' | 'alerts' | 'deadLetters' | 'summary'>>

type Action =
  | { type: 'message'; message: WebSocketMessage }
  | { type: 'socket'; status: DashboardState['socketStatus'] }
  | { type: 'hydrate'; data: Hydration }

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
}

export function dashboardReducer(state: DashboardState, action: Action): DashboardState {
  if (action.type === 'socket') return { ...state, socketStatus: action.status }
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
  const { type, data } = message
  switch (type) {
    case 'snapshot': {
      if (typeof data !== 'object') return state
      const snapshot = data as Partial<SnapshotMessage>
      return {
        ...state,
        execution: snapshot.execution ?? null,
        workers: Array.isArray(snapshot.workers) ? snapshot.workers.filter((worker) => worker != null) : [],
        summary: snapshot.summary ?? null,
        simTime: typeof snapshot.simulator?.simTime === 'number' && Number.isFinite(snapshot.simulator.simTime) ? snapshot.simulator.simTime : state.simTime,
      }
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
        // A rebalance (e.g. a killed worker rejoining) is not a recovery: never count it as one.
        const extra = event as { previousGeneration?: unknown; oldGeneration?: unknown; fromGeneration?: unknown }
        const reported = [extra.previousGeneration, extra.oldGeneration, extra.fromGeneration].find((value) => typeof value === 'number' && Number.isFinite(value)) as number | undefined
        const before = state.execution?.generation
        const from = reported ?? (typeof before === 'number' && before !== event.generation ? before : null)
        return { ...state, execution: event, lastExecutionEvent: event, rebalancedFromGeneration: from }
      }
      const entersRecovery = event.event === 'RECOVERING' || (event.state === 'RECOVERING' && state.execution?.state !== 'RECOVERING')
      if (!entersRecovery) return { ...state, execution: event, lastExecutionEvent: event }
      const checkpoint = event.checkpointId ?? event.restoredFromCheckpoint ?? event.lastCompletedCheckpoint
      return {
        ...state,
        execution: event,
        lastExecutionEvent: event,
        recoveries: state.recoveries + 1,
        recoveryCheckpoint: typeof checkpoint === 'number' && Number.isFinite(checkpoint) ? checkpoint : state.recoveryCheckpoint,
      }
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

export function DashboardProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(dashboardReducer, initialDashboardState)
  // Every frame is dispatched in arrival order; React applies all queued actions through the reducer, so frames that
  // land in the same batch (e.g. RECOVERING immediately followed by RECOVERED) are never dropped.
  const status = useAetherMessages((message) => dispatch({ type: 'message', message }))
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
  return <DashboardContext.Provider value={value}>{children}</DashboardContext.Provider>
}

export function useDashboard() {
  return useContext(DashboardContext)
}
