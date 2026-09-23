import type { StatusTone } from '../layout/WorkspacePrimitives'
import { IncidentReason, IncidentStatus } from '../../types/incident'

export function getIncidentStatusLabel(status: string): string {
  switch (status) {
    case IncidentStatus.open:
      return 'Open'
    case IncidentStatus.resolved:
      return 'Resolved'
    default:
      return status
  }
}

export function getIncidentReasonPresentation(reason: string): { label: string; tone: StatusTone } {
  switch (reason) {
    case IncidentReason.threshold:
      return { label: 'Threshold violation', tone: 'danger' }
    case IncidentReason.noData:
      return { label: 'No data', tone: 'warning' }
    default:
      return { label: reason, tone: 'neutral' }
  }
}

export function shortIdentifier(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}

export function formatIncidentDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }

  return new Intl.DateTimeFormat(undefined, {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function formatIncidentDuration(startedAt: string, resolvedAt: string | null): string {
  if (resolvedAt === null) {
    return 'Ongoing'
  }

  const start = new Date(startedAt).getTime()
  const end = new Date(resolvedAt).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
    return '—'
  }

  const totalSeconds = Math.floor((end - start) / 1_000)
  const days = Math.floor(totalSeconds / 86_400)
  const hours = Math.floor((totalSeconds % 86_400) / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60

  if (days > 0) {
    return `${days}d ${hours}h`
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  return `${minutes}m ${seconds}s`
}
