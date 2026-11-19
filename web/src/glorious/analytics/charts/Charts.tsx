import type { ReactNode } from 'react'
import './charts.css'

const width = 480
const height = 190
const pad = { top: 14, right: 18, bottom: 36, left: 52 }

export function LineChart({ values, label, unit, times }: { values: number[]; label: string; unit: string; times?: string[] }) {
  const valid = values.map((value, index) => ({ value, index })).filter(({ value }) => Number.isFinite(value))
  const max = Math.max(...valid.map(({ value }) => value), 1)
  const min = Math.min(...valid.map(({ value }) => value), 0)
  const x = (index: number) => pad.left + (valid.length < 2 ? 0 : index / (valid.length - 1)) * (width - pad.left - pad.right)
  const y = (value: number) => pad.top + (1 - (value - min) / (max - min || 1)) * (height - pad.top - pad.bottom)
  const points = valid.map(({ value }, index) => `${x(index)},${y(value)}`).join(' ')
  return <figure className="an-chart">
    <figcaption>{label} <span>({unit})</span></figcaption>
    {valid.length ? <svg role="img" aria-label={`${label}, ${valid.length} samples in ${unit}`} viewBox={`0 0 ${width} ${height}`}>
      <line className="an-chart__axis" x1={pad.left} y1={pad.top} x2={pad.left} y2={height - pad.bottom} />
      <line className="an-chart__axis" x1={pad.left} y1={height - pad.bottom} x2={width - pad.right} y2={height - pad.bottom} />
      <text className="an-chart__tick" x={pad.left - 8} y={pad.top + 4} textAnchor="end">{max.toFixed(1)}</text>
      <text className="an-chart__tick" x={pad.left - 8} y={height - pad.bottom} textAnchor="end">{min.toFixed(1)}</text>
      <polyline className="an-chart__line" points={points} />
      {valid.length === 1 && <circle className="an-chart__point" cx={x(0)} cy={y(valid[0]!.value)} r="3" />}
      <text className="an-chart__tick" x={pad.left} y={height - 10}>{times?.[0] ?? 'oldest'}</text>
      <text className="an-chart__tick" x={width - pad.right} y={height - 10} textAnchor="end">{times?.at(-1) ?? 'newest'}</text>
    </svg> : <p className="an-chart__empty">No samples yet.</p>}
  </figure>
}

export function Sparkline({ values, label, unit }: { values: number[]; label: string; unit: string }) {
  return <LineChart values={values} label={label} unit={unit} />
}

export function Bars({ values, label, unit }: { values: number[]; label: string; unit: string }) {
  const max = Math.max(...values.filter(Number.isFinite), 1)
  return <figure className="an-chart"><figcaption>{label} <span>({unit})</span></figcaption>
    {values.length ? <svg role="img" aria-label={`${label}, ${values.length} bars in ${unit}`} viewBox={`0 0 ${width} 110`}>
      <line className="an-chart__axis" x1={pad.left} y1="8" x2={pad.left} y2="82" />
      <line className="an-chart__axis" x1={pad.left} y1="82" x2={width - pad.right} y2="82" />
      {values.map((value, index) => {
        const barWidth = Math.max(2, (width - pad.left - pad.right) / values.length - 4)
        const barHeight = Math.max(0, (value / max) * 70)
        return <rect key={index} className="an-chart__bar" x={pad.left + index * ((width - pad.left - pad.right) / values.length) + 2} y={82 - barHeight} width={barWidth} height={barHeight} rx="2"><title>{value} {unit}</title></rect>
      })}
      <text className="an-chart__tick" x={pad.left} y="102">oldest</text><text className="an-chart__tick" x={width - pad.right} y="102" textAnchor="end">newest</text>
    </svg> : <p className="an-chart__empty">No samples yet.</p>}
  </figure>
}

export function ChartRow({ children }: { children: ReactNode }) {
  return <div className="an-chart-row">{children}</div>
}
