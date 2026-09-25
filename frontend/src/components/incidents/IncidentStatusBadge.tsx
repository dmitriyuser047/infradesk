import { useI18n } from '../../i18n'
import { getIncidentStatusLabel, getIncidentStatusTone } from './incidentPresentation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function IncidentStatusBadge({ status }: { status: string }) {
  const i18n = useI18n()
  return <StatusIndicator label={getIncidentStatusLabel(status, i18n)} tone={getIncidentStatusTone(status)} />
}
