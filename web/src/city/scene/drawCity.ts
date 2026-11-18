// Static city art, drawn once into Graphics (vector, so it stays crisp at every zoom).
import { Container, Graphics } from 'pixi.js'
import { CITY_SIZE, STREET_WIDTH, WORLD_HEIGHT, WORLD_WIDTH, hash01, streetLines, type Block, type Building, type CityLayout } from '../cityLayout'

export const palette = {
  void: 0x050b13,
  street: 0x0c1824,
  curb: 0x1a2b3b,
  sidewalk: 0x101e2b,
  tops: [0x1b2c3e, 0x1f3247, 0x182839, 0x22374d],
  side: 0x0b1520,
  highlight: 0x5a7d9c,
  lane: 0x49d8e8,
  park: 0x0d2620,
  parkEdge: 0x1f5a49,
  tree: 0x1b4a3b,
  treeTop: 0x2c6e56,
  water: 0x0a2034,
  waterEdge: 0x2f7fa8,
  plaza: 0x0f1d2a,
}

/** Light comes from the top-left; shadows fall to the bottom-right. */
const SHADOW_DIR = { x: 0.9, y: 1.1 }

export function drawStaticCity(layout: CityLayout): Container {
  const root = new Container({ label: 'static-city' })
  const ground = new Graphics()
  // Outer void plus a faint dot grid in the engine gutter.
  ground.rect(-400, -400, WORLD_WIDTH + 800, WORLD_HEIGHT + 800).fill(palette.void)
  for (let x = CITY_SIZE + 60; x < WORLD_WIDTH + 200; x += 40) for (let y = -200; y < WORLD_HEIGHT + 200; y += 40) ground.circle(x, y, 1.4)
  ground.fill({ color: 0x2a4256, alpha: 0.35 })
  // The city slab: a raised plate with a soft drop shadow.
  for (let i = 3; i >= 1; i -= 1) ground.roundRect(-6 + i * 6, -6 + i * 8, CITY_SIZE + 12, CITY_SIZE + 12, 24).fill({ color: 0x000000, alpha: 0.18 })
  ground.roundRect(-6, -6, CITY_SIZE + 12, CITY_SIZE + 12, 22).fill(palette.street).stroke({ color: 0x1d3446, width: 3, alpha: 0.9 })

  const shadows = new Graphics()
  const blocks = new Graphics()
  const details = new Graphics()
  for (const block of layout.blocks) drawBlock(block, shadows, blocks, details)

  const lanes = new Graphics({ blendMode: 'add' })
  drawLaneMarkings(lanes)

  root.addChild(ground, lanes, shadows, blocks, details)
  return root
}

function drawLaneMarkings(g: Graphics): void {
  const dash = 16, gap = 18, clear = STREET_WIDTH / 2 + 10
  for (const n of streetLines()) {
    if (n === 0 || n === CITY_SIZE) continue
    for (const cross of streetLines()) {
      // Dashes along this street between two cross streets, leaving intersections clean.
      const start = cross + clear, end = cross + 200 - clear
      if (cross >= CITY_SIZE) continue
      for (let p = start; p + dash <= end; p += dash + gap) {
        g.rect(n - 1, p, 2, dash)
        g.rect(p, n - 1, dash, 2)
      }
    }
  }
  g.fill({ color: palette.lane, alpha: 0.22 })
  // Soft halo under the dashes so they read as faintly glowing paint.
  for (const n of streetLines()) {
    if (n === 0 || n === CITY_SIZE) continue
    g.rect(n - 4, 0, 8, CITY_SIZE).rect(0, n - 4, CITY_SIZE, 8)
  }
  g.fill({ color: palette.lane, alpha: 0.025 })
}

