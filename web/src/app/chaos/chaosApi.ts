export interface ChaosResponse {
  sent: number
}

export interface SlowSinkResponse {
  delayMs: number
}

export interface KillWorkerResponse {
  workerId: string
}

export interface Worker {
  workerId: string
  alive: boolean
  state: 'IDLE' | 'RESTORING' | 'RUNNING'
  partitions: number[]
  sourceQueue: { size: number; capacity: number }
  sinkQueue: { size: number; capacity: number }
  backpressured: boolean
  recordsPerSec: number
  lastHeartbeatMs: number
}

export interface ExecutionInfo {
  id: string
  pipelineId: string
  pipelineVersion: number
  state: 'CREATED' | 'RUNNING' | 'RECOVERING' | 'STOPPED' | 'FAILED'
  generation: number
  restoredFromCheckpoint: number | null
  lastCompletedCheckpoint: number
  createdAt: string
}

export interface CompareMismatch {
  type: 'MISSING' | 'DIFFERENT' | 'EXTRA'
  sinkId: string
  key: string
  windowStart: number
  detail: string
}

export interface CompareResult {
  checkpointId: number
  referenceResults: number
  engineResults: number
  matched: number
  mismatches: CompareMismatch[]
  recordsReplayed: number
  durationMs: number
  match: boolean
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('Could not reach the service. Check that the demo is running and try again.')
  }

  if (!response.ok) {
    let message = `The request failed (${response.status}). Please try again.`
    try {
      const body: unknown = await response.json()
      if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') message = body.message
      else if (body && typeof body === 'object' && 'error' in body && typeof body.error === 'string') message = body.error
    } catch {
      // Keep the status-based message when the response is not JSON.
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

function post<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

export function sendLateEvents(): Promise<ChaosResponse> {
  return post('/api/chaos/late-events', { count: 20, lateByMs: 180_000 })
}

export function sendDuplicates(): Promise<ChaosResponse> {
  return post('/api/chaos/duplicates', { count: 20 })
}

export function sendMalformed(): Promise<ChaosResponse> {
  return post('/api/chaos/malformed', { count: 5 })
}

export function setSlowSink(delayMs: 0 | 50 | 200): Promise<SlowSinkResponse> {
  return post('/api/chaos/slow-sink', { delayMs })
}

export function killWorker(workerId: string | null): Promise<KillWorkerResponse> {
  return post('/api/chaos/kill-worker', { workerId })
}

export function getWorkers(): Promise<Worker[]> {
  return request('/api/workers')
}

export async function getCurrentExecution(): Promise<ExecutionInfo | null> {
  const response = await fetch('/api/executions/current')
  if (response.status === 204) return null
  if (!response.ok) throw new Error(`The request failed (${response.status}). Please try again.`)
  return response.json() as Promise<ExecutionInfo>
}

export function compareWithReference(): Promise<CompareResult> {
  return post('/api/compare', {})
}
