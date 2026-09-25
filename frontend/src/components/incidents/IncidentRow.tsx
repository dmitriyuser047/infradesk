import { Link, useLocation } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { getIncidentReasonPresentation, getIncidentTimePresentation } from './incidentPresentation'

/**
 * One incident: its status, the resource it is about and what happened to it, then when. The
 * resource name is the row's single link, to the incident; the whole row answers to it by pointer,
 * the keyboard reaches it as an ordinary link.
 */
export function IncidentRow({ organizationId, incident, now }: {
  organizationId: string
  incident: IncidentListItemResponse
  now: number
}) {
  const i18n = useI18n()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incident.id)}${location.search}`
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const time = getIncidentTimePresentation(incident, i18n, now)
  const open = incident.resolvedAt === null
  const resource = incident.resource
  const ReasonIcon = reason.Icon

  return <li className={`incident-row ${open ? 'incident-open' : 'incident-resolved'}`} data-reason={incident.reason}>
    <span className="incident-status"><IncidentStatusBadge status={incident.status} /></span>
    <div className="incident-main">
      <div className="incident-subject">
        <Link className="incident-link" to={destination} title={resource.name}>{resource.name}</Link>
        <span className="incident-type">{i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode}</span>
      </div>
      <span className={`incident-reason ${open ? `tone-${reason.tone}` : ''}`}>
        <ReasonIcon aria-hidden size={14} />{reason.label}</span>
    </div>
    <div className="incident-time">
      <time dateTime={time.at} title={i18n.format.dateTime(time.at)}>{time.label}</time>
      {time.duration !== null ? <span className="incident-duration">{time.duration}</span> : null}
    </div>
  </li>
}
