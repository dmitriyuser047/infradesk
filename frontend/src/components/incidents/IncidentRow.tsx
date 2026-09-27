import { Link, useLocation } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { incidentPath } from '../infrastructure/infrastructureLinks'
import { sourceSummary } from '../infrastructure/SourceConnections'
import { formatMonitorCondition } from '../monitoring/monitorRulePresentation'
import { IncidentStatusBadge } from './IncidentStatusBadge'
import { getIncidentReasonPresentation, getIncidentTimePresentation } from './incidentPresentation'

/** What a row repeats depends on where the list is: a resource's own list need not name it. */
export interface IncidentRowOptions {
  /** The resource name is the link; without it the rule condition is. Default true. */
  showResource?: boolean
  /** Environment and source connections under the reason. Default true. */
  showContext?: boolean
  /** The rule's condition next to the reason. Default false. */
  showCondition?: boolean
  /** The query the incident link carries; the current page's query by default. */
  linkQuery?: string
}

/**
 * One incident: its status, the resource it is about and what happened to it, then when. The
 * subject is the row's single link, to the incident; the whole row answers to it by pointer, the
 * keyboard reaches it as an ordinary link. Everything shown comes from the list response itself.
 */
export function IncidentRow({ organizationId, incident, now, options = {} }: {
  organizationId: string
  incident: IncidentListItemResponse
  now: number
  options?: IncidentRowOptions
}) {
  const i18n = useI18n()
  const location = useLocation()
  const { showResource = true, showContext = true, showCondition = false } = options
  const destination = incidentPath(organizationId, incident.id, options.linkQuery ?? location.search)
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const time = getIncidentTimePresentation(incident, i18n, now)
  const open = incident.resolvedAt === null
  const resource = incident.resource
  const condition = formatMonitorCondition(incident.monitorRule, i18n)
  const subject = showResource ? resource.name : condition
  const sources = sourceSummary(incident.sourceConnections, i18n.t.infrastructure.moreSources)
  const ReasonIcon = reason.Icon

  return <li className={`incident-row ${open ? 'incident-open' : 'incident-resolved'}`} data-reason={incident.reason}>
    <span className="incident-status"><IncidentStatusBadge status={incident.status} /></span>
    <div className="incident-main">
      <div className="incident-subject">
        {/* Several incidents of one resource must still be told apart by their link alone. */}
        <Link className="incident-link" to={destination} title={subject}
          aria-label={i18n.t.incidents.linkLabel(subject, reason.label, time.label)}>{subject}</Link>
        {showResource ? <span className="incident-type">{i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode}</span> : null}
      </div>
      <span className={`incident-reason ${open ? `tone-${reason.tone}` : ''}`}>
        <ReasonIcon aria-hidden size={14} />{reason.label}
        {showCondition && showResource ? <span className="incident-condition"> · {condition}</span> : null}</span>
      {showContext ? <span className="incident-context">
        {[incident.environment.name, sources].filter(Boolean).join(' · ')}</span> : null}
    </div>
    <div className="incident-time">
      <time dateTime={time.at} title={i18n.format.dateTime(time.at)}>{time.label}</time>
      {time.duration !== null ? <span className="incident-duration">{time.duration}</span> : null}
    </div>
  </li>
}
