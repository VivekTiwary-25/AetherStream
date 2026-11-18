import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import App from './App'

const originalMockFlag = import.meta.env.VITE_USE_MOCK

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.stubEnv('VITE_USE_MOCK', originalMockFlag)
})

describe('App', () => {
  it('renders the title and omits the mock badge when mock mode is unset', async () => {
    vi.stubEnv('VITE_USE_MOCK', undefined)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

    render(<App />)

    expect(screen.getByRole('heading', { name: 'AetherStream' })).toBeTruthy()
    expect(screen.queryByText('MOCK DATA')).toBeNull()
    expect(await screen.findByText('Deployed version: unknown')).toBeTruthy()
  })
})
