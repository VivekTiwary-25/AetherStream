export interface Scenario {
  name: string
  label: string
  description: string
}

export interface SimulatorState {
  scenario?: string | null
}

export interface ApplyScenarioRequest {
  name: string
  truckId?: string
}

export const builtInScenarios: Scenario[] = [
  { name: 'rush-hour', label: 'Rush hour', description: 'Send more trucks through the city at peak speed.' },
  { name: 'breakdown', label: 'Breakdown', description: 'Stop a truck and watch the stuck-truck alert.' },
  { name: 'surge', label: 'Surge (+6 trucks)', description: 'Add six trucks to the city fleet.' },
  { name: 'reset', label: 'Reset', description: 'Return the simulator to its normal conditions.' },
]

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('Simulator is not reachable. Check that it is running and try again.')
  }

  if (!response.ok) {
    if (response.status === 404 && path === '/api/scenarios') {
      throw new ScenarioListNotFoundError()
    }
    if (response.status >= 500) {
      throw new Error('The simulator could not complete that request. Please try again.')
    }
    throw new Error('The simulator rejected that request. Please check the selection and try again.')
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

class ScenarioListNotFoundError extends Error {}

export async function getScenarios(): Promise<Scenario[]> {
  try {
    return await request<Scenario[]>('/api/scenarios')
  } catch (error) {
    if (error instanceof ScenarioListNotFoundError) return builtInScenarios
    throw error
  }
}

export function getSimulatorState(): Promise<SimulatorState> {
  return request('/api/simulator/state')
}

export function applyScenario(input: ApplyScenarioRequest): Promise<void> {
  return request('/api/simulator/scenario', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}
