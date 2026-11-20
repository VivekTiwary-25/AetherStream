import { describe, expect, it } from 'vitest'
import { advanceDistance, easeLabelOffset, facingLeftWithHysteresis, labelTransform, lookAheadPoint, placeMarker, prepareRoute, sampleRoute, truckLabel, type RouteFeature } from './mapLogic'

// Isolated metric path for numerical assertions, not fleet/demo data.
const feature: RouteFeature = {
  type: 'Feature', properties: { routeIndex: 0, from: 'Start', to: 'Turn' },
  geometry: { type: 'LineString', coordinates: [[76.6, 12.3], [76.601, 12.3], [76.6, 12.3]] },
}

describe('live road motion', () => {
  it('integrates km/h over real elapsed time and the actual simulator clock scale', () => {
    expect(advanceDistance(12, 36, 1000, 10, 'DRIVING', 1000)).toBe(112)
    expect(advanceDistance(12, 36, 500, 2, 'DRIVING', 1000)).toBe(22)
    expect(advanceDistance(990, 36, 2000, 1, 'DRIVING', 1000)).toBe(10)
  })

  it.each(['STOPPED', 'IDLE', 'UNLOADING', 'UNKNOWN'])('holds position in %s regardless of last reported speed', (status) => {
    expect(advanceDistance(42, 60, 1000, 10, status, 1000)).toBe(42)
  })

  it('does not move when paused or when telemetry is invalid', () => {
    expect(advanceDistance(42, 60, 1000, 0, 'DRIVING', 1000)).toBe(42)
    expect(advanceDistance(42, NaN, 1000, 1, 'DRIVING', 1000)).toBe(42)
    expect(advanceDistance(42, -5, 1000, 1, 'DRIVING', 1000)).toBe(42)
  })

  it('samples both route directions continuously and wraps at the loop end', () => {
    const route = prepareRoute(feature)
    const sample = { lon: 0, lat: 0, dx: 0, dy: 0 }
    expect(route.totalMeters).toBeGreaterThan(210)
    expect(route.totalMeters).toBeLessThan(220)
    sampleRoute(route, route.totalMeters / 4, sample)
    expect(sample.lon).toBeCloseTo(76.6005, 6)
    expect(sample.lat).toBe(12.3)
    sampleRoute(route, route.totalMeters * 3 / 4, sample)
    expect(sample.lon).toBeCloseTo(76.6005, 6)
    sampleRoute(route, route.totalMeters, sample)
    expect(sample.lon).toBe(76.6)
  })

  it('keeps the truck sample on the route polyline without a perpendicular displacement', () => {
    const route = prepareRoute(feature)
    const point = { lon: 0, lat: 0, dx: 0, dy: 0 }
    sampleRoute(route, route.totalMeters * 0.2, point)
    const start = route.coordinates[0]
    const end = route.coordinates[1]
    const crossTrack = (point.lon - start[0]) * (end[1] - start[1]) - (point.lat - start[1]) * (end[0] - start[0])
    expect(crossTrack).toBeCloseTo(0, 12)
  })

  it('looks ahead along the route and retains heading through small zigzags, then flips on a U-turn', () => {
    const route = prepareRoute({ ...feature, geometry: { type: 'LineString', coordinates: [[76.6, 12.3], [76.602, 12.3], [76.6, 12.3]] } })
    const ahead = { lon: 0, lat: 0, dx: 0, dy: 0 }
    lookAheadPoint(route, 0, 100, ahead)
    expect(ahead.lon).toBeGreaterThan(76.6008)

    let left = facingLeftWithHysteresis(12, false)
    left = facingLeftWithHysteresis(-1, left)
    expect(left).toBe(false)
    left = facingLeftWithHysteresis(-3, left)
    expect(left).toBe(true)
    expect(facingLeftWithHysteresis(1, left)).toBe(true)
    expect(facingLeftWithHysteresis(3, left)).toBe(false)
  })

  it('eases label overlap offsets over multiple frames', () => {
    const firstFrame = easeLabelOffset(0, -80)
    const secondFrame = easeLabelOffset(firstFrame, -80)
    expect(firstFrame).toBe(-8)
    expect(secondFrame).toBe(-16)
    expect(easeLabelOffset(secondFrame, -80)).toBe(-24)
  })

  it('keeps the label centred under the truck while applying the overlap offset', () => {
    // The inline style replaces the stylesheet's translateX(-50%); losing it shifts every label right.
    expect(labelTransform(-16)).toBe('translate(-50%, -16px)')
    expect(labelTransform(0)).toBe('translate(-50%, 0px)')
  })

  it('skips duplicate road points without NaN and rejects a zero-length path', () => {
    const route = prepareRoute({ ...feature, geometry: { type: 'LineString', coordinates: [[76.6, 12.3], [76.6, 12.3], [76.601, 12.3]] } })
    const sample = { lon: 0, lat: 0, dx: 0, dy: 0 }
    sampleRoute(route, 0, sample)
    expect(sample.lon).toBe(76.6)
    expect(sample.dx).toBeGreaterThan(0)
    expect(() => prepareRoute({ ...feature, geometry: { type: 'LineString', coordinates: [[0, 0], [0, 0]] } })).toThrow('nonzero')
  })

  it('labels actual truck IDs without replacing unknown IDs', () => {
    expect(truckLabel('truck-006')).toBe('Truck T-06')
    expect(truckLabel('special-truck')).toBe('special-truck')
  })

  it('keeps all six truck sprites and labels separate on a shared road', () => {
    const positions = Array.from({ length: 6 }, () => ({ x: 0, y: 0 }))
    for (let index = 0; index < positions.length; index++) placeMarker(400, 600, positions, index)
    for (let index = 0; index < positions.length; index++) {
      for (let previous = 0; previous < index; previous++) {
        expect(Math.abs(positions[index].y - positions[previous].y)).toBeGreaterThanOrEqual(80)
      }
    }
  })

  it('rechecks earlier labels after separating a collision and leaves distant trucks on their roads', () => {
    const positions = [{ x: 400, y: 400 }, { x: 400, y: 480 }, { x: 0, y: 0 }, { x: 0, y: 0 }]
    placeMarker(410, 490, positions, 2)
    expect(positions[2]).toEqual({ x: 410, y: 320 })
    placeMarker(600, 490, positions, 3)
    expect(positions[3]).toEqual({ x: 600, y: 490 })
  })
})
