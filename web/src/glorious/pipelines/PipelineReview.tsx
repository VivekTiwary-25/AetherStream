import { describePipeline, DISPLAY_NAMES, isNodeKind, type Pipeline } from './model'

/** Readable vertical list of the pipeline's steps, from the live truck stream to the outputs. */
export function PipelineReview({ pipeline }: { pipeline: Pipeline }) {
  const steps = describePipeline(pipeline)
  return <ol className="gl-review" aria-label={`Steps of ${pipeline.name}`}>
    <li className="gl-review__source">Live truck positions</li>
    {steps.map((step, index) => {
      const previous = index === 0 ? undefined : steps[index - 1]!.id
      const branch = step.inputs.length > 0 && !step.inputs.includes(previous ?? '')
      return <li key={step.id} className={`gl-review__step gl-review__step--${step.type}`}>
        <span className="gl-review__kind">{isNodeKind(step.type) ? DISPLAY_NAMES[step.type] : step.type}</span>
        <span>{step.text.replace(/^[^:]+: /, '')}</span>
        {branch && <small className="gl-muted">from {step.inputs.join(', ')}</small>}
      </li>
    })}
  </ol>
}
