import { useI18n } from '../../i18n'
import { IncidentStatus } from '../../types/incident'
import { getIncidentStatusLabel } from './incidentPresentation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function IncidentStatusBadge({ status }: { status: string }) {
  const i18n = useI18n()
  const tone = status === IncidentStatus.open ? 'danger' : status === IncidentStatus.resolved ? 'success' : 'neutral'
  return <StatusIndicator label={getIncidentStatusLabel(status, i18n)} tone={tone} />
}
