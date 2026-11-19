import type { PipelineDefinition, ValidationResult } from './types'

export interface PipelineExample { id: string; name: string; description: string }

class RequestError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    throw new RequestError(body.message ?? `Request failed (${response.status})`, response.status)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export const getDemo = () => request<PipelineDefinition>('/api/pipelines/demo')
export const getExamples = () => request<PipelineExample[]>('/api/pipelines/examples')
export const getExample = (id: string) => request<PipelineDefinition>('/api/pipelines/examples/' + encodeURIComponent(id))
export const isNotFound = (error: unknown) => error instanceof RequestError && error.status === 404
export const validatePipeline = (pipeline: PipelineDefinition) => request<ValidationResult>('/api/pipelines/validate', { method: 'POST', body: JSON.stringify(pipeline) })
export const savePipeline = (pipeline: PipelineDefinition) => request<{ id: string }>('/api/pipelines', { method: 'POST', body: JSON.stringify(pipeline) })
export const startExecution = (id: string) => request<{ id: string }>('/api/pipelines/' + encodeURIComponent(id) + '/executions', {
  method: 'POST', body: JSON.stringify({ idempotencyKey: crypto.randomUUID() }),
})
