import { Link, useLocation, useNavigate } from 'react-router-dom'

import type { IncidentResponse } from '../../types/incident'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { formatIncidentDateTime, getIncidentReasonPresentation, shortIdentifier } from './incidentPresentation'

export function IncidentRow({ organizationId, incident }: {
  organizationId: string
  incident: IncidentResponse
}) {
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incident.id)}${location.search}`
  return <tr className="clickable-row" onClick={() => navigate(destination)}>
    <td><IncidentStatusBadge status={incident.status} /></td>
    <td><Link className="grid-link" to={destination}>Resource · {shortIdentifier(incident.resourceId)}</Link></td>
    <td><StatusIndicator label={getIncidentReasonPresentation(incident.reason).label}
      tone={getIncidentReasonPresentation(incident.reason).tone} /></td>
    <td>{formatIncidentDateTime(incident.openedAt)}</td>
    <td>{incident.resolvedAt ? formatIncidentDateTime(incident.resolvedAt) : <span className="muted-cell">—</span>}</td>
  </tr>
}
