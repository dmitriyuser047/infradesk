import type { I18n } from '../../i18n'
import { describeFailure } from '../../i18n/errors'
import { secondsBetween } from '../../i18n/format'
import {
  SyncStatus,
  type ConnectionScheduleResponse,
  type ConnectionScopeResponse,
  type SyncSessionResponse,
} from '../../types/connection'

export function getConnectorTypeLabel(code: string, i18n: I18n): string {
  return i18n.t.connections.connectorTypes[code] ?? code
}

export function getConnectionScopeLabel(scope: ConnectionScopeResponse, i18n: I18n): string {
  return i18n.t.connections.scopes[scope.type] ?? scope.type
}

export function getSyncStatusLabel(status: string, i18n: I18n): string {
  return i18n.t.connections.syncStatuses[status] ?? status
}

/** A failed session's reason by its code; the server's own sentence only where it is English. */
export function getSyncFailureMessage(session: Pick<SyncSessionResponse, 'errorCode' | 'errorMessage'>, i18n: I18n): string {
  return describeFailure(session.errorCode, session.errorMessage, i18n, i18n.t.connections.syncFailed)
}

export function formatScheduleInterval(intervalSeconds: number, i18n: I18n): string {
  if (!Number.isFinite(intervalSeconds) || intervalSeconds <= 0) {
    return '—'
  }

  const every = i18n.t.connections.every
  if (intervalSeconds % 3_600 === 0) {
    return every.hours(intervalSeconds / 3_600)
  }
  if (intervalSeconds % 60 === 0) {
    return every.minutes(intervalSeconds / 60)
  }
  return every.seconds(intervalSeconds)
}

export function getScheduleSummary(schedule: ConnectionScheduleResponse | null, i18n: I18n): string {
  if (schedule === null) {
    return i18n.t.connections.scheduleNotConfigured
  }
  return schedule.enabled ? formatScheduleInterval(schedule.intervalSeconds, i18n) : i18n.t.connections.scheduleDisabled
}

export function formatSyncDuration(startedAt: string, finishedAt: string | null, i18n: I18n): string {
  if (finishedAt === null) {
    return i18n.t.common.inProgress
  }
  return i18n.format.duration(secondsBetween(startedAt, finishedAt))
}

export function getLastSyncSummary(lastSync: SyncSessionResponse | null, i18n: I18n): string {
  if (lastSync === null) {
    return i18n.t.connections.neverSynchronized
  }
  if (lastSync.status === SyncStatus.running) {
    return i18n.t.connections.lastSyncStarted(i18n.format.dateTime(lastSync.startedAt))
  }

  return i18n.format.relative(lastSync.finishedAt ?? lastSync.startedAt)
}

export function shortConnectionIdentifier(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}
