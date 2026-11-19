import type { ReactNode } from 'react'
import type { WindowsApi } from './windows'

export interface SidebarItem {
  id: string
  label: string
  icon?: ReactNode
  onSelect: (windows: WindowsApi) => void
}
export type SidebarSection = 'analytics' | 'settings'
