import { Link, useParams } from 'react-router-dom'
import { AlertTriangle } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { useIncident } from '../api/incidents'
import { useResource } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import { IncidentStatusBadge } from '../components/incidents/IncidentStatusBadge'
import {
  formatIncidentDateTime,
  formatIncidentDuration,
  shortIdentifier,
} from '../components/incidents/incidentPresentation'
import type { IncidentResponse } from '../types/incident'
import { InvalidRoutePage } from './InvalidRoutePage'

export function IncidentPage() {
  const { organizationId, incidentId } = useParams()

  if (organizationId === undefined || incidentId === undefined) {
    return <InvalidRoutePage />
  }

  return <IncidentContent organizationId={organizationId} incidentId={incidentId} />
}

function IncidentContent({ organizationId, incidentId }: { organizationId: string; incidentId: string }) {
  const incidentQuery = useIncident(organizationId, incidentId)
  const resourceQuery = useResource(organizationId, incidentQuery.data?.resourceId)
  const incidentsPath = `/organizations/${organizationId}/incidents`

  if (incidentQuery.isPending) {
    return <AppShell><div className="detail-skeleton" aria-label="Loading incident"><span /><span /><span /></div></AppShell>
  }

  if (incidentQuery.isError || incidentQuery.data === undefined) {
    const notFound = incidentQuery.error instanceof ApiError && incidentQuery.error.code === 'INCIDENT_NOT_FOUND'
    return (
      <AppShell>
        <section className="content-panel incident-state" role="alert">
          <h1>{notFound ? 'Incident not found' : 'Unable to load incident'}</h1>
          <p>{notFound ? 'This incident is unavailable in the current organization.' : safeErrorMessage(incidentQuery.error)}</p>
          <div className="state-actions">
            <Link className="back-link" to={incidentsPath}>Back to incidents</Link>
            {!notFound ? <button className="retry-button" type="button" onClick={() => incidentQuery.refetch()}>Retry</button> : null}
          </div>
        </section>
      </AppShell>
    )
  }

  const incident = incidentQuery.data

  return (
    <AppShell>
      <div className="detail-page incident-detail-page">
        <Link className="back-link" to={incidentsPath}>← Back to incidents</Link>
        <header className="resource-header incident-header">
          <div className="resource-header-icon incident-header-icon"><AlertTriangle aria-hidden size={23} /></div>
          <div>
            <p className="eyebrow">Incident</p>
            <h1>Infrastructure incident</h1>
            <p className="page-subtitle">{shortIdentifier(incident.id)}</p>
          </div>
          <IncidentStatusBadge status={incident.status} />
        </header>
        <IncidentLifecycle incident={incident} />
        <IncidentResourceSection incident={incident} resourceQuery={resourceQuery} organizationId={organizationId} />
        <section className="content-panel incident-reference" aria-labelledby="monitor-rule-reference-heading">
          <p className="eyebrow">Reference</p>
          <h2 id="monitor-rule-reference-heading">Monitor rule</h2>
          <p className="reference-id">{shortIdentifier(incident.monitorRuleId)}</p>
        </section>
      </div>
    </AppShell>
  )
}

function IncidentLifecycle({ incident }: { incident: IncidentResponse }) {
  return (
    <section className="content-panel incident-lifecycle" aria-labelledby="incident-lifecycle-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Timeline</p>
          <h2 id="incident-lifecycle-heading">Lifecycle</h2>
        </div>
      </div>
      <dl className="lifecycle-grid">
        <LifecycleItem label="Violation started" value={formatIncidentDateTime(incident.startedAt)} />
        <LifecycleItem label="Incident opened" value={formatIncidentDateTime(incident.openedAt)} />
        <LifecycleItem label="Resolved" value={incident.resolvedAt === null ? '—' : formatIncidentDateTime(incident.resolvedAt)} />
        <LifecycleItem label="Duration" value={formatIncidentDuration(incident.openedAt, incident.resolvedAt)} />
      </dl>
    </section>
  )
}

function IncidentResourceSection({
  incident,
  resourceQuery,
  organizationId,
}: {
  incident: IncidentResponse
  resourceQuery: ReturnType<typeof useResource>
  organizationId: string
}) {
  return (
    <section className="content-panel incident-resource-section" aria-labelledby="incident-resource-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Affected object</p>
          <h2 id="incident-resource-heading">Resource</h2>
        </div>
      </div>
      {resourceQuery.isPending ? <p className="reference-id">Loading resource details…</p> : null}
      {resourceQuery.isError ? (
        <div className="incident-resource-fallback">
          <p>Unable to load resource details</p>
          <span>Resource · {shortIdentifier(incident.resourceId)}</span>
        </div>
      ) : null}
      {resourceQuery.data !== undefined ? (
        <div className="incident-resource-loaded">
          <strong>{resourceQuery.data.name}</strong>
          <span>{resourceQuery.data.resourceTypeCode} · {resourceQuery.data.code}</span>
          <Link className="back-link" to={`/organizations/${organizationId}/environments/${resourceQuery.data.environmentId}/resources/${resourceQuery.data.id}`}>
            View resource →
          </Link>
        </div>
      ) : null}
    </section>
  )
}

function LifecycleItem({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>
}

function safeErrorMessage(error: Error | null): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}
