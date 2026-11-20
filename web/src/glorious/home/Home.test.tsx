import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Home } from './Home'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Home media', () => {
  it('renders the looping home video with its poster', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })))

    const { container } = render(<Home />)
    const video = container.querySelector('video')

    expect(video).not.toBeNull()
    expect(video?.hasAttribute('autoplay')).toBe(true)
    expect(video?.muted).toBe(true)
    expect(video?.hasAttribute('loop')).toBe(true)
    expect(video?.hasAttribute('playsinline')).toBe(true)
    expect(video?.getAttribute('poster')).toBe('/glorious/home-poster.jpg')
    expect(video?.querySelector('source')?.getAttribute('src')).toBe('/glorious/home-loop.mp4')
  })

  it('renders the poster image when reduced motion is preferred', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} })))

    render(<Home />)

    expect(screen.getByRole('img', { name: 'Delivery truck on the highway to Mysuru' }).getAttribute('src'))
      .toBe('/glorious/home-poster.jpg')
    expect(document.querySelector('video')).toBeNull()
  })
})
