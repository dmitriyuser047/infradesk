import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Box, RefreshCw, Server } from 'lucide-react'

import { ApiError } from '../api/httpClient'
import { createLastHourWindow, useResourceMetrics } from '../api/metrics'
import { useResource } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import { formatDuration, formatMemoryMb, formatPercent } from '../components/metrics/formatters'
import { MetricChart } from '../components/metrics/MetricChart'
import { filterMetricSeries } from '../components/metrics/metricSeries'
import { MonitorRulesSection } from '../components/monitoring/MonitorRulesSection'
import { MetricCode } from '../types/metric'
import type { ResourceResponse } from '../types/resource'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ResourcePage() {
  const { organizationId, environmentId, resourceId } = useParams()

  if (organizationId === undefined || environmentId === undefined || resourceId === undefined) {
    return <InvalidRoutePage />
  }

  return (
    <ResourceContent
      organizationId={organizationId}
      environmentId={environmentId}
      resourceId={resourceId}
    />
  )
}

interface ResourceContentProps {
  organizationId: string
  environmentId: string
  resourceId: string
}

function ResourceContent({ organizationId, environmentId, resourceId }: ResourceContentProps) {
  const [metricWindow, setMetricWindow] = useState(() => createLastHourWindow())
  const resourceQuery = useResource(organizationId, resourceId)
  const metricsQuery = useResourceMetrics(
    organizationId,
    resourceId,
    metricWindow,
    resourceQuery.data?.data.kind === 'NODE',
  )
  const environmentPath = `/organizations/${organizationId}/environments/${environmentId}`

  if (resourceQuery.isPending) {
    return (
      <AppShell>
        <ResourcePageSkeleton />
      </AppShell>
    )
  }

  if (resourceQuery.isError) {
    const isNotFound = resourceQuery.error instanceof ApiError && resourceQuery.error.code === 'RESOURCE_NOT_FOUND'
    return (
      <AppShell>
        <section className="content-panel state-message" role="alert">
          <h1>{isNotFound ? 'Resource not found' : 'Unable to load resource'}</h1>
          <p>{isNotFound ? 'This resource is unavailable in the current organization.' : safeErrorMessage(resourceQuery.error)}</p>
          <div className="state-actions">
            <Link className="back-link" to={environmentPath}>Back to infrastructure</Link>
            {!isNotFound ? (
              <button className="retry-button" type="button" onClick={() => resourceQuery.refetch()}>Retry</button>
            ) : null}
          </div>
        </section>
      </AppShell>
    )
  }

  if (resourceQuery.data === undefined) {
    return null
  }

  return (
    <AppShell>
      <div className="detail-page">
        <Link className="back-link" to={environmentPath}>← Back to infrastructure</Link>
        <ResourceHeader resource={resourceQuery.data} />
        <ResourceSummary resource={resourceQuery.data} />
        <MetricsSection
          resource={resourceQuery.data}
          isPending={metricsQuery.isPending}
          isError={metricsQuery.isError}
          error={metricsQuery.error}
          observations={metricsQuery.data}
          refresh={() => setMetricWindow(createLastHourWindow())}
          retry={metricsQuery.refetch}
        />
        {resourceQuery.data.data.kind === 'NODE' ? (
          <MonitorRulesSection organizationId={organizationId} resourceId={resourceId} />
        ) : null}
      </div>
    </AppShell>
  )
}

function ResourceHeader({ resource }: { resource: ResourceResponse }) {
  const isNode = resource.data.kind === 'NODE'
  const Icon = isNode ? Server : Box
  const status = resource.data.kind === 'NODE'
    ? nodeStatusLabel(resource.data.status?.online)
    : undefined

  return (
    <header className="resource-header">
      <div className="resource-header-icon"><Icon aria-hidden size={24} /></div>
      <div>
        <p className="eyebrow">{resource.resourceTypeCode}</p>
        <h1>{resource.name}</h1>
        <p className="page-subtitle">{resource.code}</p>
      </div>
      <div className="resource-header-meta">
        <span className={`resource-badge resource-badge-${isNode ? 'node' : 'container'}`}>
          {resource.resourceTypeCode}
        </span>
        {status !== undefined ? (
          <span className={`status-badge status-badge-${status.toLowerCase()}`}>{status}</span>
        ) : null}
      </div>
    </header>
  )
}

