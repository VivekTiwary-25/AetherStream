import { describe, expect, it } from 'vitest'
import { damp, dampAngle, easeInOutCubic, easeOutBack, easeOutCubic, headingToRotation, quadPoint, shortestAngle, TruckTrack } from '../motion'

describe('easing', () => {
  it('starts at 0, ends at 1 and clamps', () => {
    for (const ease of [easeOutCubic, easeInOutCubic, easeOutBack]) {
      expect(ease(0)).toBeCloseTo(0)
      expect(ease(1)).toBeCloseTo(1)
      expect(ease(2)).toBeCloseTo(1)
    }
    expect(easeInOutCubic(0.5)).toBeCloseTo(0.5)
    expect(easeOutCubic(0.5)).toBeGreaterThan(0.5)
    expect(Math.max(...[0.6, 0.7, 0.8].map(easeOutBack))).toBeGreaterThan(1)
  })

  it('damps the same amount regardless of frame rate', () => {
    let a = 0, b = 0
    for (let i = 0; i < 30; i += 1) a = damp(a, 10, 5, 1 / 30)
    for (let i = 0; i < 144; i += 1) b = damp(b, 10, 5, 1 / 144)
    expect(a).toBeCloseTo(b, 6)
  })
})

describe('angles', () => {
  it('takes the shortest way round', () => {
    expect(shortestAngle(0.1, -0.1)).toBeCloseTo(-0.2)
    expect(shortestAngle(Math.PI - 0.1, -Math.PI + 0.1)).toBeCloseTo(0.2)
    expect(dampAngle(Math.PI - 0.1, -Math.PI + 0.1, 1000, 1)).toBeCloseTo(Math.PI + 0.1)
  })

  it('maps compass heading to screen rotation', () => {
    expect(headingToRotation(90)).toBeCloseTo(0)
    expect(headingToRotation(0)).toBeCloseTo(-Math.PI / 2)
  })

  it('evaluates quadratic curves', () => {
    expect(quadPoint({ x: 0, y: 0 }, { x: 50, y: 100 }, { x: 100, y: 0 }, 0.5)).toEqual({ x: 50, y: 50 })
  })
})

describe('truck track (corner turning)', () => {
  // Driving east along y=0, turning south at (100, 0).
  const track = () => {
    const t = new TruckTrack()
    t.push(0, 0, 0); t.push(250, 50, 0); t.push(500, 100, 0); t.push(750, 100, 50); t.push(1000, 100, 100)
    return t
  }

  it('passes through midpoints between samples', () => {
    const pose = track().poseAt(375)!
    expect(pose.x).toBeCloseTo(75)
    expect(pose.y).toBeCloseTo(0)
  })

  it('rounds the corner instead of snapping through it', () => {
    const pose = track().poseAt(500)!
    // The raw polyline corner is (100, 0); the smoothed path cuts inside it.
    expect(pose.x).toBeLessThan(100)
    expect(pose.y).toBeGreaterThan(0)
    expect(pose.x).toBeGreaterThan(75)
    expect(pose.y).toBeLessThan(25)
  })

  it('turns the travel direction smoothly through the corner', () => {
    const t = track()
    const angles = [375, 437, 500, 563, 625].map((time) => { const p = t.poseAt(time)!; return Math.atan2(p.vy, p.vx) })
    for (let i = 1; i < angles.length; i += 1) {
      expect(angles[i]).toBeGreaterThanOrEqual(angles[i - 1])
      expect(angles[i] - angles[i - 1]).toBeLessThan(Math.PI / 3)
    }
    expect(angles[0]).toBeCloseTo(0, 1)
    expect(angles[4]).toBeCloseTo(Math.PI / 2, 1)
  })

  it('eases to a stop when samples stop moving', () => {
    const t = new TruckTrack()
    t.push(0, 0, 0); t.push(250, 50, 0); t.push(500, 100, 0); t.push(750, 100, 0); t.push(1000, 100, 0)
    const early = t.poseAt(400)!, late = t.poseAt(620)!
    const earlySpeed = Math.hypot(early.vx, early.vy), lateSpeed = Math.hypot(late.vx, late.vy)
    expect(lateSpeed).toBeLessThan(earlySpeed)
    const stopped = t.poseAt(875)!
    expect(stopped.x).toBeCloseTo(100)
    expect(Math.hypot(stopped.vx, stopped.vy)).toBeCloseTo(0)
  })

  it('holds at the newest midpoint when data is late and resets on teleport', () => {
    const t = track()
    expect(t.poseAt(5000)).toMatchObject({ x: 100, y: 75 })
    t.push(1250, 1500, 1500)
    expect(t.size).toBe(1)
    expect(t.poseAt(2000)).toMatchObject({ x: 1500, y: 1500 })
  })

  it('ignores out-of-order samples', () => {
    const t = track()
    t.push(100, 999, 999)
    expect(t.size).toBe(5)
  })
})
