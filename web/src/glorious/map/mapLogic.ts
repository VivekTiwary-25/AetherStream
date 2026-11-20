export type RouteFeature = {
  type: 'Feature'
  properties: { routeIndex: number; from: string; to: string }
  geometry: { type: 'LineString'; coordinates: [number, number][] }
}

export type PreparedRoute = {
  feature: RouteFeature
  totalMeters: number
  coordinates: [number, number][]
  cumulativeMeters: Float64Array
}

export type RouteSample = { lon: number; lat: number; dx: number; dy: number }
const radians = Math.PI / 180

/** Precompute road segment lengths once; animation only interpolates along them. */
export function prepareRoute(feature: RouteFeature): PreparedRoute {
  const coordinates = feature.geometry.coordinates
  if (coordinates.length < 2) throw new Error('A route needs at least two road coordinates')
  const cumulativeMeters = new Float64Array(coordinates.length)
  for (let i = 1; i < coordinates.length; i++) {
    const [lon1, lat1] = coordinates[i - 1]
    const [lon2, lat2] = coordinates[i]
    const a = Math.sin((lat2 - lat1) * radians / 2) ** 2
      + Math.cos(lat1 * radians) * Math.cos(lat2 * radians) * Math.sin((lon2 - lon1) * radians / 2) ** 2
    cumulativeMeters[i] = cumulativeMeters[i - 1] + 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, a)))
  }
  const totalMeters = cumulativeMeters[coordinates.length - 1]
  if (!(totalMeters > 0)) throw new Error('A route needs a nonzero road length')
  return { feature, coordinates, cumulativeMeters, totalMeters }
}

/** Uses simulated elapsed time, not a cosmetic speed multiplier. Only DRIVING moves. */
export function advanceDistance(distance: number, speedKmh: number, elapsedMs: number, timeScale: number, status: string, totalMeters: number): number {
  if (status !== 'DRIVING' || !Number.isFinite(speedKmh) || speedKmh <= 0
    || !Number.isFinite(timeScale) || timeScale <= 0 || !Number.isFinite(elapsedMs) || elapsedMs <= 0
    || !(totalMeters > 0)) return distance
  return (distance + speedKmh / 3.6 * elapsedMs / 1000 * timeScale) % totalMeters
}

/** Writes into an existing sample to avoid per-frame garbage. Routes are closed. */
export function sampleRoute(route: PreparedRoute, distance: number, out: RouteSample): void {
  const meters = ((distance % route.totalMeters) + route.totalMeters) % route.totalMeters
  let lo = 1
  let hi = route.coordinates.length - 1
  while (lo < hi) {
    const mid = (lo + hi) >>> 1
    if (route.cumulativeMeters[mid] <= meters) lo = mid + 1
    else hi = mid
  }
  const start = route.coordinates[lo - 1]
  const end = route.coordinates[lo]
  const segmentMeters = route.cumulativeMeters[lo] - route.cumulativeMeters[lo - 1]
  const fraction = segmentMeters > 0 ? (meters - route.cumulativeMeters[lo - 1]) / segmentMeters : 0
  out.dx = end[0] - start[0]
  out.dy = end[1] - start[1]
  out.lon = start[0] + out.dx * fraction
  out.lat = start[1] + out.dy * fraction
}

/** Samples a point a fixed distance ahead, wrapping on the closed display route. */
export function lookAheadPoint(route: PreparedRoute, distance: number, aheadMeters: number, out: RouteSample): void {
  sampleRoute(route, distance + aheadMeters, out)
}

/** Retains the prior heading inside a small dead band to prevent sprite flicker. */
export function facingLeftWithHysteresis(screenDx: number, wasFacingLeft: boolean, threshold = 2): boolean {
  if (screenDx < -threshold) return true
  if (screenDx > threshold) return false
  return wasFacingLeft
}

/** Moves a label offset by at most maxStep pixels per animation frame. */
export function easeLabelOffset(current: number, target: number, maxStep = 8): number {
  const delta = target - current
  return Math.abs(delta) <= maxStep ? target : current + Math.sign(delta) * maxStep
}

/** Inline label transform. It replaces the CSS translateX(-50%), so it must keep the centring. */
export function labelTransform(offsetY: number): string {
  return `translate(-50%, ${offsetY}px)`
}


/** Truck T-01 is a display label for the real simulator ID truck-001. */
export function truckLabel(id: string): string {
  const match = /^truck-(\d+)$/.exec(id)
  return match ? `Truck T-${Number(match[1]).toString().padStart(2, '0')}` : id
}

/** Screen-space clearance includes the sprite and the label below it, even on shared roads. */
export function placeMarker(x: number, y: number, placed: { x: number; y: number }[], index: number): void {
  const position = placed[index]
  position.x = x
  position.y = y
  for (let previous = 0; previous < index; previous++) {
    const other = placed[previous]
    if (Math.abs(position.x - other.x) < 90 && Math.abs(position.y - other.y) < 80) {
      position.y = other.y - 80
      previous = -1
    }
  }
}
