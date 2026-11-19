import { useEffect, useRef, useState } from 'react'
import { analyticsMenu } from '../analytics/menu'
import { settingsMenu } from '../recovery/menu'
import { GlassSurface } from './GlassSurface'
import { Icon, type IconName } from './Icon'
import type { Screen } from './router'
import type { SidebarSection } from './sections'
import { useWindows } from './windows'

const destinations: { id: Exclude<Screen, 'editor'> | SidebarSection; label: string; icon: IconName }[] = [
  { id: 'home', label: 'Home', icon: 'home' },
  { id: 'live', label: 'Live World', icon: 'live' },
  { id: 'analytics', label: 'Analytics', icon: 'analytics' },
  { id: 'pipelines', label: 'Pipelines', icon: 'pipelines' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
]

export function Sidebar({ screen, navigate }: { screen: Screen; navigate: (screen: Screen) => void }) {
  const [section, setSection] = useState<SidebarSection | null>(null)
  const [leaving, setLeaving] = useState(false)
  const transition = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(transition.current), [])
  const changeSection = (next: SidebarSection | null) => {
    clearTimeout(transition.current)
    setLeaving(true)
    transition.current = setTimeout(() => { setSection(next); setLeaving(false) }, 150)
  }
  const windows = useWindows()
  const rail = screen === 'home' && section === null
  return <aside className={`gl-sidebar${rail ? ' gl-sidebar--rail' : ''}`}>
    <GlassSurface className="gl-sidebar__surface">
      <div className="gl-sidebar__brand"><Icon name="stream" /><span>AetherStream</span></div>
      <nav aria-label="Main navigation" className={`gl-sidebar__nav${leaving ? ' gl-sidebar__nav--leaving' : ''}`} key={section ?? 'main'}>
        {section ? <>
          <button type="button" className="gl-nav-item gl-nav-back" onClick={() => changeSection(null)} aria-label="Back to main navigation"><Icon name="back" /><span>{section === 'analytics' ? 'Analytics' : 'Settings'}</span></button>
          {(section === 'analytics' ? analyticsMenu : settingsMenu).map((item) => <button key={item.id} type="button" className={`gl-nav-item${windows.isOpen(`${section}.${item.id}`) ? ' gl-nav-item--active' : ''}`} onClick={() => item.onSelect(windows)}>{item.icon}<span>{item.label}</span></button>)}
        </> : destinations.map(({ id, label, icon }) => <button key={id} type="button" className={`gl-nav-item${screen === id ? ' gl-nav-item--active' : ''}`} aria-label={label} aria-current={screen === id ? 'page' : undefined} title={rail ? label : undefined}
          onClick={() => { if (id === 'analytics' || id === 'settings') { navigate('live'); changeSection(id) } else navigate(id) }}><Icon name={icon} /><span>{label}</span></button>)}
      </nav>
    </GlassSurface>
  </aside>
}
