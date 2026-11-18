import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { IntroSplash, TourOverlay, tourSteps, TOUR_STORAGE_KEY } from '..'

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
    matches: reduce && query.includes('reduce'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })))
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('TourOverlay', () => {
  it('has 8 steps and navigates with arrow keys, remembering the step', () => {
    expect(tourSteps).toHaveLength(8)
    render(<TourOverlay />)
    expect(screen.getByText('Step 1 of 8')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'The city' })).toBeTruthy()

    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByText('Step 2 of 8')).toBeTruthy()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByRole('heading', { name: 'Live panels' })).toBeTruthy()
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('2')

    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(screen.getByText('Step 2 of 8')).toBeTruthy()

    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(screen.getByText('Step 1 of 8')).toBeTruthy()
  })

  it('Next/Back buttons work and Esc closes', () => {
    const onClose = vi.fn()
    render(<TourOverlay onClose={onClose} />)
    expect((screen.getByRole('button', { name: 'Back' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Step 2 of 8')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByText('Step 1 of 8')).toBeTruthy()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('resumes from the stored step and finishes on the last step', () => {
    localStorage.setItem(TOUR_STORAGE_KEY, '7')
    const onClose = vi.fn()
    render(<TourOverlay onClose={onClose} />)
    expect(screen.getByRole('heading', { name: 'DevOps' })).toBeTruthy()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByText('Step 8 of 8')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not crash when the spotlight target is missing or the selector is invalid', () => {
    render(<TourOverlay steps={[
      { id: 'a', title: 'Missing', body: 'No such element.', target: '#does-not-exist' },
      { id: 'b', title: 'Broken', body: 'Bad selector.', target: '[[[' },
    ]} />)
    expect(screen.getByRole('heading', { name: 'Missing' })).toBeTruthy()
    expect(screen.queryByTestId('tour-spotlight')).toBeNull()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByRole('heading', { name: 'Broken' })).toBeTruthy()
    expect(screen.queryByTestId('tour-spotlight')).toBeNull()
  })

  it('shows a spotlight when the target exists', () => {
    const target = document.createElement('div')
    target.className = 'panel-grid'
    target.getBoundingClientRect = () => ({ top: 10, left: 20, width: 300, height: 200, right: 320, bottom: 210, x: 20, y: 10, toJSON: () => ({}) })
    document.body.appendChild(target)
    localStorage.setItem(TOUR_STORAGE_KEY, '2')
    render(<TourOverlay />)
    expect(screen.getByTestId('tour-spotlight')).toBeTruthy()
  })

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    render(<TourOverlay />)
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByText('Step 2 of 8')).toBeTruthy()
  })
})

describe('IntroSplash', () => {
  it('is skipped with reduced motion', () => {
    stubReducedMotion(true)
    render(<IntroSplash />)
    expect(screen.queryByText('AetherStream')).toBeNull()
  })

  it('shows once per session and hides after 2 seconds', () => {
    stubReducedMotion(false)
    vi.useFakeTimers()
    const onDone = vi.fn()
    render(<IntroSplash onDone={onDone} />)
    expect(screen.getByText('AetherStream')).toBeTruthy()
    expect(screen.getByText('a fault-tolerant stream engine')).toBeTruthy()
    act(() => { vi.advanceTimersByTime(2000) })
    expect(screen.queryByText('AetherStream')).toBeNull()
    expect(onDone).toHaveBeenCalledTimes(1)

    cleanup()
    render(<IntroSplash />)
    expect(screen.queryByText('AetherStream')).toBeNull()
  })

  it('can be skipped', () => {
    stubReducedMotion(false)
    render(<IntroSplash />)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    expect(screen.queryByText('AetherStream')).toBeNull()
  })
})
