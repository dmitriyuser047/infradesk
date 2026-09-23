import { Link, useLocation, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useIncident } from '../api/incidents'
import { useResource } from '../api/resources'
import { IncidentStatusBadge } from '../components/incidents/IncidentStatusBadge'
import { formatIncidentDateTime, formatIncidentDuration, shortIdentifier } from '../components/incidents/incidentPresentation'
import { AppShell } from '../components/layout/AppShell'
import { PropertyGrid, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { InvalidRoutePage } from './InvalidRoutePage'

export function IncidentPage() {
  const { organizationId, incidentId } = useParams()
  if (!organizationId || !incidentId) return <InvalidRoutePage />
  return <IncidentContent organizationId={organizationId} incidentId={incidentId} />
}

function IncidentContent({ organizationId, incidentId }: { organizationId: string; incidentId: string }) {
  const incidentQuery = useIncident(organizationId, incidentId)
  const location = useLocation()
  const resourceQuery = useResource(organizationId, incidentQuery.data?.resourceId)
  const back = `/organizations/${encodeURIComponent(organizationId)}/incidents${location.search}`
  if (incidentQuery.isPending) return <AppShell><div className="row-skeleton" aria-label="Loading incident"><span /><span /></div></AppShell>
  if (incidentQuery.isError || !incidentQuery.data) {
    const notFound = incidentQuery.error instanceof ApiError && incidentQuery.error.code === 'INCIDENT_NOT_FOUND'
    return <AppShell><div className="inline-error" role="alert">{notFound ? 'Incident not found' : 'Unable to load incident'}
      {!notFound ? <button className="text-button" onClick={() => incidentQuery.refetch()}>Retry</button> : null}</div></AppShell>
  }
  const incident = incidentQuery.data
  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title="Infrastructure incident" subtitle={`Incident · ${shortIdentifier(incident.id)}`}
      back={{ label: 'Incidents', to: back }} status={<IncidentStatusBadge status={incident.status} />} />
    <div className="workspace-split detail-split">
      <WorkspaceSection title="Overview"><PropertyGrid items={[
        { label: 'Status', value: <IncidentStatusBadge status={incident.status} /> },
        { label: 'Violation started', value: formatIncidentDateTime(incident.startedAt) },
        { label: 'Opened', value: formatIncidentDateTime(incident.openedAt) },
        { label: 'Resolved', value: incident.resolvedAt ? formatIncidentDateTime(incident.resolvedAt) : '—' },
        { label: 'Duration', value: formatIncidentDuration(incident.openedAt, incident.resolvedAt) },
      ]} /></WorkspaceSection>
      <WorkspaceSection title="Related objects"><PropertyGrid items={[
        { label: 'Resource', value: resourceQuery.data ? <Link to={`/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(resourceQuery.data.environmentId)}/resources/${encodeURIComponent(resourceQuery.data.id)}${location.search}`}>
          {resourceQuery.data.name}</Link> : resourceQuery.isPending ? 'Loading…' : shortIdentifier(incident.resourceId) },
        { label: 'Resource type', value: resourceQuery.data?.resourceTypeCode ?? '—' },
        { label: 'Monitor rule', value: shortIdentifier(incident.monitorRuleId) },
      ]} /></WorkspaceSection>
    </div>
  </div></AppShell>
}
