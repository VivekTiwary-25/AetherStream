// Pure motion helpers: easing, frame-rate independent damping, angles, curves and truck path smoothing.

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
  constructor(private readonly maxSamples = 8, private readonly teleportDistance = 160) {}

  push(t: number, x: number, y: number): void {
    const last = this.samples[this.samples.length - 1]
    if (last && t <= last.t) return
    if (last && Math.hypot(x - last.x, y - last.y) > this.teleportDistance) this.samples = []
    this.samples.push({ t, x, y })
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

/** Sample interval is 250 ms; render this far in the past so the smoothing always has a sample ahead. */
export const INTERPOLATION_DELAY_MS = 420
