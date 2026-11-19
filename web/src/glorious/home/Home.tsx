import { useReducedMotion } from '../shell/motion'

export function Home() {
  const reducedMotion = useReducedMotion()
  return <div className="gl-home" aria-label="Mysuru highway">
    {reducedMotion
      ? <img className="gl-home__media" src="/glorious/home-poster.jpg" alt="Delivery truck on the highway to Mysuru" />
      : <video className="gl-home__media" autoPlay muted loop playsInline poster="/glorious/home-poster.jpg" aria-hidden="true"><source src="/glorious/home-loop.mp4" type="video/mp4" /></video>}
    <h1 className="gl-sr-only">AetherStream</h1>
  </div>
}
