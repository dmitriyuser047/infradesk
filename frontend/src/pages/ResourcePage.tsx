import { useState } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { createLastHourWindow, useResourceMetrics } from '../api/metrics'
import { useResource } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection,
  WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { formatDuration, formatMemoryMb, formatPercent } from '../components/metrics/formatters'
import { MetricChart } from '../components/metrics/MetricChart'
import { filterMetricSeries } from '../components/metrics/metricSeries'
import { MonitorRulesSection } from '../components/monitoring/MonitorRulesSection'
import { MetricCode, type MetricObservationResponse } from '../types/metric'
import type { ResourceResponse } from '../types/resource'
import { InvalidRoutePage } from './InvalidRoutePage'

type Tab = 'overview' | 'metrics' | 'rules'

export function ResourcePage() {
  const { organizationId, environmentId, resourceId } = useParams()
  if (!organizationId || !environmentId || !resourceId) return <InvalidRoutePage />
  return <ResourceContent organizationId={organizationId} environmentId={environmentId} resourceId={resourceId} />
}

function ResourceContent({ organizationId, environmentId, resourceId }: {
  organizationId: string; environmentId: string; resourceId: string
}) {
  const location = useLocation()
  const [tab, setTab] = useState<Tab>('overview')
  const [metricWindow, setMetricWindow] = useState(() => createLastHourWindow())
  const resourceQuery = useResource(organizationId, resourceId)
  const metricsQuery = useResourceMetrics(organizationId, resourceId, metricWindow,
    resourceQuery.data?.data.kind === 'NODE' && tab === 'metrics')
  const back = `/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}${location.search}`

  if (resourceQuery.isPending) return <AppShell><div className="row-skeleton" aria-label="Loading resource"><span /><span /></div></AppShell>
  if (resourceQuery.isError || !resourceQuery.data) {
    const notFound = resourceQuery.error instanceof ApiError && resourceQuery.error.code === 'RESOURCE_NOT_FOUND'
    return <AppShell><div className="inline-error" role="alert">{notFound ? 'Resource not found' : 'Unable to load resource'}
      {!notFound ? <button className="text-button" type="button" onClick={() => resourceQuery.refetch()}>Retry</button> : null}</div></AppShell>
  }
  const resource = resourceQuery.data
  const tabs: { id: Tab; label: string }[] = resource.data.kind === 'NODE' ? [
    { id: 'overview', label: 'Overview' }, { id: 'metrics', label: 'Metrics' }, { id: 'rules', label: 'Monitor rules' },
  ] : [{ id: 'overview', label: 'Overview' }]
  const status = resource.data.kind === 'NODE' ? resource.data.status?.online : undefined

  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={resource.name} subtitle={`${resource.resourceTypeCode} · ${resource.code}`}
      back={{ label: 'Infrastructure', to: back }}
      status={resource.data.kind === 'NODE' ? <StatusIndicator
        label={status === true ? 'Online' : status === false ? 'Offline' : 'Unknown'}
        tone={status === true ? 'success' : status === false ? 'danger' : 'neutral'} /> : undefined} />
    <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
      {tab === 'overview' ? <ResourceOverview resource={resource} /> : null}
      {tab === 'metrics' ? <MetricsSection resource={resource} isPending={metricsQuery.isPending}
        isError={metricsQuery.isError} error={metricsQuery.error} observations={metricsQuery.data}
        refresh={() => setMetricWindow(createLastHourWindow())} retry={metricsQuery.refetch} /> : null}
      {tab === 'rules' && resource.data.kind === 'NODE' ?
        <MonitorRulesSection organizationId={organizationId} resourceId={resourceId} /> : null}
    </div>
  </div></AppShell>
}

function ResourceOverview({ resource }: { resource: ResourceResponse }) {
  if (resource.data.kind === 'NODE') {
    const { spec, status } = resource.data
    return <div className="workspace-split detail-split">
      <WorkspaceSection title="Properties"><PropertyGrid items={[
        { label: 'Code', value: resource.code },
        { label: 'Hostname', value: spec?.hostname ?? '—' },
        { label: 'Operating system', value: spec?.operatingSystem ?? '—' },
        { label: 'Architecture', value: spec?.architecture ?? '—' },
        { label: 'CPU cores', value: spec?.cpuCores ?? '—' },
        { label: 'Memory', value: formatMemoryMb(spec?.memoryMb ?? null) },
      ]} /></WorkspaceSection>
      <WorkspaceSection title="Current state"><PropertyGrid items={[
        { label: 'Status', value: <StatusIndicator label={status?.online === true ? 'Online' : status?.online === false ? 'Offline' : 'Unknown'}
          tone={status?.online === true ? 'success' : status?.online === false ? 'danger' : 'neutral'} /> },
        { label: 'CPU', value: formatPercent(status?.cpuUsagePercent ?? null) },
        { label: 'Memory', value: formatPercent(status?.memoryUsagePercent ?? null) },
        { label: 'Uptime', value: formatDuration(status?.uptimeSeconds ?? null) },
      ]} /></WorkspaceSection>
    </div>
  }
  if (resource.data.kind === 'CONTAINER') return <div className="workspace-split detail-split">
    <WorkspaceSection title="Properties"><PropertyGrid items={[
      { label: 'Code', value: resource.code }, { label: 'Type', value: resource.resourceTypeCode },
      { label: 'Image', value: resource.data.spec?.image ?? '—' },
    ]} /></WorkspaceSection>
    <WorkspaceSection title="Current state"><PropertyGrid items={[
      { label: 'State', value: resource.data.status?.state ?? '—' },
    ]} /></WorkspaceSection>
  </div>
  return <EmptyWorkspaceState title="Details are not available for this resource type" />
}

function MetricsSection({ resource, isPending, isError, error, observations, refresh, retry }: {
  resource: ResourceResponse
  isPending: boolean
  isError: boolean
  error: Error | null
  observations: readonly MetricObservationResponse[] | undefined
  refresh: () => void
  retry: () => void
}) {
  if (resource.data.kind !== 'NODE') return null
  const cpuSeries = filterMetricSeries(observations ?? [], MetricCode.cpuUsagePercent)
  const memorySeries = filterMetricSeries(observations ?? [], MetricCode.memoryUsagePercent)
  return <WorkspaceSection title="Metrics · last 1 hour" actions={<button className="secondary-button" type="button" onClick={refresh}>
    <RefreshCw aria-hidden size={14} /> Refresh</button>}>
    <div className="metric-strip"><div><span>CPU</span><strong>{formatPercent(resource.data.status?.cpuUsagePercent ?? null)}</strong></div>
      <div><span>Memory</span><strong>{formatPercent(resource.data.status?.memoryUsagePercent ?? null)}</strong></div></div>
    {isPending ? <div className="row-skeleton" aria-label="Loading metrics"><span /><span /></div> : null}
    {isError ? <div className="inline-error" role="alert">Unable to load metrics. {error instanceof ApiError ? error.message : 'Please try again shortly.'}
      <button className="text-button" type="button" onClick={retry}>Retry</button></div> : null}
    {!isPending && !isError ? <div className="charts-grid">
      <section className="chart-pane" aria-label="CPU usage chart"><h3>CPU usage</h3><MetricChart title="CPU usage" data={cpuSeries} /></section>
      <section className="chart-pane" aria-label="Memory usage chart"><h3>Memory usage</h3><MetricChart title="Memory usage" data={memorySeries} /></section>
    </div> : null}
  </WorkspaceSection>
}
