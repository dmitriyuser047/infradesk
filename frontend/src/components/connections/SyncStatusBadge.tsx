import { SyncStatus } from '../../types/connection'
import { getSyncStatusLabel } from './connectionPresentation'

export function SyncStatusBadge({ status }: { status: string }) {
  const variant = status === SyncStatus.completed
    ? 'completed'
    : status === SyncStatus.failed
      ? 'failed'
      : status === SyncStatus.running
        ? 'running'
        : 'unknown'

  return <span className={`sync-status sync-status-${variant}`}>{getSyncStatusLabel(status)}</span>
}
