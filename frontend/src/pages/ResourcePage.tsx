import { useState } from 'react'
import type { ComponentType } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { RefreshCw, Activity, CircleAlert, Cable, Layers } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { useResourceContext } from '../api/infrastructure'
import { createLastHourWindow, useResourceMetrics } from '../api/metrics'
import { useResource } from '../api/resources'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { ConfigurationAssignmentList } from '../components/configuration/ConfigurationAssignmentList'
import { ResourceLabelsSection } from '../components/configuration/ResourceLabelsSection'
import { supportsConfigurationAssignment } from '../components/configuration/configurationTargetSupport'
import { isUnavailableError, RefreshWarning } from '../components/layout/RefreshWarning'
import { AppShell } from '../components/layout/AppShell'
import {
  InlineAlert, PageLoading, PageUnavailable, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs, WorkspaceMetrics,
} from '../components/layout/WorkspacePrimitives'
import { useAvailableResourceOperations, useResourceOperationExecutions } from '../api/resourceOperations'
import { operationsApplicability } from '../components/resources/operationPresentation'
import { MetricChart } from '../components/metrics/MetricChart'
import { filterMetricSeries } from '../components/metrics/metricSeries'
import { ResourceActivitySection } from '../components/history/ResourceActivitySection'
import { InfrastructureContextPath, resourceContextPath } from '../components/infrastructure/InfrastructureContextPath'
import {
  connectionPath, environmentPath, originPath, originQuery, readOrigin, withTab, workspaceQuery,
} from '../components/infrastructure/infrastructureLinks'
import { asTree } from '../components/infrastructure/resourceGroups'
import { ScopedIncidentsPanel } from '../components/infrastructure/ScopedIncidentsPanel'
import { SourceConnectionLinks } from '../components/infrastructure/SourceConnections'
import { MonitorRulesSection } from '../components/monitoring/MonitorRulesSection'
import { supportsResourceMonitoring } from '../components/monitoring/resourceMonitoringSupport'
import { resourceChildrenTitle, resourceDisplayName, resourceTerminalConnection } from '../components/resources/resourceInventoryPresentation'
import { ResourceTree } from '../components/resources/ResourceTree'
import { resourcePresentationRegistry } from '../components/resources/presentation/resourcePresentations'
import type { ResourcePresentationProps } from '../components/resources/presentation/ResourcePresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { ResourceContextResponse } from '../types/infrastructure'
import { MetricCode, type MetricObservationResponse } from '../types/metric'
import type { ResourceResponse } from '../types/resource'
import { ResourceOperationsPanel } from '../components/resources/ResourceOperationsPanel'
import { ResourceIntegrationSection } from '../components/integrations/ResourceIntegrationSection'
import { ProvisioningPanel } from '../components/resources/ProvisioningPanel'
import { ServerProfileAutomationPanel } from '../components/resources/ServerProfileAutomationPanel'
import { supportsProvisioning } from '../components/resources/provisioningSupport'
import { supportsIntegrationBinding } from '../components/integrations/integrationPresentation'
import { InvalidRoutePage } from './InvalidRoutePage'

const Tabs = ['overview', 'monitoring', 'incidents', 'activity', 'configurations', 'operations'] as const
type Tab = typeof Tabs[number]

export function ResourcePage() {
  const { organizationId, environmentId, resourceId } = useParams()
  if (!organizationId || !environmentId || !resourceId) return <InvalidRoutePage />
  return <ResourceContent key={`${organizationId}:${environmentId}:${resourceId}`} organizationId={organizationId} environmentId={environmentId} resourceId={resourceId} />
}

/**
 * One resource: its name and status first, where it lives, then tabs for what applies to it. How it
 * looks comes from its presentation; whether it is monitored comes from the monitoring feature and
 * whether it has operations from the backend — three separate answers, never derived from each
 * other. Its place in the infrastructure is its own read, so a direct link needs no list in cache,
 * and a failure of that read leaves the resource itself usable.
 */
