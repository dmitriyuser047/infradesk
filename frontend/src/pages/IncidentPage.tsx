import { Link, useLocation, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useIncident } from '../api/incidents'
import { useResource } from '../api/resources'
import { IncidentStatusBadge } from '../components/incidents/IncidentStatusBadge'
import { formatIncidentDuration, getIncidentReasonPresentation, shortIdentifier } from '../components/incidents/incidentPresentation'
import { AppShell } from '../components/layout/AppShell'
import { PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { InvalidRoutePage } from './InvalidRoutePage'

export function IncidentPage() {
  const { organizationId, incidentId } = useParams()
  if (!organizationId || !incidentId) return <InvalidRoutePage />
  return <IncidentContent organizationId={organizationId} incidentId={incidentId} />
}

function IncidentContent({ organizationId, incidentId }: { organizationId: string; incidentId: string }) {
  const i18n = useI18n()
  const t = i18n.t.incidents.page
  const incidentQuery = useIncident(organizationId, incidentId)
  const location = useLocation()
  const resourceQuery = useResource(organizationId, incidentQuery.data?.resourceId)
  const back = `/organizations/${encodeURIComponent(organizationId)}/incidents${location.search}`
  if (incidentQuery.isPending) return <AppShell><PageLoading title={t.loading} back={{ label: t.back, to: back }} label={t.loading} /></AppShell>
  if (incidentQuery.isError || !incidentQuery.data) {
    return <AppShell><PageUnavailable back={{ label: t.back, to: back }} onRetry={() => incidentQuery.refetch()} error={incidentQuery.error}
      notFound={incidentQuery.error instanceof ApiError && incidentQuery.error.code === 'INCIDENT_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  }
  const incident = incidentQuery.data
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const resource = resourceQuery.data
  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={resource ? `${reason.label} · ${resource.name}` : t.title}
      subtitle={t.subtitle(shortIdentifier(incident.id))}
      back={{ label: t.back, to: back }} status={<IncidentStatusBadge status={incident.status} />} />
    <div className="workspace-split detail-split">
      <WorkspaceSection title={t.overview}><PropertyGrid items={[
        { label: i18n.t.common.status, value: <IncidentStatusBadge status={incident.status} /> },
        { label: t.reason, value: <StatusIndicator label={reason.label} tone={reason.tone} /> },
        { label: t.violationStarted, value: i18n.format.dateTime(incident.startedAt) },
        { label: t.opened, value: i18n.format.dateTime(incident.openedAt) },
        { label: t.resolved, value: incident.resolvedAt ? i18n.format.dateTime(incident.resolvedAt) : '—' },
        { label: t.duration, value: incident.resolvedAt ? formatIncidentDuration(incident.openedAt, incident.resolvedAt, i18n)
          : `${formatIncidentDuration(incident.openedAt, null, i18n)} · ${i18n.t.incidents.ongoing}` },
      ]} /></WorkspaceSection>
      <WorkspaceSection title={t.related}><PropertyGrid items={[
        { label: t.resource, value: resource ? <Link className="property-link" to={`/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(resource.environmentId)}/resources/${encodeURIComponent(resource.id)}${location.search}`}>
          {resource.name}</Link> : resourceQuery.isPending ? i18n.t.common.loading : shortIdentifier(incident.resourceId) },
        { label: t.resourceType, value: resource ? i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode : '—' },
        { label: t.monitorRule, value: shortIdentifier(incident.monitorRuleId) },
      ]} /></WorkspaceSection>
    </div>
  </div></AppShell>
}
