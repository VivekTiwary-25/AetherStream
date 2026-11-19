import { createContext, useContext, type ReactNode } from 'react'

export interface WindowGeometry { x: number; y: number; w: number; h: number }
export interface WindowOptions {
  title: string
  render: () => ReactNode
  initial?: WindowGeometry
  strong?: boolean
  onBack?: () => void
}
export interface WindowsApi {
  open: (id: string, options: WindowOptions) => void
  close: (id: string) => void
  isOpen: (id: string) => boolean
  /** Changes header/content without remounting the window or resetting its geometry. */
  update: (id: string, options: Partial<Pick<WindowOptions, 'title' | 'onBack' | 'render'>>) => void
}
export const WindowsContext = createContext<WindowsApi | null>(null)
export function useWindows(): WindowsApi {
  const value = useContext(WindowsContext)
  if (!value) throw new Error('useWindows requires WindowManager')
  return value
}
