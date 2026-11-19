import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ValidationList } from '../ValidationList'

describe('ValidationList', () => {
  it('shows compiler messages and provides a Show me action for the associated node', () => {
    const select = vi.fn()
    render(<ValidationList errors={[{ nodeId: 'speed', code: 'INVALID_CONFIG', message: 'Set a valid window size.' }]} onSelect={select} />)
    screen.getByText('Set a valid window size.')
    screen.getByText('INVALID_CONFIG')
    fireEvent.click(screen.getByRole('button', { name: 'Show me' }))
    expect(select).toHaveBeenCalledWith('speed')
  })
})
