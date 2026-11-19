/** City render-mode helpers for CityStage: presenter override (URL/localStorage) and software-WebGL detection. */
export const CITY_MODE_KEY = 'aetherstream.city-mode'
const SOFTWARE_RENDERER = /swiftshader|llvmpipe|softpipe|software|basic render|mesa offscreen/i
export type CityMode = 'live' | 'static'

export function storedMode(): CityMode | null {
  try {
    const fromUrl = new URLSearchParams(window.location.search).get('city')
    if (fromUrl === 'live' || fromUrl === 'static') return fromUrl
    const stored = window.localStorage.getItem(CITY_MODE_KEY)
    return stored === 'live' || stored === 'static' ? stored : null
  } catch {
    return null
  }
}

export function storeMode(mode: CityMode) {
  try { window.localStorage.setItem(CITY_MODE_KEY, mode) } catch { /* Storage can be unavailable. */ }
}

/** True when the browser's WebGL is a CPU rasteriser. Unknown (no WebGL, jsdom, blocked info) counts as hardware. */
export function hasSoftwareWebGL(): boolean {
  if (typeof window === 'undefined' || !window.WebGLRenderingContext) return false
  try {
    const canvas = document.createElement('canvas')
    const gl = (canvas.getContext('webgl2') ?? canvas.getContext('webgl')) as WebGLRenderingContext | null
    if (!gl) return false
    const info = gl.getExtension('WEBGL_debug_renderer_info')
    const renderer = String(gl.getParameter(info ? info.UNMASKED_RENDERER_WEBGL : gl.RENDERER) ?? '')
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    return SOFTWARE_RENDERER.test(renderer)
  } catch {
    return false
  }
}
