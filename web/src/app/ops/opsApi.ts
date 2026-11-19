export interface Execution {
  id: string
  state?: string
}

export interface Savepoint {
  savepointId: string
  checkpointId: number
  createdAt: string
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
    try {
      const body = await response.json() as { message?: unknown }
      if (typeof body.message === 'string') message = body.message
    } catch {
      // Use a useful fallback when the response is not the API error shape.
    }
    throw new Error(message ?? 'The control API could not complete that request. Please try again.')
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

export function restoreSavepoint(executionId: string, input: RestoreRequest): Promise<void> {
  return request(`/api/executions/${encodeURIComponent(executionId)}/restore`, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function rescaleExecution(executionId: string): Promise<void> {
  return request(`/api/executions/${encodeURIComponent(executionId)}/rescale`, { method: 'POST' })
}
