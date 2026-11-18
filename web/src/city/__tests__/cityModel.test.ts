import { describe, expect, it } from 'vitest'
import { buildCityLayout, CITY_SIZE, STREET_SPACING, STREET_WIDTH } from '../cityLayout'
import { CityModel, engineStatus, nearestStop } from '../cityModel'
import type { CityExecution, CityTruck, CityWorker } from '../cityTypes'

const truck = (id: string, x: number, y: number, status: CityTruck['status'] = 'DRIVING'): CityTruck => ({ truckId: id, x, y, speedKmh: 30, heading: 90, status })
const exec = (state: CityExecution['state'], extra: Partial<CityExecution> = {}): CityExecution => ({ id: 'e1', pipelineId: 'p', pipelineVersion: 1, state, generation: 1, restoredFromCheckpoint: null, lastCompletedCheckpoint: 4, createdAt: '', ...extra })
const worker = (extra: Partial<CityWorker> = {}): CityWorker => ({ workerId: 'w-1', alive: true, state: 'RUNNING', partitions: [0], backpressured: false, recordsPerSec: 1, lastHeartbeatMs: 0, ...extra })

describe('city model', () => {
  it('applies a snapshot and truck updates', () => {
    const model = new CityModel()
    const simulator = { running: true, timeScale: 10, simTime: 5, trucks: [truck('truck-001', 10, 0)], stops: [{ stopId: 'stop-1', x: 400, y: 0 }], depot: { x: 1000, y: 1000 }, emitted: 0 }
    model.apply({ type: 'snapshot', ts: 0, data: { execution: exec('RUNNING'), workers: [worker()], summary: {}, simulator } }, 0)
    expect(model.trucks.get('truck-001')?.track.size).toBe(1)
    expect(model.stops).toHaveLength(1)
    model.apply({ type: 'trucks', ts: 0, data: { simTime: 9, trucks: [truck('truck-001', 20, 0)] } }, 250)
    expect(model.trucks.get('truck-001')?.track.size).toBe(2)
    expect(model.simTime).toBe(9)
  })

  it('fades out and then forgets trucks that disappear', () => {
    const model = new CityModel()
    model.ingest([truck('a', 0, 0), truck('b', 0, 0)], 0)
    model.ingest([truck('a', 0, 0)], 250)
    expect(model.trucks.get('b')?.removedAt).toBe(250)
    model.ingest([truck('a', 0, 0)], 1000)
    expect(model.trucks.has('b')).toBe(false)
  })

  it('records a delivery bloom when a truck finishes unloading at a stop', () => {
    const model = new CityModel()
    model.stops = [{ stopId: 'stop-7', x: 400, y: 600 }]
    model.ingest([truck('a', 402, 600, 'UNLOADING')], 0)
    model.ingest([truck('a', 404, 600, 'DRIVING')], 250)
    expect(model.deliveries).toEqual([{ stopId: 'stop-7', x: 400, y: 600, at: 250 }])
    model.ingest([truck('a', 420, 600, 'DRIVING')], 5000)
    expect(model.deliveries).toEqual([])
  })

  it('lets a dropped truck glide in from the drop point', () => {
    const model = new CityModel()
    model.expectTruck('truck-009', { x: 500, y: 520 }, 0)
    model.ingest([truck('truck-009', 600, 600)], 100)
    const pose = model.trucks.get('truck-009')!.track.poseAt(-300)!
    expect(pose.x).toBeCloseTo(500)
    expect(pose.y).toBeCloseTo(520)
  })

  it('remembers the latest checkpoint event', () => {
    const model = new CityModel()
    model.apply({ type: 'execution', ts: 0, data: { ...exec('RUNNING'), event: 'CHECKPOINT_COMPLETED', checkpointId: 12 } }, 1000)
    expect(model.status(1500)).toMatchObject({ mode: 'checkpointing', label: 'Checkpoint #12 saved' })
    expect(model.status(9000).mode).toBe('running')
  })

  it('finds the nearest stop within range', () => {
    const stops = [{ stopId: 'a', x: 0, y: 0 }, { stopId: 'b', x: 30, y: 0 }]
    expect(nearestStop(stops, { x: 25, y: 0 }, 60)?.stopId).toBe('b')
    expect(nearestStop(stops, { x: 500, y: 0 }, 60)).toBeUndefined()
  })
})

describe('engine status', () => {
  it('covers every UI state from contract 04 with text and colour', () => {
    expect(engineStatus(null, [], null).label).toBe('Awaiting execution')
    expect(engineStatus(exec('RUNNING'), [worker()], null).mode).toBe('running')
    expect(engineStatus(exec('RUNNING'), [worker({ backpressured: true })], null).mode).toBe('backpressured')
    expect(engineStatus(exec('RUNNING'), [worker()], 3).mode).toBe('checkpointing')
    expect(engineStatus(exec('RECOVERING', { restoredFromCheckpoint: 7 }), [], null).label).toBe('Recovering from checkpoint #7')
    expect(engineStatus(exec('STOPPED'), [], null).mode).toBe('stopped')
    const failed = engineStatus(exec('FAILED'), [], null)
    expect(failed.mode).toBe('failed')
    expect(failed.color).toMatch(/^#/)
  })
})

describe('city layout', () => {
  it('is deterministic and has parks, water, a plaza and buildings', () => {
    const a = buildCityLayout(), b = buildCityLayout()
    expect(a).toEqual(b)
    expect(new Set(a.blocks.map((block) => block.kind))).toEqual(new Set(['buildings', 'park', 'water', 'plaza']))
    expect(a.blocks).toHaveLength((CITY_SIZE / STREET_SPACING) ** 2)
  })

  it('keeps every building off the streets', () => {
    for (const block of buildCityLayout().blocks) {
      for (const bld of block.buildings) {
        const cellX = Math.floor(bld.x / STREET_SPACING), cellY = Math.floor(bld.y / STREET_SPACING)
        expect(bld.x).toBeGreaterThan(cellX * STREET_SPACING + STREET_WIDTH / 2)
        expect(bld.y).toBeGreaterThan(cellY * STREET_SPACING + STREET_WIDTH / 2)
        expect(bld.x + bld.w).toBeLessThan((cellX + 1) * STREET_SPACING - STREET_WIDTH / 2)
        expect(bld.y + bld.h).toBeLessThan((cellY + 1) * STREET_SPACING - STREET_WIDTH / 2)
        expect(bld.w).toBeGreaterThan(10)
        expect(bld.h).toBeGreaterThan(10)
      }
    }
  })
})
