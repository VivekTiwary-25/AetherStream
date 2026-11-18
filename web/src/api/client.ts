import type {
  Alert,
  ApiErrorBody,
  ApiExecutionRequest,
  CheckpointInfo,
  CompareResult,
  DeadLetter,
  ExecutionInfo,
  PipelineDefinition,
  PipelineSummary,
  SimulatorState,
  SimulatorStatus,
  Summary,
  ValidationResult,
  VersionInfo,
  WindowResult,
  WorkerInfo,
} from './types'

export class ApiError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
  })
  if (response.status === 204) return undefined as T
  if (!response.ok) {
    let body: Partial<ApiErrorBody> = {}
    try {
      body = (await response.json()) as Partial<ApiErrorBody>
    } catch {
      // Preserve the HTTP status as a useful error code when the response is not JSON.
    }
    throw new ApiError(body.error ?? `HTTP_${response.status}`, body.message ?? response.statusText ?? 'Request failed')
  }
  return (await response.json()) as T
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
})

export const getHealth = () => request<SimulatorStatus>('/api/health')
export const getReady = () => request<{ status: string; checks: Record<string, string> }>('/api/ready')
export const getVersion = () => request<VersionInfo>('/api/version')
export const validatePipeline = (pipeline: PipelineDefinition) => request<ValidationResult>('/api/pipelines/validate', json('POST', pipeline))
export const createPipeline = (pipeline: PipelineDefinition) => request<PipelineSummary>('/api/pipelines', json('POST', pipeline))
export const listPipelines = () => request<PipelineSummary[]>('/api/pipelines')
export const getPipeline = (id: string) => request<PipelineDefinition>(`/api/pipelines/${encodeURIComponent(id)}`)
export const getDemoPipeline = () => request<PipelineDefinition>('/api/pipelines/demo')
export const startExecution = (id: string, requestBody: ApiExecutionRequest) =>
  request<ExecutionInfo>(`/api/pipelines/${encodeURIComponent(id)}/executions`, json('POST', requestBody))
export const getCurrentExecution = () => request<ExecutionInfo | null>('/api/executions/current')
export const getExecution = (id: string) => request<ExecutionInfo>(`/api/executions/${encodeURIComponent(id)}`)
export const stopExecution = (id: string) => request<ExecutionInfo>(`/api/executions/${encodeURIComponent(id)}/stop`, json('POST'))
export const getCheckpoints = (id: string, limit = 20) =>
  request<CheckpointInfo[]>(`/api/executions/${encodeURIComponent(id)}/checkpoints?limit=${limit}`)
export const getWorkers = () => request<WorkerInfo[]>('/api/workers')
export const getWindows = (sinkId = 'speedSink', limit = 200) =>
  request<WindowResult[]>(`/api/results/windows?sinkId=${encodeURIComponent(sinkId)}&limit=${limit}`)
export const getAlerts = (limit = 50) => request<Alert[]>(`/api/results/alerts?limit=${limit}`)
export const getDeadLetters = (kind: DeadLetter['kind'], limit = 50) =>
  request<DeadLetter[]>(`/api/results/dead-letters?kind=${kind}&limit=${limit}`)
export const getSummary = () => request<Summary>('/api/results/summary')
export const sendLateEvents = (count: number, lateByMs: number) => request<{ sent: number }>('/api/chaos/late-events', json('POST', { count, lateByMs }))
export const sendDuplicates = (count: number) => request<{ sent: number }>('/api/chaos/duplicates', json('POST', { count }))
export const sendMalformed = (count: number) => request<{ sent: number }>('/api/chaos/malformed', json('POST', { count }))
export const setSlowSink = (delayMs: number) => request<{ delayMs: number }>('/api/chaos/slow-sink', json('POST', { delayMs }))
export const killWorker = (workerId: string | null = null) => request<{ workerId: string }>('/api/chaos/kill-worker', json('POST', { workerId }))
export const getSimulatorState = () => request<SimulatorState>('/api/simulator/state')
export const addSimulatorTruck = (x: number, y: number) => request<{ truckId: string }>('/api/simulator/trucks', json('POST', { x, y }))
export const compareWithReference = () => request<CompareResult>('/api/compare', json('POST', {}))
