import { IncidentStatus } from '../../types/incident'
import { getIncidentStatusLabel } from './incidentPresentation'

export function IncidentStatusBadge({ status }: { status: string }) {
  const variant = status === IncidentStatus.open
    ? 'open'
    : status === IncidentStatus.resolved
      ? 'resolved'
      : 'unknown'

  return <span className={`incident-status incident-status-${variant}`}>{getIncidentStatusLabel(status)}</span>
}
