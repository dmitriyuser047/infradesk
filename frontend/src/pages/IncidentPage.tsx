import { Link, useParams, useSearchParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useIncident } from '../api/incidents'
import { useAcknowledgeIncident } from '../api/maintenance'
import { useAvailableResourceOperations, useResourceOperationExecutions } from '../api/resourceOperations'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { IncidentStatusBadge } from '../components/incidents/IncidentStatusBadge'
import { formatIncidentDuration, getIncidentReasonPresentation } from '../components/incidents/incidentPresentation'
import {
  connectionPath, environmentPath, originQuery, projectPath, readOrigin, resourcePath, withTab, workspaceQuery,
} from '../components/infrastructure/infrastructureLinks'
import { SourceConnectionLinks } from '../components/infrastructure/SourceConnections'
import { isUnavailableError, RefreshWarning } from '../components/layout/RefreshWarning'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { formatMonitorCondition, formatNoDataTimeout, getMetricLabel } from '../components/monitoring/monitorRulePresentation'
import { operationsApplicability } from '../components/resources/operationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { IncidentReason, type IncidentListItemResponse } from '../types/incident'
import { InvalidRoutePage } from './InvalidRoutePage'

export function IncidentPage() {
  const { organizationId, incidentId } = useParams()
  if (!organizationId || !incidentId) return <InvalidRoutePage />
  return <IncidentContent organizationId={organizationId} incidentId={incidentId} />
}

/**
 * Where diagnosis starts: what went wrong, on which resource, where that resource lives and what
 * discovered it, then the way to the resource, its connection and its operations. One read brings
 * all of it; nothing is looked up per related object.
 */
