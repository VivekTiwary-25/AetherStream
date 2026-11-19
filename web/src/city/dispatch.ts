// "Add truck": drop-point validation and the POST /api/simulator/trucks call, kept free of React/Pixi so it is testable.
import { CITY_SIZE } from './cityLayout'
import type { CityPoint } from './cityTypes'

export const TRUCK_MIME = 'text/city-truck'

export type NoticeTone = 'info' | 'pending' | 'success' | 'error'
export interface Notice { tone: NoticeTone; text: string }

/** How long each kind of notice stays in the dock (ms). Errors stay longer so they can be read. */
export const NOTICE_MS: Record<NoticeTone, number> = { info: 2600, pending: 8000, success: 2400, error: 4200 }

export interface DropCheck { point: CityPoint; inside: boolean }

/** Grace margin (m) outside the 2 km grid that still counts as "on the map" (the edge streets). */
const EDGE_MARGIN = 40

/** Is a world point a valid place to add a truck? The simulator snaps it to the nearest street. */
export function checkDrop(world: CityPoint | null | undefined): DropCheck {
  if (!world || !Number.isFinite(world.x) || !Number.isFinite(world.y)) return { point: { x: CITY_SIZE / 2, y: CITY_SIZE / 2 }, inside: false }
  const inside = world.x >= -EDGE_MARGIN && world.x <= CITY_SIZE + EDGE_MARGIN && world.y >= -EDGE_MARGIN && world.y <= CITY_SIZE + EDGE_MARGIN
  return { point: { x: Math.round(Math.min(CITY_SIZE, Math.max(0, world.x))), y: Math.round(Math.min(CITY_SIZE, Math.max(0, world.y))) }, inside }
}

export type DispatchResult = { ok: true; truckId: string | null; notice: Notice } | { ok: false; notice: Notice }

type FetchLike = (input: string, init?: RequestInit) => Promise<Pick<Response, 'ok' | 'status' | 'json'>>

/** POST /api/simulator/trucks (contract 04) and turn every outcome into a short, plain notice. */
export async function dispatchTruck(point: CityPoint, fetchImpl: FetchLike = (input, init) => fetch(input, init)): Promise<DispatchResult> {
  let response: Awaited<ReturnType<FetchLike>>
  try {
    response = await fetchImpl('/api/simulator/trucks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ x: Math.round(point.x), y: Math.round(point.y) }) })
  } catch {
    return { ok: false, notice: { tone: 'error', text: 'Simulator unreachable · truck not added' } }
  }
  const body = await response.json().catch(() => null) as { truckId?: unknown; message?: unknown } | null
  if (!response.ok) {
    // Contract 04 error shape: { error, message }.
    const reason = typeof body?.message === 'string' && body.message.trim() ? body.message.trim() : `HTTP ${response.status}`
    return { ok: false, notice: { tone: 'error', text: `Truck not added · ${reason}` } }
  }
  const truckId = typeof body?.truckId === 'string' && body.truckId ? body.truckId : null
  return { ok: true, truckId, notice: { tone: 'success', text: truckId ? `${truckId} dispatched` : 'Truck dispatched' } }
}
