import type { PipelineDefinition, ValidationResult } from './types'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(body.message ?? `Request failed (${response.status})`)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export const getDemo = () => request<PipelineDefinition>('/api/pipelines/demo')
export const validatePipeline = (pipeline: PipelineDefinition) => request<ValidationResult>('/api/pipelines/validate', { method: 'POST', body: JSON.stringify(pipeline) })
export const savePipeline = (pipeline: PipelineDefinition) => request<{ id: string }>('/api/pipelines', { method: 'POST', body: JSON.stringify(pipeline) })
export const startExecution = (id: string) => request<{ id: string }>('/api/pipelines/' + encodeURIComponent(id) + '/executions', {
  method: 'POST', body: JSON.stringify({ idempotencyKey: crypto.randomUUID() }),
})