function drawBlock(block: Block, shadows: Graphics, blocks: Graphics, details: Graphics): void {
  const { x, y, w, h } = block
  if (block.kind === 'water') {
    blocks.roundRect(x, y, w, h, 14).fill(palette.sidewalk)
    blocks.roundRect(x + 10, y + 10, w - 20, h - 20, 26).fill(palette.water).stroke({ color: palette.waterEdge, width: 2, alpha: 0.35 })
    blocks.roundRect(x + 18, y + 18, w - 36, h - 36, 22).fill({ color: 0x0f2c47, alpha: 0.7 })
    for (let k = 0; k < 6; k += 1) {
      const sx = x + 30 + hash01(x, y, k) * (w - 90), sy = y + 30 + hash01(y, x, k) * (h - 60)
      details.roundRect(sx, sy, 18 + hash01(k, x) * 24, 2, 1)
    }
    details.fill({ color: 0x6fc4e8, alpha: 0.16 })
    return
  }
  if (block.kind === 'park') {
    blocks.roundRect(x, y, w, h, 14).fill(palette.sidewalk)
    blocks.roundRect(x + 8, y + 8, w - 16, h - 16, 12).fill(palette.park).stroke({ color: palette.parkEdge, width: 2, alpha: 0.45 })
    // A footpath across the park.
    blocks.moveTo(x + 14, y + h - 30).quadraticCurveTo(x + w / 2, y + h / 2 - 10, x + w - 14, y + 30).stroke({ color: 0x2b5a4d, width: 5, alpha: 0.6 })
    for (const tree of block.trees) shadows.circle(tree.x + tree.r * 0.6, tree.y + tree.r * 0.8, tree.r)
    shadows.fill({ color: 0x000000, alpha: 0.28 })
    for (const tree of block.trees) {
      details.circle(tree.x, tree.y, tree.r).fill(palette.tree)
      details.circle(tree.x - tree.r * 0.3, tree.y - tree.r * 0.3, tree.r * 0.55).fill({ color: palette.treeTop, alpha: 0.85 })
    }
    return
  }
  if (block.kind === 'plaza') {
    blocks.roundRect(x, y, w, h, 14).fill(palette.plaza).stroke({ color: 0x1d3446, width: 2, alpha: 0.8 })
    for (let k = 1; k < 4; k += 1) blocks.rect(x + (w / 4) * k, y + 10, 1.5, h - 20).rect(x + 10, y + (h / 4) * k, w - 20, 1.5)
    blocks.fill({ color: 0x24394d, alpha: 0.4 })
    return
  }
  blocks.roundRect(x, y, w, h, 14).fill(palette.sidewalk).stroke({ color: palette.curb, width: 2, alpha: 0.9 })
  for (const b of block.buildings) drawBuilding(b, shadows, blocks, details)
}

function drawBuilding(b: Building, shadows: Graphics, blocks: Graphics, details: Graphics): void {
  const lift = b.height * 0.32 // how far the roof is drawn above the footprint (fake 2.5D)
  // Long soft shadow: three stacked extrusions of the footprint, lighter as they get longer.
  for (const [len, alpha] of [[1.7, 0.13], [1.2, 0.16], [0.7, 0.22]] as const) {
    const dx = SHADOW_DIR.x * b.height * len, dy = SHADOW_DIR.y * b.height * len
    shadows.poly([b.x, b.y, b.x + b.w, b.y, b.x + b.w + dx, b.y + dy, b.x + b.w + dx, b.y + b.h + dy, b.x + dx, b.y + b.h + dy, b.x, b.y + b.h]).fill({ color: 0x000000, alpha })
  }
  const top = palette.tops[b.shade]
  // Facade (south face) shows between the lifted roof and the footprint.
  blocks.roundRect(b.x, b.y, b.w, b.h, 4).fill(palette.side)
  blocks.roundRect(b.x, b.y - lift, b.w, b.h, 4).fill(top)
  // Roof highlight along the lit edges and a slightly lighter inner panel.
  blocks.roundRect(b.x + 5, b.y - lift + 5, b.w - 10, b.h - 10, 3).fill({ color: 0xffffff, alpha: 0.025 })
  blocks.moveTo(b.x + 3, b.y - lift + b.h - 3).lineTo(b.x + 1, b.y - lift + 1).lineTo(b.x + b.w - 3, b.y - lift + 1).stroke({ color: palette.highlight, width: 1.5, alpha: 0.55 })
  if (b.roofBox && b.w > 40 && b.h > 40) {
    const bx = b.x + b.w * 0.55, by = b.y - lift + b.h * 0.25
    blocks.roundRect(bx, by, 14, 10, 2).fill({ color: 0x2a4258, alpha: 0.9 })
  }
  // Lit windows in the facade strip, deterministic per building.
  if (lift >= 5) {
    const rowY = b.y + b.h - lift + 2, rowH = Math.max(2, lift - 4)
    for (let wx = b.x + 6, k = 0; wx < b.x + b.w - 8; wx += 9, k += 1) {
      if (hash01(b.windows, k) > 0.42) details.rect(wx, rowY + rowH * 0.25, 3.5, Math.max(1.5, rowH * 0.45))
    }
    details.fill({ color: 0xffd9a0, alpha: 0.35 })
  }
}
