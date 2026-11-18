import { Texture } from 'pixi.js'

let glow: Texture | null = null

/** Soft round white glow (tint + additive blend it). Made once from a canvas radial gradient. */
export function glowTexture(): Texture {
  if (glow && !glow.destroyed) return glow
  const size = 64
  const canvas = document.createElement('canvas')
  canvas.width = size; canvas.height = size
  const ctx = canvas.getContext('2d')
  if (ctx) {
    const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
    g.addColorStop(0, 'rgba(255,255,255,1)')
    g.addColorStop(0.18, 'rgba(255,255,255,0.75)')
    g.addColorStop(0.45, 'rgba(255,255,255,0.22)')
    g.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, size, size)
  }
  glow = Texture.from(canvas)
  return glow
}

/** The glow texture is 64 px wide; scale for a glow of the given world radius. */
export const glowScale = (radius: number) => radius / 32

let cone: Texture | null = null
export const CONE_SIZE = { w: 128, h: 72 }

/** Headlight cone pointing along +x from its left-centre, fading with distance and towards the edges. */
export function coneTexture(): Texture {
  if (cone && !cone.destroyed) return cone
  const canvas = document.createElement('canvas')
  canvas.width = CONE_SIZE.w; canvas.height = CONE_SIZE.h
  const ctx = canvas.getContext('2d')
  if (ctx) {
    const mid = CONE_SIZE.h / 2
    const radial = ctx.createRadialGradient(0, mid, 2, 0, mid, CONE_SIZE.w)
    radial.addColorStop(0, 'rgba(255,255,255,0.9)')
    radial.addColorStop(0.35, 'rgba(255,255,255,0.35)')
    radial.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = radial
    ctx.beginPath()
    ctx.moveTo(0, mid - 5)
    ctx.lineTo(CONE_SIZE.w, 0)
    ctx.lineTo(CONE_SIZE.w, CONE_SIZE.h)
    ctx.lineTo(0, mid + 5)
    ctx.closePath()
    ctx.filter = 'blur(4px)'
    ctx.fill()
  }
  cone = Texture.from(canvas)
  return cone
}

export function releaseTextures(): void {
  glow?.destroy(true)
  cone?.destroy(true)
  glow = null
  cone = null
}
