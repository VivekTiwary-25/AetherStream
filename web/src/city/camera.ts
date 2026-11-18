// Pure camera rig: smooth zoom towards the cursor, inertial pan, animated reset.
import { clamp, damp } from './motion'

export interface ViewTransform { scale: number; x: number; y: number }
export interface Screen { width: number; height: number }

export class CameraRig {
  zoom = 1
  targetZoom = 1
  panX = 0
  panY = 0
  velocityX = 0
  velocityY = 0
  dragging = false
  private anchorX = 0
  private anchorY = 0
  private resetting = false

  constructor(
    readonly worldWidth: number,
    readonly worldHeight: number,
    readonly minZoom = 0.6,
    readonly maxZoom = 5,
    readonly padding = 28,
  ) {}

  /** Scale that fits the whole world on screen at zoom 1. */
  baseScale(screen: Screen): number {
    return Math.max(0.01, Math.min((screen.width - this.padding * 2) / this.worldWidth, (screen.height - this.padding * 2) / this.worldHeight))
  }

  transform(screen: Screen): ViewTransform {
    const scale = this.baseScale(screen) * this.zoom
    return { scale, x: (screen.width - this.worldWidth * scale) / 2 + this.panX, y: (screen.height - this.worldHeight * scale) / 2 + this.panY }
  }

  screenToWorld(sx: number, sy: number, screen: Screen): { x: number; y: number } {
    const view = this.transform(screen)
    return { x: (sx - view.x) / view.scale, y: (sy - view.y) / view.scale }
  }

  /** deltaY from a wheel event; positive zooms out. The world point under (sx, sy) stays under the cursor. */
  wheel(sx: number, sy: number, deltaY: number): void {
    this.resetting = false
    this.anchorX = sx; this.anchorY = sy
    this.targetZoom = clamp(this.targetZoom * Math.exp(-clamp(deltaY, -240, 240) * 0.0018), this.minZoom, this.maxZoom)
    this.velocityX = 0; this.velocityY = 0
  }

  beginDrag(): void { this.dragging = true; this.resetting = false; this.velocityX = 0; this.velocityY = 0 }

  dragBy(dx: number, dy: number, dtSeconds: number): void {
    this.panX += dx; this.panY += dy
    if (dtSeconds > 0) {
      // Keep a smoothed velocity so release continues with the recent motion, not one noisy sample.
      const blend = Math.min(1, dtSeconds * 18)
      this.velocityX += (dx / dtSeconds - this.velocityX) * blend
      this.velocityY += (dy / dtSeconds - this.velocityY) * blend
    }
  }

  endDrag(idleSeconds = 0): void {
    this.dragging = false
    if (idleSeconds > 0.08) { this.velocityX = 0; this.velocityY = 0 }
  }

  reset(): void { this.resetting = true; this.targetZoom = 1; this.velocityX = 0; this.velocityY = 0 }

  update(dtSeconds: number, screen: Screen): void {
    if (this.resetting) {
      this.zoom = damp(this.zoom, 1, 9, dtSeconds)
      this.panX = damp(this.panX, 0, 9, dtSeconds)
      this.panY = damp(this.panY, 0, 9, dtSeconds)
      if (Math.abs(this.zoom - 1) < 1e-3 && Math.abs(this.panX) < 0.5 && Math.abs(this.panY) < 0.5) {
        this.zoom = 1; this.panX = 0; this.panY = 0; this.resetting = false
      }
      return
    }
    if (Math.abs(this.zoom - this.targetZoom) > 1e-5) {
      const anchor = this.screenToWorld(this.anchorX, this.anchorY, screen)
      this.zoom = damp(this.zoom, this.targetZoom, 14, dtSeconds)
      if (Math.abs(this.zoom - this.targetZoom) < 1e-4) this.zoom = this.targetZoom
      const scale = this.baseScale(screen) * this.zoom
      this.panX = this.anchorX - anchor.x * scale - (screen.width - this.worldWidth * scale) / 2
      this.panY = this.anchorY - anchor.y * scale - (screen.height - this.worldHeight * scale) / 2
    }
    if (!this.dragging) {
      this.panX += this.velocityX * dtSeconds
      this.panY += this.velocityY * dtSeconds
      const decay = Math.exp(-4.5 * dtSeconds)
      this.velocityX *= decay; this.velocityY *= decay
      if (Math.hypot(this.velocityX, this.velocityY) < 2) { this.velocityX = 0; this.velocityY = 0 }
    }
    this.clampPan(screen)
  }

  /** Keep at least part of the world on screen. */
  private clampPan(screen: Screen): void {
    const scale = this.baseScale(screen) * this.zoom
    const limitX = Math.max(0, (this.worldWidth * scale - screen.width) / 2) + screen.width * 0.35
    const limitY = Math.max(0, (this.worldHeight * scale - screen.height) / 2) + screen.height * 0.35
    const x = clamp(this.panX, -limitX, limitX), y = clamp(this.panY, -limitY, limitY)
    if (x !== this.panX) this.velocityX = 0
    if (y !== this.panY) this.velocityY = 0
    this.panX = x; this.panY = y
  }
}
