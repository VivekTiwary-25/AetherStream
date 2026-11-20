import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Bars, LineChart } from './Charts'

afterEach(cleanup)

describe('charts preserve unavailable measurements', () => {
  it('keeps original sample positions and splits lines and fills at missing values', () => {
    const { container } = render(<LineChart values={[10, Number.NaN, 30, 40]} label="Speed" unit="km/h" times={['first', 'last']} />)
    expect(screen.getByRole('img', { name: 'Speed, 3 samples in km/h' })).toBeTruthy()
    const lines = container.querySelectorAll('polyline')
    expect(lines).toHaveLength(2)
    expect(lines[0]!.getAttribute('points')!.split(' ')).toHaveLength(1)
    expect(lines[1]!.getAttribute('points')!.split(' ')).toHaveLength(2)
    expect(Number(lines[0]!.getAttribute('points')!.split(',')[0])).toBe(52)
    expect(Number(lines[1]!.getAttribute('points')!.split(',')[0])).toBeCloseTo(52 + 410 * 2 / 3)
    expect(container.querySelectorAll('.an-chart__area')).toHaveLength(1)
    expect(screen.getByText('first')).toBeTruthy()
    expect(screen.getByText('last')).toBeTruthy()
  })

  it('does not connect finite points across null, undefined or infinite measurements', () => {
    const { container } = render(<LineChart values={[1, null, 2, undefined, 3, Infinity, 4]} label="Rate" unit="events/s" />)
    expect(container.querySelectorAll('polyline')).toHaveLength(4)
    expect(container.querySelectorAll('.an-chart__area')).toHaveLength(0)
    expect(container.querySelectorAll('circle')).toHaveLength(4)
  })

  it('omits unavailable bars without shifting valid bars or disguising them as zero', () => {
    const { container } = render(<Bars values={[2, Number.NaN, null, Infinity, 0]} label="Deliveries" unit="deliveries" />)
    expect(screen.getByRole('img', { name: 'Deliveries, 2 bars in deliveries' })).toBeTruthy()
    const bars = container.querySelectorAll('rect')
    expect(bars).toHaveLength(2)
    expect(bars[0]!.textContent).toBe('2 deliveries')
    expect(bars[1]!.textContent).toBe('0 deliveries')
    expect(bars[1]!.getAttribute('height')).toBe('0')
    expect(Number(bars[1]!.getAttribute('x')) - Number(bars[0]!.getAttribute('x'))).toBeCloseTo(410 * 4 / 5)
  })

  it('shows empty states when neither chart has a finite measurement', () => {
    render(<><LineChart values={[null, Infinity]} label="Rate" unit="events/s" /><Bars values={[undefined, Number.NaN]} label="Counts" unit="records" /></>)
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getAllByText('Samples unavailable.')).toHaveLength(2)
  })

  it('shows no-samples states only for genuinely empty histories', () => {
    render(<><LineChart values={[]} label="Rate" unit="events/s" /><Bars values={[]} label="Counts" unit="records" /></>)
    expect(screen.getAllByText('No samples yet.')).toHaveLength(2)
  })
})
