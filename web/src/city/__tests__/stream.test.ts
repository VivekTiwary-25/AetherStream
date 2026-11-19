import { describe, expect, it } from 'vitest'
import { laneVisualRates, RateAccumulator, streamStyle, visualRate, zoomFade } from '../cityLogic'
import { CityModel, stalledLanes } from '../cityModel'
import type { CityWorker } from '../cityTypes'

const worker = (extra: Partial<CityWorker> = {}): CityWorker => ({ workerId: 'w-1', alive: true, state: 'RUNNING', partitions: [0], backpressured: false, recordsPerSec: 1, lastHeartbeatMs: 0, ...extra })
const lanes = () => new Array<number>(6).fill(-1)
const sum = (values: number[]) => values.reduce((a, b) => a + b, 0)

describe('stream rate per partition lane', () => {
  it('splits the visual rate in proportion to the real per-partition rates', () => {
    const out = laneVisualRates({ eventsPerSec: 100, byPartition: { 0: 30, 1: 10, 4: 60 } }, false, lanes())
    expect(sum(out)).toBeCloseTo(visualRate(100, false))
    expect(out[0] / out[1]).toBeCloseTo(3)
    expect(out[4] / out[1]).toBeCloseTo(6)
    expect(out[2]).toBe(0); expect(out[3]).toBe(0); expect(out[5]).toBe(0)
  })

  it('splits evenly when there is no per-partition breakdown, and is silent without traffic', () => {
    const even = laneVisualRates({ eventsPerSec: 40 }, false, lanes())
    for (const rate of even) expect(rate).toBeCloseTo(visualRate(40, false) / 6)
    expect(laneVisualRates({ eventsPerSec: 0, byPartition: { 0: 0 } }, false, lanes())).toEqual([0, 0, 0, 0, 0, 0])
    expect(laneVisualRates(null, false, lanes())).toEqual([0, 0, 0, 0, 0, 0])
  })

  it('ignores negative or junk partition values and writes into the given array', () => {
    const target = lanes()
    const out = laneVisualRates({ eventsPerSec: 10, byPartition: { 0: -5, 1: Number.NaN, 2: 10 } }, false, target)
    expect(out).toBe(target)
    expect(out[2]).toBeCloseTo(visualRate(10, false))
    expect(out[0]).toBe(0); expect(out[1]).toBe(0)
  })

  it('keeps the reduced-motion cap', () => {
    expect(sum(laneVisualRates({ eventsPerSec: 5000, byPartition: { 0: 1, 1: 1 } }, true, lanes()))).toBeCloseTo(8)
  })

  it('spawns what the rate says over time at any frame rate (rate follows real events)', () => {
    const out = laneVisualRates({ eventsPerSec: 90, byPartition: { 0: 45, 3: 45 } }, false, lanes())
    for (const fps of [30, 60, 144]) {
      const acc = out.map(() => new RateAccumulator())
      const spawned = new Array<number>(6).fill(0)
      for (let frame = 0; frame < fps * 10; frame += 1) acc.forEach((a, lane) => { spawned[lane] += a.take(out[lane], 1 / fps) })
      expect(Math.abs(spawned[0] - out[0] * 10)).toBeLessThanOrEqual(1)
      expect(Math.abs(spawned[3] - out[3] * 10)).toBeLessThanOrEqual(1)
      expect(spawned[1]).toBe(0)
    }
  })
})

describe('stream style per engine state', () => {
  it('flows normally when running or checkpointing', () => {
    expect(streamStyle('running')).toMatchObject({ active: true, travel: 1, alpha: 1 })
    expect(streamStyle('checkpointing')).toEqual(streamStyle('running'))
  })

  it('is subtle but visible during recovery and backpressure: slower, fainter, recolored', () => {
    const running = streamStyle('running'), recovering = streamStyle('recovering'), slow = streamStyle('backpressured')
    expect(recovering.active).toBe(true)
    expect(recovering.travel).toBeGreaterThan(running.travel)
    expect(recovering.alpha).toBeLessThan(running.alpha)
    expect(recovering.color).not.toBe(running.color)
    expect(slow.travel).toBeGreaterThan(running.travel)
  })

  it('stops spawning when there is nothing live to show', () => {
    for (const mode of ['idle', 'stopped', 'failed', 'disconnected', 'something-new']) expect(streamStyle(mode).active).toBe(false)
  })

  it('fades packets when zoomed in to street level', () => {
    expect(zoomFade(1)).toBe(1)
    expect(zoomFade(1.5)).toBe(1)
    expect(zoomFade(2.25)).toBeCloseTo(0.675)
    expect(zoomFade(5)).toBe(0.35)
  })
})

describe('worker killed -> stalled lanes', () => {
  it('marks the partitions of a dead worker as stalled', () => {
    const out = stalledLanes([worker({ workerId: 'a', partitions: [0, 3] }), worker({ workerId: 'b', alive: false, partitions: [1, 4] })], new Array<boolean>(6).fill(true))
    expect(out).toEqual([false, true, false, false, true, false])
  })

  it('clears a lane once a live running worker owns it again (recovered)', () => {
    const out = stalledLanes([worker({ workerId: 'b', alive: false, partitions: [1, 4] }), worker({ workerId: 'a', partitions: [0, 1, 3, 4] })], new Array<boolean>(6).fill(false))
    expect(out).toEqual([false, false, false, false, false, false])
  })

  it('keeps the lane stalled while the new owner is still restoring, and ignores bad partition numbers', () => {
    const out = stalledLanes([worker({ workerId: 'b', alive: false, partitions: [2, 9, -1] }), worker({ workerId: 'a', state: 'RESTORING', partitions: [2] })], new Array<boolean>(6).fill(false))
    expect(out).toEqual([false, false, true, false, false, false])
  })
})

describe('live link state', () => {
  it('shows a reconnecting status while the WebSocket is down and resumes after', () => {
    const model = new CityModel()
    model.connected = false
    expect(model.status(0)).toMatchObject({ mode: 'disconnected', label: 'Live link lost · reconnecting' })
    expect(streamStyle(model.status(0).mode).active).toBe(false)
    model.connected = true
    expect(model.status(0).mode).toBe('idle')
  })

  it('counts only live trucks', () => {
    const model = new CityModel()
    const t = (id: string) => ({ truckId: id, x: 0, y: 0, speedKmh: 0, heading: 0, status: 'IDLE' as const })
    model.ingest([t('a'), t('b')], 0)
    model.ingest([t('a')], 250)
    expect(model.trucks.size).toBe(2)
    expect(model.activeTrucks()).toBe(1)
  })
})
