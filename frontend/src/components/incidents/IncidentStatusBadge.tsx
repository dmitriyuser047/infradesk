import { IncidentStatus } from '../../types/incident'
import { getIncidentStatusLabel } from './incidentPresentation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function IncidentStatusBadge({ status }: { status: string }) {
  const variant = status === IncidentStatus.open
    ? 'open'
    : status === IncidentStatus.resolved
      ? 'resolved'
      : 'unknown'

  return <StatusIndicator label={getIncidentStatusLabel(status)}
    tone={variant === 'open' ? 'danger' : variant === 'resolved' ? 'success' : 'neutral'} />
}
