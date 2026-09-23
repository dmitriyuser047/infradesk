import { Link, useLocation, useNavigate } from 'react-router-dom'

import type { IncidentResponse } from '../../types/incident'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { formatIncidentDateTime, shortIdentifier } from './incidentPresentation'

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
    <td>{formatIncidentDateTime(incident.openedAt)}</td>
    <td>{incident.resolvedAt ? formatIncidentDateTime(incident.resolvedAt) : <span className="muted-cell">—</span>}</td>
  </tr>
}
