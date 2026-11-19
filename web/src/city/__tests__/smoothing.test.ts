import { describe, expect, it } from 'vitest'
import { CityModel } from '../cityModel'
import type { CityTruck } from '../cityTypes'
import { INTERPOLATION_DELAY_MS, SampleClock, streetCorner, TruckTrack } from '../motion'

const truck = (x: number, y: number): CityTruck => ({ truckId: 'truck-001', x, y, speedKmh: 30, heading: 90, status: 'DRIVING' })
const spread = (values: number[]) => {
  const mean = values.reduce((a, b) => a + b, 0) / values.length
  return Math.sqrt(values.reduce((a, b) => a + (b - mean) ** 2, 0) / values.length)
}

describe('sample clock (de-jitter WebSocket arrivals)', () => {
  // `trucks` frames are sent every 250 ms but arrive with +-40 ms of network / main-thread jitter.
  const arrivals = [0, 290, 470, 790, 1000, 1290, 1460, 1760, 2000, 2290, 2460, 2760, 3000, 3290, 3460, 3760, 4000]

  it('spaces samples far more evenly than the raw arrival times', () => {
    const clock = new SampleClock()
    const stamps = arrivals.map((a) => clock.stamp(a))
    const raw = arrivals.slice(1).map((a, i) => a - arrivals[i])
    const smooth = stamps.slice(1).map((s, i) => s - stamps[i])
    expect(spread(smooth)).toBeLessThan(spread(raw) * 0.5)
  })

  it('never stamps in the future, never lags far behind and always increases', () => {
    const clock = new SampleClock(250, 180)
    let previous = Number.NEGATIVE_INFINITY
    for (const arrival of arrivals) {
      const stamp = clock.stamp(arrival)
      expect(stamp).toBeLessThanOrEqual(arrival)
      expect(stamp).toBeGreaterThanOrEqual(arrival - 180)
      expect(stamp).toBeGreaterThan(previous)
      previous = stamp
    }
  })

  it('does not drift when the server is consistently slower than nominal', () => {
    const clock = new SampleClock()
    let stamp = 0
    for (let i = 0; i <= 80; i += 1) stamp = clock.stamp(i * 300)
    expect(80 * 300 - stamp).toBeLessThan(60)
  })

  it('resyncs to the arrival time after a long gap (tab hidden, reconnect)', () => {
    const clock = new SampleClock()
    clock.stamp(0); clock.stamp(250)
    expect(clock.stamp(9000)).toBe(9000)
  })

  it('handles a burst of late frames without going backwards', () => {
    const clock = new SampleClock()
    const a = clock.stamp(0), b = clock.stamp(250), c = clock.stamp(700), d = clock.stamp(701)
    expect(b).toBeGreaterThan(a)
    expect(c).toBeGreaterThan(b)
    expect(d).toBeGreaterThan(c)
  })

  it('is used by the model for live truck frames, so the rendered path does not jitter', () => {
    const model = new CityModel()
    for (const [i, arrival] of arrivals.entries()) model.apply({ type: 'trucks', ts: 0, data: { simTime: i, trucks: [truck(i * 10, 200)] } }, arrival)
    // Truck moves 10 m per frame at a steady pace; sampled across render time its speed should stay steady too.
    const track = model.trucks.get('truck-001')!.track
    const now = 4000, xs: number[] = []
    for (let t = now - INTERPOLATION_DELAY_MS - 1000; t <= now - INTERPOLATION_DELAY_MS; t += 100) xs.push(track.poseAt(t)!.x)
    const steps = xs.slice(1).map((x, i) => x - xs[i])
    for (const step of steps) expect(step).toBeGreaterThan(0)
    expect(Math.max(...steps) / Math.min(...steps)).toBeLessThan(1.6)
  })
})

describe('street corners', () => {
  it('finds the corner between a sample on an east-west street and one on a north-south street', () => {
    expect(streetCorner({ x: 170, y: 200 }, { x: 200, y: 225 })).toEqual({ x: 200, y: 200 })
    expect(streetCorner({ x: 400, y: 330 }, { x: 420, y: 400 })).toEqual({ x: 400, y: 400 })
  })

  it('ignores samples on the same street or off the grid', () => {
    expect(streetCorner({ x: 180, y: 200 }, { x: 220, y: 200 })).toBeNull()
    expect(streetCorner({ x: 200, y: 180 }, { x: 200, y: 230 })).toBeNull()
    expect(streetCorner({ x: 500, y: 520 }, { x: 600, y: 610 })).toBeNull()
    expect(streetCorner({ x: 200, y: 200 }, { x: 230, y: 215 })).toBeNull()
  })

  it('makes the track turn on the street instead of cutting across the block', () => {
    const track = new TruckTrack()
    // East along y=200, then south along x=200.
    track.push(0, 140, 200); track.push(250, 170, 200); track.push(500, 200, 225); track.push(750, 200, 255)
    expect(track.size).toBe(5)
    // Around the corner the truck stays close to the streets: within 10 m of y=200 or x=200 at every moment.
    for (let t = 125; t <= 625; t += 25) {
      const pose = track.poseAt(t)!
      expect(Math.min(Math.abs(pose.y - 200), Math.abs(pose.x - 200))).toBeLessThan(10)
    }
    // Travel direction swings from east to south through the corner.
    const before = track.poseAt(200)!, after = track.poseAt(560)!
    expect(Math.atan2(before.vy, before.vx)).toBeCloseTo(0, 1)
    expect(Math.atan2(after.vy, after.vx)).toBeCloseTo(Math.PI / 2, 1)
  })

  it('can be switched off (drop-point glides are straight lines)', () => {
    const track = new TruckTrack(8, Number.POSITIVE_INFINITY, 0)
    track.push(0, 170, 200); track.push(250, 200, 225)
    expect(track.size).toBe(2)
  })
})
