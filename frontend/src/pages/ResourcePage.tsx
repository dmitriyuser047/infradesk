import { useState } from 'react'
import type { ComponentType } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { RefreshCw, SearchX } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { createLastHourWindow, useResourceMetrics } from '../api/metrics'
import { useResource } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { useAvailableResourceOperations, useResourceOperationExecutions } from '../api/resourceOperations'
import { operationsApplicability } from '../components/resources/operationPresentation'
import { MetricChart } from '../components/metrics/MetricChart'
import { filterMetricSeries } from '../components/metrics/metricSeries'
import { ResourceActivitySection } from '../components/history/ResourceActivitySection'
import { MonitorRulesSection } from '../components/monitoring/MonitorRulesSection'
import { supportsResourceMonitoring } from '../components/monitoring/resourceMonitoringSupport'
import { resourcePresentationRegistry } from '../components/resources/presentation/resourcePresentations'
import type { ResourcePresentationProps } from '../components/resources/presentation/ResourcePresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { MetricCode, type MetricObservationResponse } from '../types/metric'
import type { ResourceResponse } from '../types/resource'
import { ResourceOperationsPanel } from '../components/resources/ResourceOperationsPanel'
import { InvalidRoutePage } from './InvalidRoutePage'

type Tab = 'overview' | 'monitoring' | 'activity' | 'operations'

export function ResourcePage() {
  const { organizationId, environmentId, resourceId } = useParams()
  if (!organizationId || !environmentId || !resourceId) return <InvalidRoutePage />
  return <ResourceContent organizationId={organizationId} environmentId={environmentId} resourceId={resourceId} />
}

/**
 * One resource: its name and status first, then tabs for what applies to it. How it looks comes
 * from its presentation; whether it is monitored comes from the monitoring feature and whether it
 * has operations from the backend — three separate answers, never derived from each other.
 */
function ResourceContent({ organizationId, environmentId, resourceId }: {
  organizationId: string; environmentId: string; resourceId: string
}) {
  const i18n = useI18n()
  const t = i18n.t.resources.page
  const location = useLocation()
  const [tab, setTab] = useState<Tab>('overview')
  const [metricWindow, setMetricWindow] = useState(() => createLastHourWindow())
  const resourceQuery = useResource(organizationId, resourceId)
  const presentation = resourcePresentationRegistry.resolve(resourceQuery.data?.resourceTypeCode ?? '')
  const monitored = supportsResourceMonitoring(resourceQuery.data?.resourceTypeCode)
  // The same two reads the operations tab shows; asking them here only decides whether it exists.
  const operations = operationsApplicability(
    useAvailableResourceOperations(organizationId, resourceId),
    useResourceOperationExecutions(organizationId, resourceId))
  const metricsQuery = useResourceMetrics(organizationId, resourceId, metricWindow,
    monitored && tab === 'monitoring')
  const back = { label: t.back,
    to: `/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}${location.search}` }

  if (resourceQuery.isPending) return <AppShell><div className="workspace-page" aria-busy="true">
    <WorkspaceHeader title={t.loading} back={back} />
    <div className="row-skeleton" aria-label={t.loading}><span /><span /><span /></div>
  </div></AppShell>
  if (resourceQuery.isError || !resourceQuery.data) {
    const notFound = resourceQuery.error instanceof ApiError && resourceQuery.error.code === 'RESOURCE_NOT_FOUND'
    return <AppShell><div className="workspace-page">
      <WorkspaceHeader title={notFound ? t.notFound : t.loadError} back={back} />
      {notFound ? <EmptyWorkspaceState icon={SearchX} title={t.notFound} detail={t.notFoundDetail} />
        : <InlineAlert tone="danger" title={t.loadError}
          action={<button className="secondary-button" type="button" onClick={() => resourceQuery.refetch()}>{i18n.t.common.retry}</button>}>
          {describeError(resourceQuery.error, i18n)}</InlineAlert>}
    </div></AppShell>
  }
  const resource = resourceQuery.data
  const tabs: { id: Tab; label: string }[] = [
    { id: 'overview', label: t.tabs.overview },
    ...(monitored ? [{ id: 'monitoring' as const, label: t.tabs.monitoring }] : []),
    { id: 'activity', label: t.tabs.activity },
    ...(operations === 'applicable' ? [{ id: 'operations' as const, label: t.tabs.operations }] : []),
  ]
  // A tab that stopped applying (the operations answer changed) falls back to the overview.
  const active = tabs.some(item => item.id === tab) ? tab : 'overview'
  const status = presentation.headerStatus?.(resource, i18n)
  const Overview = presentation.Overview
  const typeLabel = i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode

  return <AppShell><div className="workspace-page resource-page">
    <WorkspaceHeader title={resource.name} subtitle={t.subtitle(typeLabel, resource.code)} back={back}
      status={<>{status ? <StatusIndicator label={status.label} tone={status.tone} /> : null}
        {!resource.active ? <StatusIndicator label={t.inactive} tone="neutral" /> : null}</>} />
    <WorkspaceTabs tabs={tabs} active={active} onChange={setTab} />
    <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
      {active === 'overview' ? <Overview resource={resource} /> : null}
      {active === 'monitoring' ? <>
        <MetricsSection resource={resource} Summary={presentation.MetricSummary}
          isPending={metricsQuery.isPending} isError={metricsQuery.isError} error={metricsQuery.error}
          observations={metricsQuery.data} refresh={() => setMetricWindow(createLastHourWindow())}
          retry={metricsQuery.refetch} />
        <MonitorRulesSection organizationId={organizationId} resourceId={resourceId} />
      </> : null}
      {active === 'activity' ?
        <ResourceActivitySection organizationId={organizationId} resourceId={resourceId} /> : null}
      {active === 'operations' ?
        <ResourceOperationsPanel organizationId={organizationId} resourceId={resourceId} resourceName={resource.name} /> : null}
    </div>
  </div></AppShell>
}

function MetricsSection({ resource, Summary, isPending, isError, error, observations, refresh, retry }: {
  resource: ResourceResponse
  Summary: ComponentType<ResourcePresentationProps> | undefined
  isPending: boolean
  isError: boolean
  error: Error | null
  observations: readonly MetricObservationResponse[] | undefined
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
    {isError ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" type="button" onClick={retry}>{i18n.t.common.retry}</button>}>
      {describeError(error, i18n)}</InlineAlert> : null}
    {!isPending && !isError ? <div className="charts-grid">
      <section className="chart-pane" aria-label={t.chart(t.cpu)}><h3>{t.cpu}</h3><MetricChart title={t.cpu} data={cpuSeries} /></section>
      <section className="chart-pane" aria-label={t.chart(t.memory)}><h3>{t.memory}</h3><MetricChart title={t.memory} data={memorySeries} /></section>
    </div> : null}
  </WorkspaceSection>
}