function ResourceContent({ organizationId, environmentId, resourceId }: {
  organizationId: string; environmentId: string; resourceId: string
}) {
  const i18n = useI18n()
  const t = i18n.t.resources.page
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedTab = Tabs.find(value => value === searchParams.get('tab')) ?? 'overview'
  const [metricWindow, setMetricWindow] = useState(() => createLastHourWindow())
  const [focusedRunId, setFocusedRunId] = useState<string | null>(null)
  const resourceQuery = useResource(organizationId, resourceId)
  const contextQuery = useResourceContext(organizationId, resourceId)
  const context = isUnavailableError(contextQuery.error) ? undefined : contextQuery.data
  const presentation = resourcePresentationRegistry.resolve(resourceQuery.data?.resourceTypeCode ?? '')
  const monitored = supportsResourceMonitoring(resourceQuery.data?.resourceTypeCode)
  // Desired configuration is for nodes only for now, and for those who may manage it.
  const permissions = useOrganizationPermissions(organizationId)
  const configurable = supportsConfigurationAssignment(resourceQuery.data?.resourceTypeCode)
    && permissions.can('manageConfigurations')
  // The same two reads the operations tab shows; asking them here only decides whether it exists,
  // and only once the resource itself is known to exist.
  const operations = operationsApplicability(
    useAvailableResourceOperations(organizationId, resourceId, resourceQuery.isSuccess),
    useResourceOperationExecutions(organizationId, resourceId, resourceQuery.isSuccess))
  const metricsQuery = useResourceMetrics(organizationId, resourceId, metricWindow,
    monitored && requestedTab === 'monitoring')
  const back = backLink(organizationId, environmentId, searchParams, context, i18n)
  const linkQuery = originQuery(searchParams, { kind: 'resource', id: resourceId, environmentId })
  const selectTab = (next: Tab) => setSearchParams(previous => {
    const updated = new URLSearchParams(previous)
    if (next === 'overview') updated.delete('tab')
    else updated.set('tab', next)
    return updated
  }, { replace: true })

  if (resourceQuery.isPending) return <AppShell><PageLoading title={t.loading} back={back} label={t.loading} /></AppShell>
  if (!resourceQuery.data || isUnavailableError(resourceQuery.error)) {
    return <AppShell><PageUnavailable back={back} onRetry={() => resourceQuery.refetch()} error={resourceQuery.error}
      notFound={resourceQuery.error instanceof ApiError && resourceQuery.error.code === 'RESOURCE_NOT_FOUND'}
      notFoundTitle={t.notFound} notFoundDetail={t.notFoundDetail} errorTitle={t.loadError} /></AppShell>
  }
  const resource = resourceQuery.data
  const resourceName = resourceDisplayName(resource)
  const terminalConnection = permissions.can('openTerminal') ? resourceTerminalConnection(resource, context?.sourceConnections ?? []) : undefined
  const terminalQuery = new URLSearchParams(linkQuery)
  terminalQuery.set('environment', environmentId)
  if (context?.project.id) terminalQuery.set('project', context.project.id)
  // Incidents come from monitor rules, so they belong where monitoring does; whether the resource
  // has operations says nothing about either.
  const tabs: { id: Tab; label: string; count?: number }[] = [
    { id: 'overview', label: t.tabs.overview },
    ...(monitored ? [{ id: 'monitoring' as const, label: t.tabs.monitoring }] : []),
    ...(monitored ? [{ id: 'incidents' as const, label: t.tabs.incidents, count: context?.openIncidentCount || undefined }] : []),
    { id: 'activity', label: t.tabs.activity },
    ...(configurable ? [{ id: 'configurations' as const, label: t.tabs.configurations }] : []),
    ...(operations === 'applicable' ? [{ id: 'operations' as const, label: t.tabs.operations }] : []),
  ]
  // A tab that does not apply (the operations answer changed, or a stale link) falls back to the overview.
  const active = tabs.some(item => item.id === requestedTab) ? requestedTab : 'overview'
  const status = presentation.headerStatus?.(resource, i18n)
  const Overview = presentation.Overview
  const typeLabel = i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode

  return <AppShell><div className="workspace-page resource-page work-page detail-page">
    {context ? <InfrastructureContextPath items={resourceContextPath(organizationId, context, [{ label: resourceName }], i18n.t.shell.nav, linkQuery)} /> : null}
    <WorkspaceHeader title={resourceName} subtitle={presentation.headerSubtitle?.(resource, i18n) ?? t.subtitle(typeLabel, resource.code)}
      back={context?.parentResource && !readOrigin(searchParams) ? undefined : back}
      status={<>{status ? <StatusIndicator label={status.label} tone={status.tone} /> : null}
        {!resource.active ? <StatusIndicator label={t.inactive} tone="neutral" /> : null}</>}
      actions={<>
        {terminalConnection ? <Link className="secondary-button"
          to={connectionPath(organizationId, terminalConnection.id, withTab(`?${terminalQuery.toString()}`, 'terminal'))}>{i18n.t.workScreens.openTerminal}</Link> : null}
        {context && context.sourceConnections.length > 0
        // Sources are related entities, independent of the breadcrumb hierarchy.
        ? <details className="toolbar-overflow source-disclosure">
          <summary>{i18n.t.infrastructure.sourcesCount(context.sourceConnections.length)}</summary>
          <SourceConnectionLinks organizationId={organizationId} sources={context.sourceConnections} linkQuery={linkQuery} />
        </details> : null}</>} />
    {resourceQuery.isError ? <RefreshWarning updatedAt={resourceQuery.dataUpdatedAt} retry={() => resourceQuery.refetch()} /> : null}
    <WorkspaceMetrics items={[
      { label: i18n.t.design.observedState, value: status?.label ?? (resource.active ? i18n.t.common.active : t.inactive),
        icon: Activity, tone: status?.tone, detail: typeLabel },
      { label: i18n.t.infrastructure.openIncidents, value: context?.openIncidentCount ?? '—', icon: CircleAlert,
        tone: (context?.openIncidentCount ?? 0) > 0 ? 'warning' : 'neutral',
        ...(monitored ? { onSelect: () => selectTab('incidents') } : {}) },
      { label: i18n.t.design.sourceConnections, value: context?.sourceConnections.length ?? '—', icon: Cable, detail: i18n.t.design.discoverySources },
      { label: i18n.t.design.childResources, value: context?.activeChildCount ?? '—', icon: Layers, detail: i18n.t.design.observedObjects },
    ]} />
    <WorkspaceTabs tabs={tabs} active={active} onChange={selectTab} />
    {active === 'overview' && monitored && context && context.openIncidentCount > 0 ? <InlineAlert tone="warning" title={i18n.t.infrastructure.openIncidents}
      action={<button className="secondary-button" type="button" onClick={() => selectTab('incidents')}>{i18n.t.infrastructure.viewAllIncidents}</button>}>
      {i18n.t.infrastructure.openIncidentCount(context.openIncidentCount)}
    </InlineAlert> : null}
    <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
      {active === 'overview' ? <div className="resource-overview">
        <Overview resource={resource} />
        {supportsProvisioning(resource.resourceTypeCode) ? <ServerProfileAutomationPanel key={`profile:${organizationId}:${resourceId}`}
          organizationId={organizationId} resourceId={resourceId} resourceName={resourceName} resourceActive={resource.active} onRunQueued={setFocusedRunId} /> : null}
        {supportsProvisioning(resource.resourceTypeCode) ? <ProvisioningPanel key={`${organizationId}:${resourceId}`}
          organizationId={organizationId} resourceId={resourceId} resourceName={resourceName}
          canRun={permissions.can('manageConfigurations') && permissions.can('executeOperations')}
          focusRunId={focusedRunId} onRunQueued={setFocusedRunId} /> : null}
        {/* A manual Remnawave binding, for those who manage integrations; others never request it. */}
        {supportsIntegrationBinding(resource.resourceTypeCode) && permissions.can('manageIntegrations')
          ? <ResourceIntegrationSection organizationId={organizationId} resourceId={resourceId} /> : null}
        <ResourceChildren organizationId={organizationId} query={contextQuery} linkQuery={linkQuery} />
      </div> : null}
      {active === 'monitoring' ? <>
        <MetricsSection resource={resource} Summary={presentation.MetricSummary}
          isPending={metricsQuery.isPending} isError={metricsQuery.isError} error={metricsQuery.error}
          observations={isUnavailableError(metricsQuery.error) ? undefined : metricsQuery.data} updatedAt={metricsQuery.dataUpdatedAt} refresh={() => setMetricWindow(createLastHourWindow())}
          retry={metricsQuery.refetch} />
        <MonitorRulesSection organizationId={organizationId} resourceId={resourceId} />
      </> : null}
      {active === 'incidents' ? <ScopedIncidentsPanel organizationId={organizationId}
        scope={{ kind: 'resource', id: resourceId }}
        options={{ showResource: false, showContext: false, showCondition: true, linkQuery }}
        openTotal={context?.openIncidentCount} /> : null}
      {active === 'activity' ?
        <ResourceActivitySection organizationId={organizationId} resourceId={resourceId} /> : null}
      {active === 'configurations' ? <>
        <ResourceLabelsSection organizationId={organizationId} resourceId={resourceId} canEdit />
        <ConfigurationAssignmentList organizationId={organizationId} filter={{ resourceId }} view="resource" canAssign={resource.active} />
      </> : null}
      {active === 'operations' ?
        <ResourceOperationsPanel organizationId={organizationId} resourceId={resourceId} resourceName={resource.name} /> : null}
    </div>
  </div></AppShell>
}

