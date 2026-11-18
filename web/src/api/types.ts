export type ExecutionState = 'CREATED' | 'RUNNING' | 'RECOVERING' | 'STOPPED' | 'FAILED'
export type DeadLetterKind = 'LATE' | 'REJECTED'
export type WorkerState = 'IDLE' | 'RESTORING' | 'RUNNING'

export interface PipelineDefinition {
  id: string
  version: number
  name: string
  nodes: Array<Record<string, unknown>>
  edges: Array<{ from: string; to: string }>
}

export interface PipelineSummary {
  id: string
  version: number
  name: string
  createdAt: string
}

export interface ExecutionInfo {
  id: string
  pipelineId: string
  pipelineVersion: number
  state: ExecutionState
  generation: number
  restoredFromCheckpoint: number | null
  lastCompletedCheckpoint: number
  createdAt: string
}

export interface CheckpointInfo {
  checkpointId: number
  state: string
  generation: number
  durationMs: number
  completedAt: string | null
  sourceOffsets: Record<string, number>
}

export interface QueueStatus {
  size: number
  capacity: number
}

export interface WorkerInfo {
  workerId: string
  alive: boolean
  state: WorkerState
  partitions: number[]
  sourceQueue: QueueStatus
  sinkQueue: QueueStatus
  backpressured: boolean
  recordsPerSec: number
  lastHeartbeatMs: number
}

export interface ClusterState {
  execution: ExecutionInfo | null
  workers: WorkerInfo[]
  sinkDelayMs: number
}

export interface WindowResult {
  sinkId: string
  key: string
  windowStart: number
  windowEnd: number
  values: Record<string, number>
  eventCount: number
}

export interface Alert {
  alertId: string
  key: string
  ruleId: string
  message: string
  eventTime: number
}

export interface DeadLetter {
  eventId: string
  kind: DeadLetterKind
  reason: string
  partition: number
  offset: number
  eventTime: number
}

export interface Summary {
  executionId: string | null
  windowsWritten: number
  alerts: number
  rejected: number
  late: number
  deliveriesLastMinute: number
  avgSpeedLastMinute: number
  eventsPerSec: number
}

export interface SimulatorTruck {
  truckId: string
  x: number
  y: number
  speedKmh: number
  heading: number
  status: string
}

export interface SimulatorState {
  running: boolean
  timeScale: number
  simTime: number
  trucks: SimulatorTruck[]
  stops: Array<{ stopId: string; x: number; y: number }>
  depot: { x: number; y: number }
  emitted: number
}

export interface Flow {
  eventsPerSec: number
  byPartition: Record<string, number>
  rejectedPerSec: number
  latePerSec: number
}

export interface WebSocketMessage<T = unknown> {
  type: 'snapshot' | 'trucks' | 'flow' | 'windows' | 'alerts' | 'deadLetters' | 'workers' | 'execution'
  ts: number
  data: T
}

export interface ExecutionEvent extends ExecutionInfo {
  event: 'STARTED' | 'CHECKPOINT_COMPLETED' | 'WORKER_DEAD' | 'RECOVERING' | 'RECOVERED' | 'STOPPED'
  checkpointId?: number
  workerId?: string
}

export interface SnapshotMessage {
  execution: ExecutionInfo | null
  workers: WorkerInfo[]
  summary: Summary
  simulator: SimulatorState
}

export interface TrucksMessage {
  simTime: number
  trucks: SimulatorTruck[]
}

export interface ValidationResult {
  valid: boolean
  errors: Array<{ nodeId: string | null; code: string; message: string }>
  plan: Record<string, unknown> | null
}

export interface ApiErrorBody {
  error: string
  message: string
}

export interface CompareResult {
  checkpointId: number
  referenceResults: number
  engineResults: number
  matched: number
  mismatches: Array<{ type: 'MISSING' | 'DIFFERENT' | 'EXTRA'; sinkId: string; key: string; windowStart: number; detail: string }>
  recordsReplayed: number
  durationMs: number
  match: boolean
}

export interface SimulatorStatus {
  status: string
}

export interface VersionInfo {
  commit: string
  builtAt: string
}

export interface ApiExecutionRequest {
  idempotencyKey: string
}
