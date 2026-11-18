import { describe, expect, it } from 'vitest'
import { CameraRig } from '../camera'

const screen = { width: 1200, height: 800 }
const settle = (rig: CameraRig, seconds = 3) => { for (let i = 0; i < seconds * 60; i += 1) rig.update(1 / 60, screen) }

describe('camera rig', () => {
  it('fits and centres the world at zoom 1', () => {
    const rig = new CameraRig(2000, 2000, 0.5, 5, 0)
    expect(rig.transform(screen).scale).toBeCloseTo(0.4)
    expect(rig.screenToWorld(600, 400, screen)).toEqual({ x: 1000, y: 1000 })
  })

  it('zooms smoothly towards the cursor, keeping the point under it fixed', () => {
    const rig = new CameraRig(2000, 2000)
    const before = rig.screenToWorld(900, 300, screen)
    rig.wheel(900, 300, -300)
    rig.update(1 / 60, screen)
    expect(rig.zoom).toBeGreaterThan(1)
    expect(rig.zoom).toBeLessThan(rig.targetZoom)
    settle(rig)
    expect(rig.zoom).toBeCloseTo(rig.targetZoom)
    const after = rig.screenToWorld(900, 300, screen)
    expect(after.x).toBeCloseTo(before.x, 3)
    expect(after.y).toBeCloseTo(before.y, 3)
  })

  it('clamps zoom', () => {
    const rig = new CameraRig(2000, 2000, 0.6, 5)
    for (let i = 0; i < 40; i += 1) rig.wheel(600, 400, 240)
    settle(rig)
    expect(rig.zoom).toBeCloseTo(0.6)
  })

  it('keeps gliding after a drag is released and then stops', () => {
    const rig = new CameraRig(2000, 2000)
    rig.wheel(600, 400, -600); settle(rig)
    rig.beginDrag()
    for (let i = 0; i < 6; i += 1) rig.dragBy(10, 0, 1 / 60)
    const released = rig.panX
    rig.endDrag(0)
    rig.update(1 / 60, screen)
    expect(rig.panX).toBeGreaterThan(released)
    settle(rig, 4)
    expect(rig.velocityX).toBe(0)
  })

  it('does not glide when the pointer rested before release', () => {
    const rig = new CameraRig(2000, 2000)
    rig.beginDrag(); rig.dragBy(30, 0, 1 / 60); rig.endDrag(0.3)
    expect(rig.velocityX).toBe(0)
  })

  it('animates back to the fitted view on reset', () => {
    const rig = new CameraRig(2000, 2000)
    rig.wheel(100, 100, -500); settle(rig)
    rig.reset()
    rig.update(1 / 60, screen)
    expect(rig.zoom).not.toBe(1)
    settle(rig)
    expect(rig.zoom).toBe(1)
    expect(rig.panX).toBe(0)
    expect(rig.panY).toBe(0)
  })
})
