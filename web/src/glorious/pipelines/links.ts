export const EDITOR_URL = '/pipelines/editor'

/** Opens the full-page editor in a new tab. */
export function openEditor(query = '') {
  window.open(`${EDITOR_URL}${query}`, '_blank', 'noopener')
}
