import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { IncidentResponse } from '../../types/incident'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { formatIncidentDuration, getIncidentReasonPresentation, shortIdentifier } from './incidentPresentation'

export function IncidentRow({ organizationId, incident }: {
  organizationId: string
  incident: IncidentResponse
}) {
  const i18n = useI18n()
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incident.id)}${location.search}`
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const open = incident.resolvedAt === null
  return <tr className={`clickable-row ${open ? '' : 'row-quiet'}`} onClick={() => navigate(destination)}>
    <td><IncidentStatusBadge status={incident.status} /></td>
    <td><Link className="grid-link" to={destination}>{i18n.t.incidents.resourceRef(shortIdentifier(incident.resourceId))}</Link></td>
    <td><StatusIndicator label={reason.label} tone={reason.tone} /></td>
    <td><time dateTime={incident.openedAt} title={i18n.format.dateTime(incident.openedAt)}>{i18n.format.relative(incident.openedAt)}</time></td>
    <td>{open ? <span>{formatIncidentDuration(incident.openedAt, null, i18n)} · {i18n.t.incidents.ongoing}</span>
      : formatIncidentDuration(incident.openedAt, incident.resolvedAt, i18n)}</td>
  </tr>
}
