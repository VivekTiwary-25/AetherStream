import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import PipelineEditor from '../PipelineEditor'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('PipelineEditor', () => {
  it('keeps Run disabled until validation succeeds', () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    render(<PipelineEditor open onClose={() => undefined} />)
    expect((screen.getByRole('button', { name: 'Run' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
