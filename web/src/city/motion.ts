// Pure motion helpers: easing, frame-rate independent damping, angles, curves and truck path smoothing.
import { STREET_SPACING } from './cityLayout'

export interface Vec { x: number; y: number }

export const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))
export const clamp01 = (value: number) => clamp(value, 0, 1)
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t

export const easeOutCubic = (t: number) => 1 - Math.pow(1 - clamp01(t), 3)
export const easeInOutCubic = (t: number) => {
  const x = clamp01(t)
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2
}
/** Slight overshoot, used for things popping into existence. */
export const easeOutBack = (t: number) => {
  const x = clamp01(t), c1 = 1.70158, c3 = c1 + 1
  return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2)
}

/** Exponential smoothing that behaves the same at 30 fps and 144 fps. */
export function damp(current: number, target: number, lambda: number, dt: number): number {
  return target + (current - target) * Math.exp(-lambda * dt)
}

/** Signed shortest rotation (radians, in (-PI, PI]) from one angle to another. */
export function shortestAngle(from: number, to: number): number {
  const full = Math.PI * 2
  let delta = (to - from) % full
  if (delta > Math.PI) delta -= full
  if (delta <= -Math.PI) delta += full
  return delta
}

export function dampAngle(current: number, target: number, lambda: number, dt: number): number {
  return current + shortestAngle(current, target) * (1 - Math.exp(-lambda * dt))
}

/** Compass heading in degrees (0 = north/up, 90 = east) to a screen rotation in radians (0 = +x). */
export const headingToRotation = (headingDeg: number) => (headingDeg - 90) * Math.PI / 180

export function quadPoint(a: Vec, control: Vec, b: Vec, t: number, out: Vec = { x: 0, y: 0 }): Vec {
  const inv = 1 - t
  out.x = inv * inv * a.x + 2 * inv * t * control.x + t * t * b.x
  out.y = inv * inv * a.y + 2 * inv * t * control.y + t * t * b.y
  return out
}

interface Sample { t: number; x: number; y: number }
export interface TrackPose { x: number; y: number; vx: number; vy: number }

/**
 * Smooths a stream of position samples (one every ~250 ms) into a continuous path.
 * The rendered curve runs through the midpoints of consecutive samples using each sample as a quadratic
 * control point. That rounds 90 degree street corners instead of snapping, never overshoots (the curve stays
 * inside the sample hull) and naturally eases to zero speed when a truck stops (repeated samples).
 * Render it a little in the past (see INTERPOLATION_DELAY_MS) so there is always a next sample.
 */
export class TruckTrack {
  private samples: Sample[] = []
  constructor(private readonly maxSamples = 8, private readonly teleportDistance = 160, private readonly streetSpacing = STREET_SPACING) {}

  push(t: number, x: number, y: number): void {
    const last = this.samples[this.samples.length - 1]
    if (last && t <= last.t) return
    if (last && Math.hypot(x - last.x, y - last.y) > this.teleportDistance) this.samples = []
    else if (last && this.streetSpacing > 0) {
      // Two samples either side of a street corner: add the corner itself so the truck turns on the
      // street instead of cutting diagonally across the block.
      const corner = streetCorner(last, { x, y }, this.streetSpacing)
      if (corner) {
        const before = Math.hypot(corner.x - last.x, corner.y - last.y), after = Math.hypot(x - corner.x, y - corner.y)
        const ct = last.t + (t - last.t) * (before / Math.max(before + after, 1e-6))
        if (ct > last.t && ct < t) this.append({ t: ct, x: corner.x, y: corner.y })
      }
    }
    this.append({ t, x, y })
  }

  private append(sample: Sample): void {
    this.samples.push(sample)
    if (this.samples.length > this.maxSamples) this.samples.shift()
  }

  get size(): number { return this.samples.length }

