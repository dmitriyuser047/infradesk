import { Link } from 'react-router-dom'

import type { IncidentResponse } from '../../types/incident'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { formatIncidentDateTime, shortIdentifier } from './incidentPresentation'

interface IncidentRowProps {
  organizationId: string
  incident: IncidentResponse
}

export function IncidentRow({ organizationId, incident }: IncidentRowProps) {
  return (
    <Link
      className="incident-row"
      to={`/organizations/${organizationId}/incidents/${incident.id}`}
    >
      <IncidentStatusBadge status={incident.status} />
      <div className="incident-row-main">
        <strong>Infrastructure incident</strong>
        <span>Opened {formatIncidentDateTime(incident.openedAt)}</span>
      </div>
      <span className="incident-resource-reference">Resource · {shortIdentifier(incident.resourceId)}</span>
    </Link>
  )
}
