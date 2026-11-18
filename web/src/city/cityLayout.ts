// Deterministic city layout: the simulator's 2000 x 2000 m grid with streets every 200 m.
// Blocks between streets become buildings, parks, ponds or the depot plaza. Same output every run.

export const CITY_SIZE = 2000
export const STREET_SPACING = 200
export const STREET_WIDTH = 30
/** Extra world space right of the city for the stream engine. */
export const WORLD_WIDTH = 2640
export const WORLD_HEIGHT = CITY_SIZE

export interface Building { x: number; y: number; w: number; h: number; height: number; shade: number; windows: number; roofBox: boolean }
export interface Tree { x: number; y: number; r: number }
export interface Block {
  kind: 'buildings' | 'park' | 'water' | 'plaza'
  x: number; y: number; w: number; h: number
  buildings: Building[]
  trees: Tree[]
}
export interface CityLayout { blocks: Block[] }

/** Small deterministic hash -> [0, 1). */
export function hash01(...values: number[]): number {
  let h = 2166136261
  for (const value of values) {
    h = Math.imul(h ^ (value | 0), 16777619)
    h ^= h >>> 13
    h = Math.imul(h, 0x5bd1e995)
  }
  h ^= h >>> 15
  return (h >>> 0) / 4294967296
}

const WATER_CELLS = new Set(['1,7', '2,7', '8,2'])
const PARK_CELLS = new Set(['3,2', '6,7', '7,4', '2,4', '5,1', '8,8', '0,0'])
const PLAZA_CELLS = new Set(['4,4', '5,4', '4,5', '5,5'])

export function buildCityLayout(seed = 42): CityLayout {
  const blocks: Block[] = []
  const cells = CITY_SIZE / STREET_SPACING
  const half = STREET_WIDTH / 2
  for (let j = 0; j < cells; j += 1) {
    for (let i = 0; i < cells; i += 1) {
      const x = i * STREET_SPACING + half, y = j * STREET_SPACING + half
      const size = STREET_SPACING - STREET_WIDTH
      const key = `${i},${j}`
      const kind: Block['kind'] = PLAZA_CELLS.has(key) ? 'plaza' : WATER_CELLS.has(key) ? 'water' : PARK_CELLS.has(key) ? 'park' : 'buildings'
      const block: Block = { kind, x, y, w: size, h: size, buildings: [], trees: [] }
      if (kind === 'buildings') block.buildings = lotsFor(block, seed, i, j)
      if (kind === 'park') block.trees = treesFor(block, seed, i, j)
      blocks.push(block)
    }
  }
  return { blocks }
}

function lotsFor(block: Block, seed: number, i: number, j: number): Building[] {
  const inset = 12, gap = 12
  const x = block.x + inset, y = block.y + inset, w = block.w - inset * 2, h = block.h - inset * 2
  const pattern = Math.floor(hash01(seed, i, j, 1) * 4)
  const split = 0.38 + hash01(seed, i, j, 2) * 0.24
  const rects: Array<[number, number, number, number]> = []
  if (pattern === 0) rects.push([x, y, w, h])
  else if (pattern === 1) rects.push([x, y, w * split - gap / 2, h], [x + w * split + gap / 2, y, w * (1 - split) - gap / 2, h])
  else if (pattern === 2) rects.push([x, y, w, h * split - gap / 2], [x, y + h * split + gap / 2, w, h * (1 - split) - gap / 2])
  else {
    const sx = w * split, sy = h * (1 - split)
    rects.push([x, y, sx - gap / 2, sy - gap / 2], [x + sx + gap / 2, y, w - sx - gap / 2, sy - gap / 2],
      [x, y + sy + gap / 2, sx - gap / 2, h - sy - gap / 2], [x + sx + gap / 2, y + sy + gap / 2, w - sx - gap / 2, h - sy - gap / 2])
  }
  // Taller buildings toward the centre, like a downtown.
  const centrality = 1 - Math.hypot(i - 4.5, j - 4.5) / 6.4
  return rects.map(([bx, by, bw, bh], k) => {
    const r = hash01(seed, i, j, 10 + k)
    return { x: bx, y: by, w: bw, h: bh, height: Math.round(10 + (14 + centrality * 30) * r), shade: Math.floor(hash01(seed, i, j, 20 + k) * 4), windows: Math.floor(hash01(seed, i, j, 30 + k) * 1000), roofBox: hash01(seed, i, j, 40 + k) > 0.55 }
  })
}

function treesFor(block: Block, seed: number, i: number, j: number): Tree[] {
  const trees: Tree[] = []
  const count = 9 + Math.floor(hash01(seed, i, j, 3) * 7)
  for (let k = 0; k < count; k += 1) {
    trees.push({ x: block.x + 18 + hash01(seed, i, j, 50 + k) * (block.w - 36), y: block.y + 18 + hash01(seed, i, j, 80 + k) * (block.h - 36), r: 7 + hash01(seed, i, j, 110 + k) * 7 })
  }
  return trees
}

/** Street centre lines (both axes), for lane markings. */
export function streetLines(): number[] {
  const lines: number[] = []
  for (let n = 0; n <= CITY_SIZE; n += STREET_SPACING) lines.push(n)
  return lines
}
