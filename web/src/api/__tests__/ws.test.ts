import { createElement, type ReactNode } from 'react'
import { act, cleanup, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ExecutionBanner } from '../../app/panels/LivePanels'
import { DashboardProvider, useDashboard } from '../../app/state/store'
import type { ExecutionEvent, WebSocketMessage, WindowResult } from '../types'
import { getSocketStatus, subscribeMessages, useAetherMessages, useAetherSocket } from '../ws'

class FakeSocket {
  static instances: FakeSocket[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  closed = false
  constructor(readonly url: string) { FakeSocket.instances.push(this) }
  close() {
    if (this.closed) return
    this.closed = true
    this.onclose?.()
  }
  open() { this.onopen?.() }
  /** Delivers frames back to back in the same task, as the browser does when several are already buffered. */
  burst(...frames: unknown[]) {
    frames.forEach((frame) => this.onmessage?.({ data: typeof frame === 'string' ? frame : JSON.stringify(frame) } as MessageEvent<string>))
  }
}

const socket = () => FakeSocket.instances.at(-1)!

function windowFrame(key: string, ts: number): WebSocketMessage<WindowResult[]> {
  return { type: 'windows', ts, data: [{ sinkId: 'speedSink', key, windowStart: 0, windowEnd: 60_000, values: { avgSpeedKmh: ts }, eventCount: 3 }] }
}

function executionFrame(event: ExecutionEvent['event'], state: ExecutionEvent['state'], ts: number): WebSocketMessage<ExecutionEvent> {
  return {
    type: 'execution', ts,
    data: { id: 'exec-1', pipelineId: 'demo', pipelineVersion: 1, state, generation: 2, restoredFromCheckpoint: 7, lastCompletedCheckpoint: 7, createdAt: '2026-11-21T09:00:00Z', event, checkpointId: 7 },
  }
}

beforeEach(() => {
  FakeSocket.instances = []
  vi.stubGlobal('WebSocket', FakeSocket as unknown as typeof WebSocket)
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 204, json: async () => null }) as Response))
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('WebSocket message subscription', () => {
  it('delivers a burst of 3 frames in one tick to every subscriber, in order', () => {
    const first: string[] = []
    const second: string[] = []
    const stopFirst = subscribeMessages((message) => first.push(`${message.type}:${message.ts}`))
    const stopSecond = subscribeMessages((message) => second.push(`${message.type}:${message.ts}`))
    expect(FakeSocket.instances).toHaveLength(1)
    socket().open()
    socket().burst(windowFrame('truck-001', 1), { type: 'flow', ts: 2, data: { eventsPerSec: 1 } }, windowFrame('truck-002', 3))
    expect(first).toEqual(['windows:1', 'flow:2', 'windows:3'])
    expect(second).toEqual(first)
    stopFirst()
    stopSecond()
    expect(socket().closed).toBe(true)
  })

  it('skips malformed frames and keeps going when one listener throws', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const seen: number[] = []
    const stopBad = subscribeMessages(() => { throw new Error('boom') })
    const stopGood = subscribeMessages((message) => seen.push(message.ts))
    socket().burst(windowFrame('a', 1), '{not json', 'null', windowFrame('b', 2))
    expect(seen).toEqual([1, 2])
    expect(error).toHaveBeenCalledTimes(2)
    stopBad()
    stopGood()
    error.mockRestore()
  })

  it('reports the current status to late subscribers and keeps the legacy lastMessage hook', () => {
    const early = renderHook(() => useAetherMessages(() => undefined))
    act(() => socket().open())
    expect(early.result.current).toBe('connected')
    const late = renderHook(() => useAetherSocket())
    expect(FakeSocket.instances).toHaveLength(1)
    expect(late.result.current.status).toBe('connected')
    act(() => socket().burst(windowFrame('truck-001', 1), windowFrame('truck-002', 2)))
    expect(late.result.current.lastMessage?.ts).toBe(2)
    early.unmount()
    late.unmount()
    expect(getSocketStatus()).toBe('connecting')
  })
})

describe('dashboard store with bursts', () => {
  const wrapper = ({ children }: { children: ReactNode }) => createElement(DashboardProvider, null, children)

  it('applies all 3 frames of a same-tick burst, in order', () => {
    const { result } = renderHook(() => useDashboard(), { wrapper })
    act(() => {
      socket().open()
      socket().burst(windowFrame('truck-001', 1), windowFrame('truck-002', 2), windowFrame('truck-003', 3))
    })
    // Windows are kept newest first, so arrival order 1, 2, 3 reads back 3, 2, 1.
    expect(result.current.windows.map((row) => row.key)).toEqual(['truck-003', 'truck-002', 'truck-001'])
    expect(result.current.socketStatus).toBe('connected')
  })

  it('does not skip RECOVERING when RECOVERED arrives in the same batch, and shows it on the banner', () => {
    vi.useFakeTimers()
    const seen: string[] = []
    const stop = subscribeMessages((message) => seen.push((message.data as ExecutionEvent).event))
    const { result } = renderHook(() => useDashboard(), { wrapper })
    render(createElement(DashboardProvider, null, createElement(ExecutionBanner)))
    act(() => {
      socket().open()
      socket().burst(executionFrame('WORKER_DEAD', 'RUNNING', 1), executionFrame('RECOVERING', 'RECOVERING', 2), executionFrame('RECOVERED', 'RUNNING', 3))
    })
    expect(seen).toEqual(['WORKER_DEAD', 'RECOVERING', 'RECOVERED'])
    expect(result.current.recoveries).toBe(1)
    expect(result.current.lastExecutionEvent?.event).toBe('RECOVERED')
    expect(result.current.execution?.state).toBe('RUNNING')
    const banner = screen.getByRole('region', { name: 'Execution status' })
    expect(banner.textContent).toContain('Recovering from checkpoint #7')
    act(() => { vi.advanceTimersByTime(1600) })
    expect(banner.textContent).toContain('Recovered')
    expect(banner.textContent).toContain('Running')
    expect(banner.textContent).not.toMatch(/recovering/i)
    stop()
  })
})
