import type { I18n } from '../../../i18n'
import type { StatusTone } from '../../layout/WorkspacePrimitives'

const tones: Record<string, StatusTone> = {
  running: 'success', exited: 'danger', dead: 'danger', paused: 'warning', created: 'info', restarting: 'info',
}

/** The Docker state inventory stored, in words; a state this frontend does not know is shown as is. */
export function containerStatusPresentation(state: string | null | undefined, i18n: I18n): { label: string; tone: StatusTone } {
  const normalized = state?.trim().toLowerCase() ?? ''
  const label = i18n.t.resources.container.states[normalized]
  return label === undefined
    ? { label: state?.trim() || i18n.t.common.unknown, tone: 'neutral' }
    : { label, tone: tones[normalized] ?? 'neutral' }
}