function ResourceSummary({ resource }: { resource: ResourceResponse }) {
  if (resource.data.kind === 'NODE') {
    const { spec, status } = resource.data
    return (
      <section className="content-panel resource-summary" aria-labelledby="overview-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Current inventory</p>
            <h2 id="overview-heading">Overview</h2>
          </div>
          <div className="current-metrics" aria-label="Current utilization">
            <CurrentMetric label="CPU" value={formatPercent(status?.cpuUsagePercent ?? null)} />
            <CurrentMetric label="Memory" value={formatPercent(status?.memoryUsagePercent ?? null)} />
          </div>
        </div>
        <dl className="summary-grid">
          <SummaryItem label="Hostname" value={spec?.hostname ?? '—'} />
          <SummaryItem label="Operating system" value={spec?.operatingSystem ?? '—'} />
          <SummaryItem label="Architecture" value={spec?.architecture ?? '—'} />
          <SummaryItem label="CPU cores" value={spec?.cpuCores === null || spec === null ? '—' : String(spec.cpuCores)} />
          <SummaryItem label="Memory" value={formatMemoryMb(spec?.memoryMb ?? null)} />
          <SummaryItem label="Uptime" value={formatDuration(status?.uptimeSeconds ?? null)} />
        </dl>
      </section>
    )
  }

  if (resource.data.kind === 'CONTAINER') {
    return (
      <section className="content-panel resource-summary" aria-labelledby="overview-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Current inventory</p>
            <h2 id="overview-heading">Overview</h2>
          </div>
        </div>
        <dl className="summary-grid">
          <SummaryItem label="Image" value={resource.data.spec?.image ?? '—'} />
          <SummaryItem label="State" value={resource.data.status?.state ?? '—'} />
        </dl>
      </section>
    )
  }

  return (
    <section className="content-panel state-message">
      <h2>Details are not available</h2>
      <p>Details for this resource type are not supported yet.</p>
    </section>
  )
}

function MetricsSection({
  resource,
  isPending,
  isError,
  error,
  observations,
  refresh,
  retry,
}: {
  resource: ResourceResponse
  isPending: boolean
  isError: boolean
  error: Error | null
  observations: readonly import('../types/metric').MetricObservationResponse[] | undefined
  refresh: () => void
  retry: () => void
}) {
  if (resource.data.kind !== 'NODE') {
    return (
      <section className="content-panel metrics-section">
        <h2>Metrics</h2>
        <p className="metric-empty">Metrics are not available for this resource type yet.</p>
      </section>
    )
  }

  const cpuSeries = filterMetricSeries(observations ?? [], MetricCode.cpuUsagePercent)
  const memorySeries = filterMetricSeries(observations ?? [], MetricCode.memoryUsagePercent)

  return (
    <section className="content-panel metrics-section" aria-labelledby="metrics-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Last 1 hour</p>
          <h2 id="metrics-heading">Metrics</h2>
        </div>
        <button className="refresh-button" type="button" onClick={refresh}>
          <RefreshCw aria-hidden size={15} />Refresh
        </button>
      </div>
      {isPending ? <MetricsSkeleton /> : null}
      {isError ? (
        <div className="metrics-error" role="alert">
          <p>Unable to load metrics. {safeErrorMessage(error)}</p>
          <button className="retry-button" type="button" onClick={retry}>Retry</button>
        </div>
      ) : null}
      {!isPending && !isError ? (
        <div className="charts-grid">
          <section className="chart-card" aria-labelledby="cpu-chart-heading">
            <h3 id="cpu-chart-heading">CPU usage</h3>
            <MetricChart title="CPU usage" data={cpuSeries} />
          </section>
          <section className="chart-card" aria-labelledby="memory-chart-heading">
            <h3 id="memory-chart-heading">Memory usage</h3>
            <MetricChart title="Memory usage" data={memorySeries} />
          </section>
        </div>
      ) : null}
    </section>
  )
}

function CurrentMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="current-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function MetricsSkeleton() {
  return (
    <div className="charts-grid" aria-label="Loading metrics">
      <div className="chart-card chart-skeleton"><span /></div>
      <div className="chart-card chart-skeleton"><span /></div>
    </div>
  )
}

function SummaryItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function ResourcePageSkeleton() {
  return (
    <div className="detail-skeleton" aria-label="Loading resource">
      <span />
      <span />
      <span />
    </div>
  )
}

function nodeStatusLabel(online: boolean | undefined): 'Online' | 'Offline' | 'Unknown' {
  if (online === true) {
    return 'Online'
  }
  if (online === false) {
    return 'Offline'
  }
  return 'Unknown'
}

function safeErrorMessage(error: Error | null): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}
