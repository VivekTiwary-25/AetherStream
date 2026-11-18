// One truck: soft shadow, headlight cone, cargo box + cab, status roof light, stopped pulse ring, id label.
import { Container, Graphics, Sprite, Text } from 'pixi.js'
import type { TruckStatus } from '../cityTypes'
import { CONE_SIZE, coneTexture, glowScale, glowTexture } from './textures'

export const statusColor: Record<TruckStatus, number> = { DRIVING: 0x4ff0f2, UNLOADING: 0xffb547, STOPPED: 0xff4d64, IDLE: 0x9aabbf }

/** Truck length in world metres at scale 1 (exaggerated so it reads on a 2 km map). */
export const TRUCK_LENGTH = 36

export class TruckSprite {
  readonly root = new Container()
  private readonly rotor = new Container()
  private readonly shadow: Graphics
  private readonly lights: Sprite
  private readonly stripe: Graphics
  private readonly roof: Graphics
  private readonly roofGlow: Sprite
  private readonly ring: Graphics
  private readonly hoverRing: Graphics
  readonly label: Text

  constructor(truckId: string) {
    this.root.label = truckId
    const shadow = new Graphics()
      .roundRect(-21, -11, 42, 24, 9).fill({ color: 0x000000, alpha: 0.18 })
      .roundRect(-19, -9, 38, 20, 7).fill({ color: 0x000000, alpha: 0.3 })
    this.shadow = shadow
    shadow.position.set(3, 4)
    this.lights = new Sprite({ texture: coneTexture(), anchor: { x: 0, y: 0.5 }, blendMode: 'add', tint: 0xfff1c9, alpha: 0.55 })
    this.lights.position.set(17, 0)
    this.lights.scale.set(76 / CONE_SIZE.w, 44 / CONE_SIZE.h)
    const body = new Graphics()
      // Cargo box: light panel with a darker base edge and a top highlight.
      .roundRect(-18, -8.5, 25, 17, 3.5).fill(0xd6e2ec)
      .roundRect(-18, 5, 25, 3.5, 2).fill({ color: 0x8ea3b5, alpha: 0.9 })
      .roundRect(-16, -7, 21, 2, 1).fill({ color: 0xffffff, alpha: 0.7 })
      // Cab: dark with a cyan-tinted windscreen.
      .roundRect(8, -7.5, 10, 15, 3.5).fill(0x24384a)
      .roundRect(14, -6, 3.2, 12, 1.5).fill({ color: 0x8fe3f0, alpha: 0.85 })
      .circle(17.5, -5.2, 1.2).fill(0xfff6d8).circle(17.5, 5.2, 1.2).fill(0xfff6d8)
    this.roofGlow = new Sprite({ texture: glowTexture(), anchor: 0.5, blendMode: 'add' })
    this.roofGlow.position.set(11, 0)
    this.roofGlow.scale.set(glowScale(20))
    this.roof = new Graphics().circle(0, 0, 3.6).fill(0xffffff)
    // Status-coloured tail band so the state still reads when the roof light is small on screen.
    this.stripe = new Graphics().roundRect(-18, -8.5, 4, 17, 2).fill(0xffffff)
    this.roof.position.set(11, 0)
    this.rotor.addChild(this.lights, body, this.stripe, this.roofGlow, this.roof)
    this.ring = new Graphics().circle(0, 0, 20).stroke({ color: 0xffffff, width: 2.2 })
    this.ring.visible = false
    this.hoverRing = new Graphics().circle(0, 0, 27).stroke({ color: 0xbff8ff, width: 1.5, alpha: 0.9 })
    this.hoverRing.visible = false
    this.label = new Text({ text: truckId.replace(/^truck-/, ''), resolution: 3, style: { fontFamily: 'Inter, system-ui, sans-serif', fontSize: 10, fontWeight: '600', fill: 0xcfe6f2, letterSpacing: 0.5 } })
    this.label.anchor.set(0.5, 1)
    this.label.position.set(0, -20)
    this.label.alpha = 0.7
    this.root.addChild(shadow, this.ring, this.rotor, this.hoverRing, this.label)
  }

  update(x: number, y: number, rotation: number, status: TruckStatus, seconds: number, opts: { alpha: number; scale: number; hovered: boolean; reducedMotion: boolean }): void {
    this.root.position.set(x, y)
    this.root.alpha = opts.alpha
    this.root.scale.set(opts.scale)
    this.rotor.rotation = rotation
    this.shadow.rotation = rotation
    const color = statusColor[status] ?? statusColor.IDLE
    this.roof.tint = color
    this.roofGlow.tint = color
    this.stripe.tint = color
    this.lights.visible = status === 'DRIVING'
    this.hoverRing.visible = opts.hovered
    this.label.alpha = opts.hovered ? 1 : 0.7
    if (status === 'STOPPED') {
      this.ring.visible = true
      this.ring.tint = color
      if (opts.reducedMotion) { this.ring.scale.set(1.1); this.ring.alpha = 0.6 }
      else {
        // Slow pulse: a ring that grows and fades every 1.8 s.
        const phase = (seconds % 1.8) / 1.8
        this.ring.scale.set(0.8 + phase * 0.9)
        this.ring.alpha = 0.85 * (1 - phase)
      }
    } else this.ring.visible = false
  }

  destroy(): void { this.root.destroy({ children: true }) }
}
