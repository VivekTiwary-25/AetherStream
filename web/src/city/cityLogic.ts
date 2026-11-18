import type { CityPoint, CityTruck } from './cityTypes'

export interface Viewport { width: number; height: number; scale: number; x: number; y: number }
export interface TruckPose extends CityTruck { targetX: number; targetY: number; targetHeading: number }

export function fitWorld(width: number, height: number, padding = 32): Viewport {
  const scale = Math.max(0.01, Math.min((width - padding * 2) / 2000, (height - padding * 2) / 2000))
  return { width, height, scale, x: (width - 2000 * scale) / 2, y: (height - 2000 * scale) / 2 }
}

export function worldToScreen(point: CityPoint, view: Viewport): CityPoint {
  return { x: view.x + point.x * view.scale, y: view.y + point.y * view.scale }
}

export function interpolateTruck(current: TruckPose, next: CityTruck, alpha: number): TruckPose {
  const t = Math.max(0, Math.min(1, alpha))
  const delta = ((next.heading - current.targetHeading + 540) % 360) - 180
  return { ...next, x: current.x + (next.x - current.x) * t, y: current.y + (next.y - current.y) * t, heading: (current.heading + delta * t + 360) % 360, targetX: next.x, targetY: next.y, targetHeading: next.heading }
}

export function partitionForTruck(truckId: string, partitions = 6): number {
  let hash = 2166136261
  for (let i = 0; i < truckId.length; i += 1) hash = Math.imul(hash ^ truckId.charCodeAt(i), 16777619)
  return (hash >>> 0) % partitions
}

export interface Particle { x: number; y: number; fromX: number; fromY: number; toX: number; toY: number; age: number; life: number; color: number; lane: number }

export class ParticlePool {
  readonly particles: Particle[] = []
  constructor(readonly capacity = 300) {}

  emit(x: number, y: number, toX: number, toY: number, color: number, lane: number, life = 1.2): void {
    if (this.particles.length >= this.capacity) this.particles.shift()
    this.particles.push({ x, y, fromX: x, fromY: y, toX, toY, age: 0, life, color, lane })
  }

  advance(deltaSeconds: number): void {
    let write = 0
    for (const particle of this.particles) {
      particle.age += deltaSeconds
      if (particle.age >= particle.life) continue
      const t = particle.age / particle.life
      const inv = 1 - t
      particle.x = inv * inv * particle.fromX + 2 * inv * t * ((particle.fromX + particle.toX) / 2) + t * t * particle.toX
      particle.y = inv * inv * particle.fromY + 2 * inv * t * (particle.fromY - 80) + t * t * particle.toY
      this.particles[write++] = particle
    }
    this.particles.length = write
  }
}
