import { useI18n } from '../../i18n'
import { SyncStatus } from '../../types/connection'
import { getSyncStatusLabel } from './connectionPresentation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function SyncStatusBadge({ status }: { status: string }) {
  const i18n = useI18n()
  const tone = status === SyncStatus.completed ? 'success'
    : status === SyncStatus.failed ? 'danger'
      : status === SyncStatus.running ? 'info' : 'neutral'
  return <StatusIndicator label={getSyncStatusLabel(status, i18n)} tone={tone} />
}
