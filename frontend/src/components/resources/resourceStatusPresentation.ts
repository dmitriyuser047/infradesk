import type { StatusTone } from '../layout/WorkspacePrimitives'

export function containerStatusPresentation(state: string | null | undefined): { label: string; tone: StatusTone } {
  const normalized = state?.trim().toLowerCase()
  switch (normalized) {
    case 'running': return { label: 'Running', tone: 'success' }
    case 'exited': return { label: 'Exited', tone: 'danger' }
    case 'dead': return { label: 'Dead', tone: 'danger' }
    case 'paused': return { label: 'Paused', tone: 'warning' }
    case 'created': return { label: 'Created', tone: 'info' }
    default: return { label: state?.trim() || 'Unknown', tone: 'neutral' }
  }
}
