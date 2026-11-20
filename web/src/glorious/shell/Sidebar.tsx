import { useEffect, useRef, useState } from 'react'
import { analyticsMenu } from '../analytics/menu'
import { settingsMenu } from '../recovery/menu'
import { PIPELINES_MODE } from '../pipelines/mode'
import { GlassSurface } from './GlassSurface'
import { Icon, type IconName } from './Icon'
import { usePhone } from './phone'
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
  const phone = usePhone()
  const sidebar = useRef<HTMLElement>(null)
  const [section, setSection] = useState<SidebarSection | null>(null)
  const [leaving, setLeaving] = useState(false)
  const transition = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(transition.current), [])
  useEffect(() => {
    if (phone && section) sidebar.current?.querySelector<HTMLButtonElement>('.gl-nav-back')?.focus()
  }, [phone, section])
  const changeSection = (next: SidebarSection | null) => {
    clearTimeout(transition.current)
    if (phone) {
      setSection(next)
      setLeaving(false)
      if (!next) sidebar.current?.querySelector<HTMLButtonElement>(`[aria-label="${section === 'analytics' ? 'Analytics' : 'Settings'}"]`)?.focus()
      return
    }
    setLeaving(true)
    transition.current = setTimeout(() => { setSection(next); setLeaving(false) }, 150)
  }
  const windows = useWindows()
  const rail = screen === 'home' && section === null
  const options = section && <>
    <button type="button" className="gl-nav-item gl-nav-back" onClick={() => changeSection(null)} aria-label="Back to main navigation"><Icon name="back" /><span>{section === 'analytics' ? 'Analytics' : 'Settings'}</span></button>
    {(section === 'analytics' ? analyticsMenu : settingsMenu).map((item) => <button key={item.id} type="button" className={`gl-nav-item${windows.isOpen(`${section}.${item.id}`) ? ' gl-nav-item--active' : ''}`} onClick={() => { if (phone) changeSection(null); item.onSelect(windows) }}>{item.icon}<span>{item.label}</span></button>)}
  </>
  return <aside ref={sidebar} className={`gl-sidebar${rail ? ' gl-sidebar--rail' : ''}`}
    onKeyDown={phone && section ? (event) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); changeSection(null) }
    } : undefined}>
    <GlassSurface className="gl-sidebar__surface">
      <div className="gl-sidebar__brand"><Icon name="stream" /><span>AetherStream</span></div>
      <nav aria-label="Main navigation" className={`gl-sidebar__nav${leaving ? ' gl-sidebar__nav--leaving' : ''}`} key={phone ? 'main' : section ?? 'main'}>
        {section && !phone ? options : destinations.filter(({ id }) => id !== 'pipelines' || PIPELINES_MODE !== 'hidden').map(({ id, label, icon }) => <button key={id} type="button" className={`gl-nav-item${screen === id || (phone && section === id) ? ' gl-nav-item--active' : ''}`} aria-label={label} aria-current={screen === id ? 'page' : undefined} aria-expanded={phone && (id === 'analytics' || id === 'settings') ? section === id : undefined} title={rail ? label : undefined}
          onClick={() => { if (id === 'analytics' || id === 'settings') { navigate('live'); changeSection(phone && section === id ? null : id) } else { if (phone) changeSection(null); navigate(id) } }}><Icon name={icon} /><span>{label}</span></button>)}
      </nav>
      {phone && section && <GlassSurface strong className="gl-sidebar__sheet">
        <nav aria-label={`${section === 'analytics' ? 'Analytics' : 'Settings'} options`} className="gl-sidebar__nav">{options}</nav>
      </GlassSurface>}
    </GlassSurface>
  </aside>
}