  poseAt(t: number, out: TrackPose = { x: 0, y: 0, vx: 0, vy: 0 }): TrackPose | undefined {
    const s = this.samples
    if (s.length === 0) return undefined
    out.vx = 0; out.vy = 0
    if (s.length === 1) { out.x = s[0].x; out.y = s[0].y; return out }
    // Midpoint k sits between sample k and k+1, at their mean time.
    const midT = (k: number) => (s[k].t + s[k + 1].t) / 2
    const first = midT(0)
    if (t <= first) {
      // Before the first midpoint: ease from sample 0 to midpoint 0 along a straight line.
      const span = first - s[0].t
      const u = clamp01(span > 0 ? (t - s[0].t) / span : 1)
      out.x = lerp(s[0].x, (s[0].x + s[1].x) / 2, u); out.y = lerp(s[0].y, (s[0].y + s[1].y) / 2, u)
      return out
    }
    for (let k = 1; k < s.length - 1; k += 1) {
      const t0 = midT(k - 1), t1 = midT(k)
      if (t > t1) continue
      const u = clamp01((t - t0) / Math.max(t1 - t0, 1e-6))
      const a = { x: (s[k - 1].x + s[k].x) / 2, y: (s[k - 1].y + s[k].y) / 2 }
      const b = { x: (s[k].x + s[k + 1].x) / 2, y: (s[k].y + s[k + 1].y) / 2 }
      quadPoint(a, s[k], b, u, out)
      // Derivative of the quadratic gives the travel direction (used for heading).
      out.vx = 2 * (1 - u) * (s[k].x - a.x) + 2 * u * (b.x - s[k].x)
      out.vy = 2 * (1 - u) * (s[k].y - a.y) + 2 * u * (b.y - s[k].y)
      return out
    }
    // Past the newest midpoint: hold there until the next sample arrives.
    const n = s.length - 1
    out.x = (s[n - 1].x + s[n].x) / 2; out.y = (s[n - 1].y + s[n].y) / 2
    return out
  }
}

const onStreet = (value: number, spacing: number, tolerance: number) => {
  const r = ((value % spacing) + spacing) % spacing
  return r <= tolerance || spacing - r <= tolerance
}

/**
 * The street corner a truck must have turned at between two samples, or null if both samples are on the
 * same street (or either is off the grid). Trucks drive Manhattan paths on streets every `spacing` metres.
 */
export function streetCorner(a: Vec, b: Vec, spacing = STREET_SPACING, tolerance = 3): Vec | null {
  if (Math.abs(a.x - b.x) <= tolerance || Math.abs(a.y - b.y) <= tolerance) return null
  // a on a north-south street, b on an east-west street: drove along x = a.x, then turned onto y = b.y.
  if (onStreet(a.x, spacing, tolerance) && onStreet(b.y, spacing, tolerance) && !onStreet(a.y, spacing, tolerance)) return { x: a.x, y: b.y }
  if (onStreet(a.y, spacing, tolerance) && onStreet(b.x, spacing, tolerance) && !onStreet(a.x, spacing, tolerance)) return { x: b.x, y: a.y }
  return null
}

/**
 * Turns WebSocket arrival times (jittery: network, GC, tab throttling) into evenly spaced sample times.
 * Each stamp is the previous stamp plus the learned interval, nudged towards the real arrival so it never
 * drifts, and never more than `maxLag` ms behind it. After a long gap it resyncs to the arrival time.
 */
export class SampleClock {
  private lastStamp = Number.NEGATIVE_INFINITY
  private lastArrival = Number.NEGATIVE_INFINITY
  private interval: number

  constructor(private readonly nominal = 250, private readonly maxLag = 180) {
    this.interval = nominal
  }

  stamp(arrival: number): number {
    const gap = arrival - this.lastArrival
    this.lastArrival = arrival
    if (!(gap < this.nominal * 4)) {
      this.lastStamp = arrival
      return arrival
    }
    this.interval += (clamp(gap, this.nominal * 0.5, this.nominal * 2) - this.interval) * 0.1
    let t = this.lastStamp + this.interval
    t += (arrival - t) * 0.15
    t = Math.max(clamp(t, arrival - this.maxLag, arrival), this.lastStamp + 1)
    this.lastStamp = t
    return t
  }
}

/** Sample interval is 250 ms; render this far in the past so the smoothing always has a sample ahead. */
export const INTERPOLATION_DELAY_MS = 420