/**
 * Where "back" leads: to the page this one was opened from when that was a connection or another
 * resource, otherwise to the resource's environment. Never the browser history, so a direct link
 * has the same way back.
 */
function backLink(
  organizationId: string,
  environmentId: string,
  params: URLSearchParams,
  context: ResourceContextResponse | undefined,
  i18n: ReturnType<typeof useI18n>,
): { label: string; to: string } {
  const origin = readOrigin(params)
  const workspace = workspaceQuery(params)
  if (origin?.kind === 'connection') {
    const source = context?.sourceConnections.find(value => value.id === origin.id)
    return { label: source?.name ?? i18n.t.infrastructure.connection, to: originPath(organizationId, origin, workspace, 'resources') }
  }
  if (origin?.kind === 'resource') {
    const name = context?.parentResource?.id === origin.id ? context.parentResource.name : i18n.t.infrastructure.resource
    return { label: name, to: originPath(organizationId, origin, workspace) }
  }
  if (origin?.kind === 'incident') return { label: i18n.t.incidents.page.crumb, to: originPath(organizationId, origin, workspace) }
  return { label: i18n.t.resources.page.back, to: context
    ? environmentPath(organizationId, context.project.id, environmentId)
    : `/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}${workspace}` }
}

/**
 * What the resource contains. Where it lives is the path above the title; it is not repeated here
 * as a table. A failed context read is reported here alone and the rest of the page stays usable.
 */
