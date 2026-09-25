import { EyeOff, TrendingUp } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { IncidentResponse } from '../../types/incident'
import type { ResourceResponse } from '../../types/resource'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { formatIncidentDuration, getIncidentReasonPresentation, shortIdentifier } from './incidentPresentation'

export function IncidentRow({ organizationId, incident, resource }: {
  organizationId: string
  incident: IncidentResponse
  resource?: ResourceResponse
}) {
  const i18n = useI18n()
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incident.id)}${location.search}`
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const open = incident.resolvedAt === null
  const resourceName = resource?.name ?? i18n.t.incidents.resourceRef(shortIdentifier(incident.resourceId))
  return <tr className={`clickable-row ${open ? '' : 'row-quiet'}`} onClick={() => navigate(destination)}>
    <td><IncidentStatusBadge status={incident.status} /></td>
    <td><StatusIndicator label={reason.label} tone={open ? reason.tone : 'neutral'}
      icon={incident.reason === 'NO_DATA' ? EyeOff : TrendingUp} /></td>
    <td><Link className="grid-link" to={destination}>{resourceName}</Link>
      {resource ? <small className="cell-secondary">{i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode}</small> : null}</td>
    <td><time dateTime={incident.openedAt} title={i18n.format.dateTime(incident.openedAt)}>{i18n.format.relative(incident.openedAt)}</time></td>
    <td>{open ? <span className="text-danger">{formatIncidentDuration(incident.openedAt, null, i18n)} · {i18n.t.incidents.ongoing}</span>
      : formatIncidentDuration(incident.openedAt, incident.resolvedAt, i18n)}</td>
  </tr>
}
