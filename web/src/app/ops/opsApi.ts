export interface Execution {
  id: string
  state?: string
  pipelineId?: string
  generation?: number
  restoredFromCheckpoint?: number | null
}

/** An API error with the HTTP status and contract 04 error code, so the UI can say e.g. "Refused (409 CONFLICT)". */
export class OpsApiError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(message: string, status: number, code: string | null) {
    super(message)
    this.name = 'OpsApiError'
    this.status = status
    this.code = code
  }
}

export interface Savepoint {
  savepointId: string
  checkpointId: number
  createdAt: string
  /** Present on GET /api/savepoints rows (contract 04), absent on the create response. */
  executionId?: string
  pipelineId?: string
}

export interface RescaleResponse {
  checkpointId?: number
}

export type CreateSavepointResponse = Savepoint

export interface RestoreRequest {
  savepointId: string
  idempotencyKey: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('The control API is not reachable. Check that it is running and try again.')
  }

  if (!response.ok) {
    let message: string | undefined
    let code: string | null = null
    try {
      const body = await response.json() as { message?: unknown; error?: unknown }
      if (typeof body.message === 'string') message = body.message
      if (typeof body.error === 'string') code = body.error
    } catch {
      // Use a useful fallback when the response is not the API error shape.
    }
    throw new OpsApiError(message ?? 'The control API could not complete that request. Please try again.', response.status, code)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function getCurrentExecution(): Promise<Execution | null> {
  return request<Execution | null>('/api/executions/current')
}

export function getSavepoints(): Promise<Savepoint[]> {
  return request('/api/savepoints')
}

export function createSavepoint(executionId: string): Promise<CreateSavepointResponse> {
  return request(`/api/executions/${encodeURIComponent(executionId)}/savepoints`, { method: 'POST' })
}

export function restoreSavepoint(executionId: string, input: RestoreRequest): Promise<Execution | undefined> {
  return request(`/api/executions/${encodeURIComponent(executionId)}/restore`, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function rescaleExecution(executionId: string): Promise<RescaleResponse | undefined> {
  return request(`/api/executions/${encodeURIComponent(executionId)}/rescale`, { method: 'POST' })
}