function ResourceChildren({ organizationId, query, linkQuery }: {
  organizationId: string
  query: ReturnType<typeof useResourceContext>
  linkQuery: string
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  if (query.isPending) return null
  if (!query.data || isUnavailableError(query.error)) {
    return <InlineAlert tone="warning" title={t.contextError}
      action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(query.error, i18n)}</InlineAlert>
  }
  const context = query.data
  const title = resourceChildrenTitle(context.children, context.activeChildCount, i18n)
  if (context.activeChildCount === 0) return query.isError
    ? <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => query.refetch()} /> : null
  return <>
  {query.isError ? <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => query.refetch()} /> : null}
  <WorkspaceSection title={title}
    actions={<span className="resource-count">{t.childrenShown(context.children.length, context.activeChildCount)}</span>}>
    <ResourceTree roots={asTree(context.children)} organizationId={organizationId} linkQuery={linkQuery} label={title} />
  </WorkspaceSection></>
}

function MetricsSection({ resource, Summary, isPending, isError, error, observations, updatedAt, refresh, retry }: {
  resource: ResourceResponse
  Summary: ComponentType<ResourcePresentationProps> | undefined
  isPending: boolean
  isError: boolean
  error: Error | null
  observations: readonly MetricObservationResponse[] | undefined
  updatedAt: number
  refresh: () => void
  retry: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.metrics
  const cpuSeries = filterMetricSeries(observations ?? [], MetricCode.cpuUsagePercent)
  const memorySeries = filterMetricSeries(observations ?? [], MetricCode.memoryUsagePercent)
  return <WorkspaceSection title={t.title} actions={<button className="secondary-button" type="button" onClick={refresh}>
    <RefreshCw aria-hidden size={14} /> {i18n.t.common.refresh}</button>}>
    {Summary ? <Summary resource={resource} /> : null}
    {isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {isError && observations ? <RefreshWarning updatedAt={updatedAt} retry={retry} /> : null}
    {isError && !observations ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" type="button" onClick={retry}>{i18n.t.common.retry}</button>}>
      {describeError(error, i18n)}</InlineAlert> : null}
    {!isPending && (!isError || observations !== undefined) ? <div className="charts-grid">
      <section className="chart-pane" aria-label={t.chart(t.cpu)}><h3>{t.cpu}</h3><MetricChart title={t.cpu} data={cpuSeries} /></section>
      <section className="chart-pane" aria-label={t.chart(t.memory)}><h3>{t.memory}</h3><MetricChart title={t.memory} data={memorySeries} /></section>
    </div> : null}
  </WorkspaceSection>
}
