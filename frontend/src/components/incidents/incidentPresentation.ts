import type { I18n } from '../../i18n'
import { secondsBetween } from '../../i18n/format'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { IncidentReason } from '../../types/incident'

export function getIncidentStatusLabel(status: string, i18n: I18n): string {
  return i18n.t.incidents.statuses[status] ?? status
}

/** NO_DATA and THRESHOLD read differently and carry different weight: blind versus broken. */
export function getIncidentReasonPresentation(reason: string, i18n: I18n): { label: string; tone: StatusTone } {
  const label = i18n.t.incidents.reasons[reason] ?? reason
  switch (reason) {
    case IncidentReason.threshold:
      return { label, tone: 'danger' }
    case IncidentReason.noData:
      return { label, tone: 'warning' }
    default:
      return { label, tone: 'neutral' }
  }
}

export function shortIdentifier(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}

/** How long an incident has been, or was, open. */
export function formatIncidentDuration(openedAt: string, resolvedAt: string | null, i18n: I18n, now = Date.now()): string {
  return i18n.format.duration(secondsBetween(openedAt, resolvedAt, now))
}
