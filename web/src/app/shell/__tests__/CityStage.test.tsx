import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CityStage } from '../CityStage'
import { CITY_MODE_KEY, hasSoftwareWebGL } from '../cityMode'

vi.mock('../../../city/CityView', () => ({ CityView: () => <section aria-label="Live delivery city" data-testid="live-city" /> }))

function fakeWebGL(renderer: string) {
  vi.stubGlobal('WebGLRenderingContext', function WebGLRenderingContext() {})
  const gl = {
    RENDERER: 0x1f01,
    getExtension: (name: string) => name === 'WEBGL_debug_renderer_info' ? { UNMASKED_RENDERER_WEBGL: 0x9246 } : null,
    getParameter: () => renderer,
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation((() => gl) as unknown as HTMLCanvasElement['getContext'])
}

beforeEach(() => localStorage.clear())
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  localStorage.clear()
})

describe('CityStage', () => {
  it('detects software WebGL renderers only', () => {
    expect(hasSoftwareWebGL()).toBe(false) // jsdom: no WebGL at all
    fakeWebGL('ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)')
    expect(hasSoftwareWebGL()).toBe(true)
    fakeWebGL('llvmpipe (LLVM 15.0.7, 256 bits)')
    expect(hasSoftwareWebGL()).toBe(true)
    fakeWebGL('ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Laptop GPU Direct3D11 vs_5_0 ps_5_0)')
    expect(hasSoftwareWebGL()).toBe(false)
  })

  it('shows the static city on a software renderer and lets the presenter opt back in', () => {
    fakeWebGL('SwiftShader driver')
    render(<CityStage />)
    expect(screen.queryByTestId('live-city')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain('no GPU acceleration detected')
    fireEvent.click(screen.getByRole('button', { name: 'Start live city' }))
    expect(screen.getByTestId('live-city')).toBeTruthy()
    expect(localStorage.getItem(CITY_MODE_KEY)).toBe('live')
  })

  it('keeps the live city on hardware and honours a stored static choice', () => {
    render(<CityStage />)
    expect(screen.getByTestId('live-city')).toBeTruthy()
    cleanup()
    localStorage.setItem(CITY_MODE_KEY, 'static')
    fakeWebGL('NVIDIA GeForce')
    render(<CityStage />)
    expect(screen.getByRole('status').textContent).toContain('turned off')
  })

  it('falls back when the live city renders too slowly', () => {
    vi.useFakeTimers()
    let frames = 0
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames += 1
      // ~2 fps: only every 500 ms of fake time does a frame run.
      return window.setTimeout(() => callback(performance.now()), 500)
    })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => window.clearTimeout(id))
    render(<CityStage />)
    expect(screen.getByTestId('live-city')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(7_100) })
    expect(frames).toBeGreaterThan(0)
    expect(screen.queryByTestId('live-city')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain('too slowly')
  })
})
