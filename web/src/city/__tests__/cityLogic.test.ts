import { describe, expect, it } from 'vitest'
import { anomalyRate, flowControl, murmur2, ParticlePool, partitionForTruck, RateAccumulator, visualRate } from '../cityLogic'

const bytes = (s: string) => new TextEncoder().encode(s)

describe('Kafka partitioning', () => {
  it('matches the murmur2 test vectors from Kafka UtilsTest', () => {
    expect(murmur2(bytes('21'))).toBe(-973932308)
    expect(murmur2(bytes('foobar'))).toBe(-790332482)
    expect(murmur2(bytes('a-little-bit-long-string'))).toBe(-985981536)
    expect(murmur2(bytes('a-little-bit-longer-string'))).toBe(-1486304829)
    expect(murmur2(bytes('lkjh234lh9fiuh90y23oiuhsafujhadof229phr9h19h89h8'))).toBe(-58897971)
    expect(murmur2(bytes('abc'))).toBe(479470107)
  })

  it('assigns repeatable partitions within the configured range', () => {
    for (let i = 1; i <= 20; i += 1) {
      const id = `truck-${String(i).padStart(3, '0')}`
      expect(partitionForTruck(id)).toBe(partitionForTruck(id))
      expect(partitionForTruck(id)).toBeGreaterThanOrEqual(0)
      expect(partitionForTruck(id)).toBeLessThan(6)
    }
  })
})

describe('spawn rates', () => {
  it('is zero without traffic, grows sub-linearly and is capped (lower with reduced motion)', () => {
    expect(visualRate(0, false)).toBe(0)
    expect(visualRate(100, false)).toBeGreaterThan(visualRate(10, false))
    expect(visualRate(1e6, false)).toBe(60)
    expect(visualRate(1e6, true)).toBe(8)
    expect(anomalyRate(0, false)).toBe(0)
    expect(anomalyRate(0.01, false)).toBeGreaterThan(0.5)
  })

  it('accumulates fractional spawns across frames', () => {
    const acc = new RateAccumulator()
    let total = 0
    for (let i = 0; i < 60; i += 1) total += acc.take(10, 1 / 60)
    expect(total).toBeGreaterThanOrEqual(9)
    expect(total).toBeLessThanOrEqual(10)
  })

  it('lifts the flow arc above both ends', () => {
    const c = flowControl({ x: 100, y: 500 }, { x: 2100, y: 900 })
    expect(c.y).toBeLessThan(500)
    expect(c.x).toBeGreaterThan(100)
    expect(c.x).toBeLessThan(2100)
  })
})

describe('particle pool', () => {
  it('caps live particles and reuses the same objects', () => {
    const pool = new ParticlePool(2)
    const first = pool.emitCurve('event', 0, { x: 0, y: 0 }, { x: 10, y: 10 }, 1)
    pool.emitCurve('event', 1, { x: 0, y: 0 }, { x: 10, y: 10 }, 1)
    expect(pool.emitCurve('event', 2, { x: 0, y: 0 }, { x: 10, y: 10 }, 1)).toBeUndefined()
    expect(pool.count).toBe(2)
    pool.advance(1.1)
    expect(pool.count).toBe(0)
    const again = pool.emitSpark(0, 0, 1, 1, 1)
    expect([pool.items[0], pool.items[1]]).toContain(first)
    expect(pool.items).toContain(again)
    expect(pool.items).toHaveLength(2)
  })

  it('moves curve particles towards the target and reports arrivals with their lane', () => {
    const pool = new ParticlePool(4)
    pool.emitCurve('rejected', 3, { x: 0, y: 0 }, { x: 100, y: 0 }, 1)
    pool.advance(0.5)
    expect(pool.items[0].x).toBeGreaterThan(0)
    expect(pool.items[0].x).toBeLessThan(100)
    const arrivals: Array<[string, number, number]> = []
    pool.advance(0.6, (kind, lane, x) => arrivals.push([kind, lane, x]))
    expect(arrivals).toEqual([['rejected', 3, 100]])
  })

  it('places trail ghosts behind the head, clamped at the start', () => {
    const pool = new ParticlePool(1)
    const p = pool.emitCurve('event', 0, { x: 0, y: 0 }, { x: 100, y: 0 }, 1)!
    pool.advance(0.5)
    const head = pool.progress(p), ghost1 = pool.progress(p, 0.05), ghost2 = pool.progress(p, 0.1)
    expect(head).toBeGreaterThan(ghost1)
    expect(ghost1).toBeGreaterThan(ghost2)
    expect(pool.progress(p, 10)).toBe(0)
  })

  it('slows sparks with drag', () => {
    const pool = new ParticlePool(1)
    const p = pool.emitSpark(0, 0, 100, 0, 1)!
    pool.advance(0.1)
    const firstStep = p.x
    pool.advance(0.1)
    expect(p.x - firstStep).toBeLessThan(firstStep)
  })
})
