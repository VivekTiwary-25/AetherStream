export type TruckStatus = 'DRIVING' | 'UNLOADING' | 'IDLE' | 'STOPPED'

export interface CityPoint {
  x: number
  y: number
}

export interface CityTruck extends CityPoint {
  truckId: string
  speedKmh: number
  heading: number
  status: TruckStatus
  routeId?: string
}

export interface CityStop extends CityPoint {
  stopId: string
}

export interface SimulatorState {
  running: boolean
  timeScale: number
  simTime: number
  trucks: CityTruck[]
  stops: CityStop[]
  depot: CityPoint
  emitted: number
}

export interface CityWorker {
  workerId: string
  alive: boolean
  state: 'IDLE' | 'RESTORING' | 'RUNNING'
  partitions: number[]
  backpressured: boolean
  recordsPerSec: number
  lastHeartbeatMs: number
}

export interface CityExecution {
  id: string
  pipelineId: string
  pipelineVersion: number
  state: 'CREATED' | 'RUNNING' | 'RECOVERING' | 'STOPPED' | 'FAILED'
  generation: number
  restoredFromCheckpoint: number | null
  lastCompletedCheckpoint: number
  createdAt: string
}

export interface CityEnvelope<T> {
  type: string
  ts: number
  data: T
}

export interface CitySnapshot {
  execution: CityExecution | null
  workers: CityWorker[]
  summary: Record<string, unknown>
  simulator: SimulatorState
}

export interface FlowData {
  eventsPerSec: number
  byPartition: Record<string, number>
  rejectedPerSec: number
  latePerSec: number
}

export interface TruckUpdate {
  simTime: number
  trucks: CityTruck[]
}

/** `execution` WebSocket message: ExecutionInfo plus what happened (contract 04). */
export interface ExecutionMessage extends CityExecution {
  event?: 'STARTED' | 'CHECKPOINT_COMPLETED' | 'WORKER_DEAD' | 'RECOVERING' | 'RECOVERED' | 'STOPPED'
  checkpointId?: number
  workerId?: string
}
