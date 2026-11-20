import type { ReactNode } from 'react'
import './charts.css'

const width = 480
const height = 190
const pad = { top: 14, right: 18, bottom: 36, left: 52 }
const barHeight = 120
const barPad = { top: 10, bottom: 30 }

function tick(value: number): string {
  if (!Number.isFinite(value)) return ''
  return Number.isInteger(value) || Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(1)
}

type ChartValue = number | null | undefined

export function LineChart({ values, label, unit, times }: { values: ChartValue[]; label: string; unit: string; times?: string[] }) {
  const valid = values.flatMap((value, index) => typeof value === 'number' && Number.isFinite(value) ? [{ value, index }] : [])
  const segments: typeof valid[] = []
  for (const point of valid) {
    const previous = segments.at(-1)
    if (previous && previous.at(-1)!.index === point.index - 1) previous.push(point)
    else segments.push([point])
  }
  const max = Math.max(...valid.map(({ value }) => value), 1)
  const min = Math.min(...valid.map(({ value }) => value), 0)
  const plotBottom = height - pad.bottom
  const x = (index: number) => pad.left + (values.length < 2 ? 0 : index / (values.length - 1)) * (width - pad.left - pad.right)
  const y = (value: number) => pad.top + (1 - (value - min) / (max - min || 1)) * (plotBottom - pad.top)
  const mid = (max + min) / 2
  return <figure className="an-chart">
    <figcaption>{label} <span>({unit})</span></figcaption>
    {valid.length ? <svg role="img" aria-label={`${label}, ${valid.length} samples in ${unit}`} viewBox={`0 0 ${width} ${height}`}>
      {[max, mid].map((value) => <line key={value} className="an-chart__grid" x1={pad.left} y1={y(value)} x2={width - pad.right} y2={y(value)} />)}
      <line className="an-chart__axis" x1={pad.left} y1={plotBottom} x2={width - pad.right} y2={plotBottom} />
      <text className="an-chart__tick" x={pad.left - 8} y={y(max) + 3.5} textAnchor="end">{tick(max)}</text>
      <text className="an-chart__tick" x={pad.left - 8} y={y(mid) + 3.5} textAnchor="end">{tick(mid)}</text>
      <text className="an-chart__tick" x={pad.left - 8} y={plotBottom + 3.5} textAnchor="end">{tick(min)}</text>
      {segments.map((segment) => <g key={segment[0]!.index}>
        {segment.length > 1 && <path className="an-chart__area" d={`M${x(segment[0]!.index)},${plotBottom} ${segment.map(({ value, index }) => `L${x(index)},${y(value)}`).join(' ')} L${x(segment.at(-1)!.index)},${plotBottom} Z`} />}
        <polyline className="an-chart__line" points={segment.map(({ value, index }) => `${x(index)},${y(value)}`).join(' ')} />
        <circle className="an-chart__point" cx={x(segment.at(-1)!.index)} cy={y(segment.at(-1)!.value)} r="3.5" />
      </g>)}
      <text className="an-chart__tick" x={pad.left} y={height - 12}>{times?.[0] ?? 'oldest'}</text>
      <text className="an-chart__tick" x={width - pad.right} y={height - 12} textAnchor="end">{times?.at(-1) ?? 'newest'}</text>
    </svg> : <p className="an-chart__empty">{values.length ? 'Samples unavailable.' : 'No samples yet.'}</p>}
    {valid.length > 0 && valid.length < values.length && <p className="an-chart__empty">Gaps indicate unavailable samples.</p>}
  </figure>
}

export function Sparkline({ values, label, unit }: { values: ChartValue[]; label: string; unit: string }) {
  return <LineChart values={values} label={label} unit={unit} />
}

export function Bars({ values, label, unit }: { values: ChartValue[]; label: string; unit: string }) {
  const valid = values.flatMap((value, index) => typeof value === 'number' && Number.isFinite(value) ? [{ value, index }] : [])
  const max = Math.max(...valid.map(({ value }) => value), 1)
  const min = Math.min(...valid.map(({ value }) => value), 0)
  const plotBottom = barHeight - barPad.bottom
  const plotHeight = plotBottom - barPad.top
  const slot = (width - pad.left - pad.right) / Math.max(values.length, 1)
  const y = (value: number) => barPad.top + (max - value) / (max - min) * plotHeight
  return <figure className="an-chart"><figcaption>{label} <span>({unit})</span></figcaption>
    {valid.length ? <svg role="img" aria-label={`${label}, ${valid.length} bars in ${unit}`} viewBox={`0 0 ${width} ${barHeight}`}>
      <line className="an-chart__grid" x1={pad.left} y1={barPad.top} x2={width - pad.right} y2={barPad.top} />
      <line className="an-chart__grid" x1={pad.left} y1={barPad.top + plotHeight / 2} x2={width - pad.right} y2={barPad.top + plotHeight / 2} />
      <text className="an-chart__tick" x={pad.left - 8} y={barPad.top + 3.5} textAnchor="end">{tick(max)}</text>
      <text className="an-chart__tick" x={pad.left - 8} y={plotBottom + 3.5} textAnchor="end">{tick(min)}</text>
      {valid.map(({ value, index }) => {
        const barWidth = Math.max(2, Math.min(28, slot - 6))
        const h = Math.abs(y(value) - y(0))
        return <rect key={index} className="an-chart__bar" x={pad.left + index * slot + (slot - barWidth) / 2} y={Math.min(y(value), y(0))} width={barWidth} height={h} rx="3"><title>{value} {unit}</title></rect>
      })}
      <line className="an-chart__axis" x1={pad.left} y1={plotBottom} x2={width - pad.right} y2={plotBottom} />
      <text className="an-chart__tick" x={pad.left} y={barHeight - 8}>oldest</text><text className="an-chart__tick" x={width - pad.right} y={barHeight - 8} textAnchor="end">newest</text>
    </svg> : <p className="an-chart__empty">{values.length ? 'Samples unavailable.' : 'No samples yet.'}</p>}
    {valid.length > 0 && valid.length < values.length && <p className="an-chart__empty">Gaps indicate unavailable samples.</p>}
  </figure>
}

export function ChartRow({ children }: { children: ReactNode }) {
  return <div className="an-chart-row">{children}</div>
}
