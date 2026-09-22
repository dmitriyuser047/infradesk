import {
  ConnectionScopeType,
  SyncStatus,
  type ConnectionScheduleResponse,
  type ConnectionScopeResponse,
  type SyncSessionResponse,
} from '../../types/connection'

export function getConnectorTypeLabel(code: string): string {
  switch (code) {
    case 'SSH':
      return 'SSH'
    case 'DOCKER':
      return 'Docker'
    default:
      return code
  }
}

export function getConnectionScopeLabel(scope: ConnectionScopeResponse): string {
  switch (scope.type) {
    case ConnectionScopeType.organization:
      return 'Organization'
    case ConnectionScopeType.project:
      return 'Project'
    case ConnectionScopeType.environment:
      return 'Environment'
  }
}

export function getSyncStatusLabel(status: string): string {
  switch (status) {
    case SyncStatus.running:
      return 'Running'
    case SyncStatus.completed:
      return 'Completed'
    case SyncStatus.failed:
      return 'Failed'
    default:
      return status
  }
}

export function formatScheduleInterval(intervalSeconds: number): string {
  if (!Number.isFinite(intervalSeconds) || intervalSeconds <= 0) {
    return '—'
  }

  if (intervalSeconds % 3_600 === 0) {
    return `Every ${intervalSeconds / 3_600}h`
  }
  if (intervalSeconds % 60 === 0) {
    return `Every ${intervalSeconds / 60}m`
  }
  return `Every ${intervalSeconds}s`
}

export function getScheduleSummary(schedule: ConnectionScheduleResponse | null): string {
  if (schedule === null) {
    return 'Not configured'
  }
  return schedule.enabled ? formatScheduleInterval(schedule.intervalSeconds) : 'Disabled'
}

export function formatConnectionDateTime(iso: string): string {
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

export function formatSyncDuration(startedAt: string, finishedAt: string | null): string {
  if (finishedAt === null) {
    return 'In progress'
  }

  const started = new Date(startedAt).getTime()
  const finished = new Date(finishedAt).getTime()
  if (!Number.isFinite(started) || !Number.isFinite(finished) || finished < started) {
    return '—'
  }

  const seconds = Math.floor((finished - started) / 1_000)
  const hours = Math.floor(seconds / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  const remainderSeconds = seconds % 60
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  if (minutes > 0) {
    return `${minutes}m ${remainderSeconds}s`
  }
  return `${remainderSeconds}s`
}

export function getLastSyncSummary(lastSync: SyncSessionResponse | null): string {
  if (lastSync === null) {
    return 'Never synchronized'
  }
  if (lastSync.status === SyncStatus.running) {
    return `Started ${formatConnectionDateTime(lastSync.startedAt)}`
  }

  return formatConnectionDateTime(lastSync.finishedAt ?? lastSync.startedAt)
}

export function shortConnectionIdentifier(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}
