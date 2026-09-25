import { CircleHelp, EyeOff, TrendingUp, type LucideIcon } from 'lucide-react'

import type { I18n } from '../../i18n'
import { secondsBetween } from '../../i18n/format'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { IncidentReason, IncidentStatus, type IncidentResponse } from '../../types/incident'

export function getIncidentStatusLabel(status: string, i18n: I18n): string {
  return i18n.t.incidents.statuses[status] ?? status
}

/** Open asks for attention, resolved is done; a status this frontend does not know stays neutral. */
export function getIncidentStatusTone(status: string): StatusTone {
  return status === IncidentStatus.open ? 'danger' : status === IncidentStatus.resolved ? 'success' : 'neutral'
}

/**
 * What an incident reason means to a person. The API codes stay as they are; this is the one place
 * that turns them into words, a tone and an icon. NO_DATA and THRESHOLD carry different weight:
 * blind versus broken. A reason this frontend does not know is shown as its code, neutral.
 */
export function getIncidentReasonPresentation(reason: string, i18n: I18n): { label: string; tone: StatusTone; Icon: LucideIcon } {
  const label = i18n.t.incidents.reasons[reason] ?? reason
  switch (reason) {
    case IncidentReason.threshold:
      return { label, tone: 'danger', Icon: TrendingUp }
    case IncidentReason.noData:
      return { label, tone: 'warning', Icon: EyeOff }
    default:
      return { label, tone: 'neutral', Icon: CircleHelp }
  }
}

export function shortIdentifier(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}

/** How long an incident has been, or was, open. */
export function formatIncidentDuration(openedAt: string, resolvedAt: string | null, i18n: I18n, now = Date.now()): string {
  return i18n.format.duration(secondsBetween(openedAt, resolvedAt, now))
}

export interface IncidentTimePresentation {
  /** The instant the line is about: when it opened, or when it was resolved. */
  at: string
  /** "Opened Sep 25, 14:32" or "Resolved Sep 25, 14:40". */
  label: string
  /** "ongoing for 8m" or "lasted 8m"; null when the timestamps do not give a duration. */
  duration: string | null
}

/**
 * When an incident happened and how long it lasts: an open one from its opening until `now`, a
 * resolved one from its opening to its resolution. Timestamps that are missing or out of order
 * give no duration instead of a wrong one.
 */
export function getIncidentTimePresentation(
  incident: Pick<IncidentResponse, 'openedAt' | 'resolvedAt'>,
  i18n: I18n,
  now = Date.now(),
): IncidentTimePresentation {
  const t = i18n.t.incidents
  const resolved = incident.resolvedAt !== null
  const seconds = secondsBetween(incident.openedAt, incident.resolvedAt, now)
  const duration = seconds === null ? null : i18n.format.duration(seconds)
  return resolved
    ? { at: incident.resolvedAt as string, label: t.resolvedAt(i18n.format.dateTime(incident.resolvedAt as string)),
      duration: duration === null ? null : t.lasted(duration) }
    : { at: incident.openedAt, label: t.openedAt(i18n.format.dateTime(incident.openedAt)),
      duration: duration === null ? null : t.lasting(duration) }
}