function IncidentContent({ organizationId, incidentId }: { organizationId: string; incidentId: string }) {
  const i18n = useI18n()
  const t = i18n.t.incidents.page
  const [searchParams] = useSearchParams()
  const incidentQuery = useIncident(organizationId, incidentId)
  const resourceId = incidentQuery.data?.resourceId ?? ''
  // The same reads the resource page asks before offering its operations tab.
  const operations = operationsApplicability(
    useAvailableResourceOperations(organizationId, resourceId, incidentQuery.isSuccess),
    useResourceOperationExecutions(organizationId, resourceId, incidentQuery.isSuccess))
  const back = backLink(organizationId, searchParams, incidentQuery.data, i18n)
  const canAcknowledge = useOrganizationPermissions(organizationId).can('manageMonitoring')
  const acknowledge = useAcknowledgeIncident(organizationId, incidentId)
  if (incidentQuery.isPending) return <AppShell><PageLoading title={t.loading} back={back} label={t.loading} /></AppShell>
  if (!incidentQuery.data || isUnavailableError(incidentQuery.error)) {
    return <AppShell><PageUnavailable back={back} onRetry={() => incidentQuery.refetch()} error={incidentQuery.error}
      notFound={incidentQuery.error instanceof ApiError && incidentQuery.error.code === 'INCIDENT_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  }
  const incident = incidentQuery.data
  const reason = getIncidentReasonPresentation(incident.reason, i18n)
  const resource = incident.resource
  const typeLabel = i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode
  const rule = incident.monitorRule
  const metric = getMetricLabel(rule.metricCode, i18n)
  const title = incident.reason === IncidentReason.noData ? t.noDataTitle(metric)
    : incident.reason === IncidentReason.threshold ? t.thresholdTitle(metric) : reason.label
  // Links out of the incident remember it, so "back" on the resource or connection returns here.
  const fromIncident = originQuery(searchParams, { kind: 'incident', id: incident.id })
  const resourceLink = resourcePath(organizationId, incident.environment.id, resource.id, fromIncident)
  const sources = incident.sourceConnections
  const infra = i18n.t.infrastructure

  return <AppShell><div className="workspace-page work-page detail-page">
    <WorkspaceHeader title={title} subtitle={<>{t.subtitle(resource.name, typeLabel)} · <time dateTime={incident.openedAt}>{t.opened} {i18n.format.dateTime(incident.openedAt)}</time></>}
      back={back} status={<><IncidentStatusBadge status={incident.status} /><IncidentHandlingBadges incident={incident} /></>}
      actions={<>
        {/* Taking an open incident in hand is the first thing an on-duty person does here. */}
        {canAcknowledge && incident.resolvedAt === null && incident.acknowledgedAt === null ? <button className="primary-button"
          type="button" disabled={acknowledge.isPending} aria-busy={acknowledge.isPending} onClick={() => acknowledge.mutate()}>
          {t.acknowledge}</button> : null}
        <Link className={canAcknowledge && incident.resolvedAt === null && incident.acknowledgedAt === null
          ? 'secondary-button' : 'primary-button'} to={resourceLink}>{infra.openResource}</Link>
        {/* One source is the connection to open; several are listed below, none preferred. */}
        {sources.length === 1 ? <Link className="secondary-button" to={connectionPath(organizationId, sources[0].id, fromIncident)}>
          {infra.openConnection}</Link> : null}
        {/* Operations run from the resource page, with its confirmation; this only leads there. */}
        {operations === 'applicable' ? <Link className="secondary-button" to={resourcePath(organizationId, incident.environment.id,
          resource.id, withTab(fromIncident, 'operations'))}>{infra.goToOperations}</Link> : null}
      </>} />
    {incidentQuery.isError ? <RefreshWarning updatedAt={incidentQuery.dataUpdatedAt} retry={() => incidentQuery.refetch()} /> : null}
    {acknowledge.isError ? <InlineAlert tone="danger" title={t.acknowledgeFailed}>{describeError(acknowledge.error, i18n)}</InlineAlert> : null}
    {incident.notificationsSilenced ? <InlineAlert tone="info" title={t.silencedTitle}>{t.silencedDetail}</InlineAlert> : null}
    <div className="workspace-split detail-split">
      <WorkspaceSection title={t.overview}><PropertyGrid items={[
        { label: i18n.t.common.status, value: <IncidentStatusBadge status={incident.status} /> },
        { label: t.reason, value: <StatusIndicator label={reason.label} tone={reason.tone} /> },
        { label: t.trigger, value: formatMonitorCondition(rule, i18n) },
        ...(incident.reason === IncidentReason.noData ? [{ label: t.noDataAfter, value: formatNoDataTimeout(rule.noDataSeconds, i18n) }] : []),
        { label: t.violationStarted, value: i18n.format.dateTime(incident.startedAt) },
        { label: t.opened, value: i18n.format.dateTime(incident.openedAt) },
        { label: t.resolved, value: incident.resolvedAt ? i18n.format.dateTime(incident.resolvedAt) : '—' },
        { label: t.acknowledged, value: incident.acknowledgedAt
          ? t.acknowledgedBy(incident.acknowledgedByName ?? '—', i18n.format.dateTime(incident.acknowledgedAt)) : '—' },
        { label: t.duration, value: incident.resolvedAt ? formatIncidentDuration(incident.openedAt, incident.resolvedAt, i18n)
          : `${formatIncidentDuration(incident.openedAt, null, i18n)} · ${i18n.t.incidents.ongoing}` },
      ]} /></WorkspaceSection>
      <WorkspaceSection title={t.related}><PropertyGrid items={[
        { label: t.affectedResource, value: <Link className="property-link" to={resourceLink}
          aria-label={infra.resourceLink(resource.name)}>{resource.name} · {typeLabel}</Link> },
        ...(incident.parentResource ? [{ label: infra.parent, value: <Link className="property-link"
          to={resourcePath(organizationId, incident.environment.id, incident.parentResource.id, fromIncident)}>
          {incident.parentResource.name} · {i18n.t.resources.types[incident.parentResource.resourceTypeCode]
            ?? incident.parentResource.resourceTypeCode}</Link> }] : []),
        { label: sources.length > 1 ? infra.sources : infra.source,
          value: <SourceConnectionLinks organizationId={organizationId} sources={sources} linkQuery={fromIncident} /> },
        { label: infra.environment, value: <Link className="property-link"
          to={environmentPath(organizationId, incident.project.id, incident.environment.id)}>{incident.environment.name}</Link> },
        { label: infra.project, value: <Link className="property-link" to={projectPath(organizationId, incident.project.id)}>
          {incident.project.name}</Link> },
        // The rules of a resource live on its monitoring tab; that is the rule's page.
        { label: t.monitorRule, value: <Link className="property-link"
          to={resourcePath(organizationId, incident.environment.id, resource.id, withTab(fromIncident, 'monitoring'))}>
          {formatMonitorCondition(rule, i18n)}</Link> },
      ]} /></WorkspaceSection>
    </div>
  </div></AppShell>
}

/**
 * Back to the connection or resource the incident was opened from, otherwise to the incident list
 * with the filter the list had.
 */
function backLink(
  organizationId: string,
  params: URLSearchParams,
  incident: IncidentListItemResponse | undefined,
  i18n: ReturnType<typeof useI18n>,
): { label: string; to: string } {
  const origin = readOrigin(params)
  const workspace = workspaceQuery(params)
  if (origin?.kind === 'connection') {
    const source = incident?.sourceConnections.find(value => value.id === origin.id)
    return { label: source?.name ?? i18n.t.infrastructure.connection,
      to: connectionPath(organizationId, origin.id, withTab(workspace, 'incidents')) }
  }
  if (origin?.kind === 'resource') {
    return { label: incident?.resource.id === origin.id ? incident.resource.name : i18n.t.common.back,
      to: resourcePath(organizationId, origin.environmentId, origin.id, withTab(workspace, 'incidents')) }
  }
  const query = params.toString()
  return { label: i18n.t.incidents.page.back,
    to: `/organizations/${encodeURIComponent(organizationId)}/incidents${query ? `?${query}` : ''}` }
}

/** Whether someone took the incident in hand, and whether it was opened during maintenance. */
export function IncidentHandlingBadges({ incident }: { incident: IncidentListItemResponse }) {
  const i18n = useI18n()
  const t = i18n.t.incidents.page
  return <>
    {incident.acknowledgedAt !== null ? <StatusIndicator label={t.acknowledgedBadge} tone="info" size="small" /> : null}
    {incident.notificationsSilenced ? <StatusIndicator label={t.silencedBadge} tone="neutral" size="small" /> : null}
  </>
}
