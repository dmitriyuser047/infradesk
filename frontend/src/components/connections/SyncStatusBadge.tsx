import { SyncStatus } from '../../types/connection'
import { getSyncStatusLabel } from './connectionPresentation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function SyncStatusBadge({ status }: { status: string }) {
  const variant = status === SyncStatus.completed
    ? 'completed'
    : status === SyncStatus.failed
      ? 'failed'
      : status === SyncStatus.running
        ? 'running'
        : 'unknown'

  return <StatusIndicator label={getSyncStatusLabel(status)}
    tone={variant === 'completed' ? 'success' : variant === 'failed' ? 'danger' : variant === 'running' ? 'info' : 'neutral'} />
}
