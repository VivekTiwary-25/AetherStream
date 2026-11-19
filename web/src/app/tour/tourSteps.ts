export interface TourStep {
  id: string
  title: string
  body: string
  /** CSS selector for the spotlight target. A missing target simply means no spotlight. */
  target?: string
}

export const TOUR_STORAGE_KEY = 'aetherstream.tour.step'
export const INTRO_STORAGE_KEY = 'aetherstream.intro.seen'

export const tourSteps: TourStep[] = [
  {
    id: 'city',
    title: 'The city',
    body: 'Every truck in this city is a live data source. Each one sends its position and deliveries as events.',
    target: '[data-tour="city"], .city__canvas',
  },
  {
    id: 'stream',
    title: 'The stream into the engine',
    body: 'The glowing stream is those events flowing into the engine through Kafka, split across 6 partitions by truck.',
    target: '[data-tour="stream"], .city__canvas',
  },
  {
    id: 'panels',
    title: 'Live panels',
    body: 'Every number here is computed by the engine: speed per truck per minute, deliveries per minute and the stuck-truck alert.',
    target: '[data-tour="panels"], .panel-grid',
  },
  {
    id: 'editor',
    title: 'Pipeline editor',
    body: 'Build the pipeline from blocks. Validate runs the compiler and shows any errors in plain English.',
    target: '[data-tour="editor"], .pipeline-editor',
  },
  {
    id: 'chaos',
    title: 'Chaos',
    body: 'Send late events and slow down the output. Bounded queues fill up and the engine pushes back (backpressure) instead of losing data.',
    target: '[data-tour="chaos"], [aria-labelledby="panel-heading-chaos-controls"]',
  },
  {
    id: 'kill',
    title: 'Kill a worker',
    body: 'Kill the busiest worker. The engine recovers from checkpoint #N with a new generation, and the old worker is fenced off.',
    target: '[data-tour="kill"], [aria-labelledby="panel-heading-checkpoint-recovery-timeline"], [aria-labelledby="panel-heading-workers"]',
  },
  {
    id: 'compare',
    title: 'Compare with reference',
    body: 'A simple single-process reference replays the same events. The result reads "N / N results match".',
    target: '[data-tour="compare"], [aria-labelledby="panel-heading-reference-comparison"]',
  },
  {
    id: 'devops',
    title: 'DevOps',
    body: 'The commit in the footer is the exact version deployed by GitHub Actions.',
    target: '[data-tour="devops"], .app-footer',
  },
]
