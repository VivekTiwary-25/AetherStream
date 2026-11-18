import { describe, expect, it } from 'vitest'
import { fitWorld, interpolateTruck, partitionForTruck, ParticlePool, worldToScreen } from '../cityLogic'
import type { TruckPose } from '../cityLogic'

describe('city world geometry', () => {
  it('fits the 2km world inside the viewport and maps the origin with padding', () => {
    const view = fitWorld(1000, 600, 20)
    expect(view.scale).toBeCloseTo(.28)
    expect(worldToScreen({ x: 0, y: 0 }, view)).toEqual({ x: 220, y: 20 })
  })

  it('interpolates heading over the shortest angular path', () => {
    const start: TruckPose = { truckId: 'truck-007', x: 0, y: 0, speedKmh: 20, heading: 359, status: 'DRIVING', targetX: 0, targetY: 0, targetHeading: 359 }
    const result = interpolateTruck(start, { ...start, x: 10, heading: 1 }, .5)
    expect(result.x).toBe(5)
    expect(result.heading).toBeCloseTo(0)
  })

  it('assigns repeatable partitions within the configured range', () => {
    expect(partitionForTruck('truck-007')).toBe(partitionForTruck('truck-007'))
    expect(partitionForTruck('truck-007')).toBeGreaterThanOrEqual(0)
    expect(partitionForTruck('truck-007')).toBeLessThan(6)
  })
})

describe('particle pool', () => {
  it('caps live particles and expires them as time advances', () => {
    const pool = new ParticlePool(2)
    pool.emit(0, 0, 10, 10, 0xffffff, 0, 1)
    pool.emit(0, 0, 10, 10, 0xffffff, 1, 1)
    pool.emit(0, 0, 10, 10, 0xffffff, 2, 1)
    expect(pool.particles).toHaveLength(2)
    pool.advance(.5)
    expect(pool.particles[0].x).toBeGreaterThan(0)
    pool.advance(.6)
    expect(pool.particles).toHaveLength(0)
  })
})
