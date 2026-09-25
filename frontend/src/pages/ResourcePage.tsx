import { useState } from 'react'
import type { ComponentType } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { createLastHourWindow, useResourceMetrics } from '../api/metrics'
import { useResource } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import { StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
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

type Tab = 'overview' | 'metrics' | 'rules' | 'activity'

export function ResourcePage() {
  const { organizationId, environmentId, resourceId } = useParams()
  if (!organizationId || !environmentId || !resourceId) return <InvalidRoutePage />
  return <ResourceContent organizationId={organizationId} environmentId={environmentId} resourceId={resourceId} />
}

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
  const metricsQuery = useResourceMetrics(organizationId, resourceId, metricWindow,
    monitored && tab === 'metrics')
  const back = `/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}${location.search}`

  if (resourceQuery.isPending) return <AppShell><div className="row-skeleton" aria-label={t.loading}><span /><span /></div></AppShell>
  if (resourceQuery.isError || !resourceQuery.data) {
    const notFound = resourceQuery.error instanceof ApiError && resourceQuery.error.code === 'RESOURCE_NOT_FOUND'
    return <AppShell><div className="inline-error" role="alert">{notFound ? t.notFound : t.loadError}
      {!notFound ? <button className="text-button" type="button" onClick={() => resourceQuery.refetch()}>{i18n.t.common.retry}</button> : null}</div></AppShell>
  }
  const resource = resourceQuery.data
  const tabs: { id: Tab; label: string }[] = (monitored ? [
    { id: 'overview' as Tab, label: t.tabs.overview }, { id: 'metrics' as Tab, label: t.tabs.metrics },
    { id: 'rules' as Tab, label: t.tabs.rules },
  ] : [{ id: 'overview' as Tab, label: t.tabs.overview }]).concat([{ id: 'activity', label: t.tabs.activity }])
  const status = presentation.headerStatus?.(resource, i18n)
  const Overview = presentation.Overview
  const typeLabel = i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode

  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={resource.name} subtitle={t.subtitle(typeLabel, resource.code)}
      back={{ label: t.back, to: back }}
      status={<>{status ? <StatusIndicator label={status.label} tone={status.tone} /> : null}
        {!resource.active ? <StatusIndicator label={t.inactive} tone="neutral" /> : null}</>} />
    <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
      {tab === 'overview' ? <><Overview resource={resource} />
        <ResourceOperationsPanel organizationId={organizationId} resourceId={resourceId} resourceName={resource.name} /></> : null}
      {tab === 'metrics' && monitored ? <MetricsSection resource={resource} Summary={presentation.MetricSummary}
        isPending={metricsQuery.isPending} isError={metricsQuery.isError} error={metricsQuery.error}
        observations={metricsQuery.data} refresh={() => setMetricWindow(createLastHourWindow())}
        retry={metricsQuery.refetch} /> : null}
      {tab === 'rules' && monitored ?
        <MonitorRulesSection organizationId={organizationId} resourceId={resourceId} /> : null}
      {tab === 'activity' ?
        <ResourceActivitySection organizationId={organizationId} resourceId={resourceId} /> : null}
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
    {isError ? <div className="inline-error" role="alert">{t.loadError} {describeError(error, i18n)}
      <button className="text-button" type="button" onClick={retry}>{i18n.t.common.retry}</button></div> : null}
    {!isPending && !isError ? <div className="charts-grid">
      <section className="chart-pane" aria-label={t.chart(t.cpu)}><h3>{t.cpu}</h3><MetricChart title={t.cpu} data={cpuSeries} /></section>
      <section className="chart-pane" aria-label={t.chart(t.memory)}><h3>{t.memory}</h3><MetricChart title={t.memory} data={memorySeries} /></section>
    </div> : null}
  </WorkspaceSection>
}
