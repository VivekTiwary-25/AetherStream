import { createContext, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react'
import { getAlerts, getDeadLetters, getSummary, getWindows, getWorkers } from '../../api/client'
import type { Alert, DeadLetter, ExecutionEvent, ExecutionInfo, Flow, SnapshotMessage, Summary, WebSocketMessage, WindowResult, WorkerInfo } from '../../api/types'
import { useAetherSocket } from '../../api/ws'

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
  socketStatus: 'connecting',
}

export function dashboardReducer(state: DashboardState, action: Action): DashboardState {
  if (action.type === 'socket') return { ...state, socketStatus: action.status }
  if (action.type === 'hydrate') {
    const data = action.data
    return {
      ...state,
      ...data,
      windows: data.windows ? [...state.windows, ...data.windows].slice(0, 200) : state.windows,
      alerts: data.alerts ? [...state.alerts, ...data.alerts].slice(0, 200) : state.alerts,
      deadLetters: data.deadLetters ? [...state.deadLetters, ...data.deadLetters].slice(0, 200) : state.deadLetters,
    }
  }
  const { type, data } = action.message
  switch (type) {
    case 'snapshot': {
      const snapshot = data as SnapshotMessage
      return { ...state, execution: snapshot.execution, workers: snapshot.workers, summary: snapshot.summary, simTime: snapshot.simulator?.simTime ?? state.simTime }
    }
    case 'trucks':
      return { ...state, simTime: (data as { simTime: number }).simTime }
    case 'flow':
      return { ...state, flow: data as Flow }
    case 'windows':
      return { ...state, windows: [...(data as WindowResult[]), ...state.windows].slice(0, 200) }
    case 'alerts':
      return { ...state, alerts: [...(data as Alert[]), ...state.alerts].slice(0, 200) }
    case 'deadLetters':
      return { ...state, deadLetters: [...(data as DeadLetter[]), ...state.deadLetters].slice(0, 200) }
    case 'workers':
      return { ...state, workers: data as WorkerInfo[] }
    case 'execution': {
      const event = data as ExecutionEvent
      return { ...state, execution: event, lastExecutionEvent: event }
    }
  }
}

export const DashboardContext = createContext<DashboardState>(initialDashboardState)

export function DashboardProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(dashboardReducer, initialDashboardState)
  const { status, lastMessage } = useAetherSocket()
  useEffect(() => dispatch({ type: 'socket', status }), [status])
  useEffect(() => {
    if (lastMessage) dispatch({ type: 'message', message: lastMessage })
  }, [lastMessage])
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
