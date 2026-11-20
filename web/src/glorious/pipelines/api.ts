import { isPipeline, type FieldInfo, type Pipeline } from './model'

export interface ValidationError { nodeId?: string; message: string }
export interface ValidationResult { valid: boolean; errors: ValidationError[] }
export type RunState = 'RUNNING' | 'STOPPED' | 'FAILED'
export interface RunResult { seq: number; ts: number | string; output: string; kind: 'row' | 'alert' | 'average'; data: Record<string, unknown> }
export interface ResultsPage { runId: string; state: RunState; error?: string; startedAt?: number | string; eventsIn: number; droppedRows?: number; results: RunResult[] }
export interface GenerateResponse { pipeline: Pipeline; source: 'ai' | 'example'; note?: string; model?: string; ms: number; validatedBy: 'control-api' | 'local' | 'example' }

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: unknown) {
    super(status === 0 ? 'Network error' : `Request failed (${status})`)
  }
}

async function call<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, null)
  }
  const body: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(response.status, body)
  if (body === null) throw new ApiError(response.status, null)
  return body as T
}

const BASE = '/api/glorious/pipelines'
const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) })

export const getFields = () => call<{ fields: FieldInfo[]; nodeTypes?: unknown[] }>(`${BASE}/fields`)
export const validatePipeline = (pipeline: Pipeline) => call<ValidationResult>(`${BASE}/validate`, json(pipeline))
export const startRun = (pipeline: Pipeline) => call<{ runId: string }>(`${BASE}/runs`, json(pipeline))
export const getResults = (runId: string, after: number, signal?: AbortSignal) =>
  call<ResultsPage>(`${BASE}/runs/${encodeURIComponent(runId)}/results?after=${after}`, { signal })
export const stopRun = (runId: string) => call<{ state: RunState }>(`${BASE}/runs/${encodeURIComponent(runId)}/stop`, { method: 'POST' })

export async function generatePipeline(prompt: string): Promise<GenerateResponse> {
  const result = await call<GenerateResponse>('/api/glorious/ai/generate', json({ prompt }))
  if (!result || !isPipeline(result.pipeline)) throw new ApiError(-1, result)
  return result
}

export interface ErrorView { message: string; errors: ValidationError[] }

const bodyField = (body: unknown, key: string) => body && typeof body === 'object' && typeof (body as Record<string, unknown>)[key] === 'string' ? (body as Record<string, string>)[key] : undefined

/** Turns any thrown error into an honest message plus any validation errors (400). */
export function describeError(error: unknown, service: 'pipeline' | 'ai' | 'results' = 'pipeline'): ErrorView {
  const unavailable = service === 'ai' ? 'AI service unavailable' : 'Pipeline service unavailable'
  if (!(error instanceof ApiError)) return { message: error instanceof Error ? error.message : 'Unexpected error', errors: [] }
  if (error.status === -1) return { message: 'The AI service returned an unexpected response', errors: [] }
  if (error.status === 0 || error.status === 404 || error.status === 502 || error.status === 503 || error.status === 504) return { message: unavailable, errors: [] }
  if (error.status === 429) return { message: service === 'ai' ? 'The AI is busy with another request. Try again in a moment.' : 'The pipeline service is busy. Try again in a moment.', errors: [] }
  if (error.status === 400) {
    const body = error.body as Partial<ValidationResult> | null
    const errors = Array.isArray(body?.errors) ? body.errors.filter((item): item is ValidationError => !!item && typeof item.message === 'string') : []
    if (service === 'results') {
      const detail = errors.length ? errors.map((item) => item.message).join('; ') : bodyField(error.body, 'error') ?? bodyField(error.body, 'message') ?? 'The request was rejected (400)'
      return { message: `Could not fetch pipeline results: ${detail}`, errors: [] }
    }
    return { message: errors.length ? 'The pipeline is not valid' : bodyField(error.body, 'error') ?? bodyField(error.body, 'message') ?? 'The request was rejected (400)', errors }
  }
  if (service === 'ai') return { message: unavailable, errors: [] }
  const detail = bodyField(error.body, 'error') ?? bodyField(error.body, 'message')
  return { message: detail ? `${error.message}: ${detail}` : error.message, errors: [] }
}
