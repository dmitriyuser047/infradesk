import { Cable } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { useConnection } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { ConnectionStatusBadge } from '../components/connections/ConnectionStatusBadge'
import {
  formatConnectionDateTime,
  formatScheduleInterval,
  formatSyncDuration,
  getConnectionScopeLabel,
  getConnectorTypeLabel,
  shortConnectionIdentifier,
} from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { ConnectionScopeType, type ConnectionResponse } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionPage() {
  const { organizationId, connectionId } = useParams()

  if (organizationId === undefined || connectionId === undefined) {
    return <InvalidRoutePage />
  }

  return <ConnectionContent organizationId={organizationId} connectionId={connectionId} />
}

function ConnectionContent({ organizationId, connectionId }: { organizationId: string; connectionId: string }) {
  const connectionQuery = useConnection(organizationId, connectionId)
  const connectionsPath = `/organizations/${organizationId}/connections`

  if (connectionQuery.isPending) {
    return <AppShell><div className="detail-skeleton" aria-label="Loading connection"><span /><span /><span /></div></AppShell>
  }

  if (connectionQuery.isError || connectionQuery.data === undefined) {
    const notFound = connectionQuery.error instanceof ApiError && connectionQuery.error.code === 'CONNECTION_NOT_FOUND'
    return (
      <AppShell>
        <section className="content-panel connection-state" role="alert">
          <h1>{notFound ? 'Connection not found' : 'Unable to load connection'}</h1>
          <p>{notFound ? 'This connection is unavailable in the current organization.' : safeErrorMessage(connectionQuery.error)}</p>
          <div className="state-actions">
            <Link className="back-link" to={connectionsPath}>Back to connections</Link>
            {!notFound ? <button className="retry-button" type="button" onClick={() => connectionQuery.refetch()}>Retry</button> : null}
          </div>
        </section>
      </AppShell>
    )
  }

  const connection = connectionQuery.data

  return (
    <AppShell>
      <div className="detail-page connection-detail-page">
        <Link className="back-link" to={connectionsPath}>← Back to connections</Link>
        <header className="resource-header connection-header">
          <div className="resource-header-icon"><Cable aria-hidden size={23} /></div>
          <div>
            <p className="eyebrow">Connection</p>
            <h1>{connection.name}</h1>
            <p className="page-subtitle">{getConnectorTypeLabel(connection.connectorType)} · {connection.code}</p>
          </div>
          <div className="connection-header-status"><ConnectionStatusBadge active={connection.active} /></div>
        </header>
        <ConnectionOverview connection={connection} />
        <ConnectionSynchronization connection={connection} />
        <ConnectionSchedule connection={connection} />
      </div>
    </AppShell>
  )
}

function ConnectionOverview({ connection }: { connection: ConnectionResponse }) {
  return (
    <section className="content-panel connection-section" aria-labelledby="connection-overview-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Connection</p>
          <h2 id="connection-overview-heading">Overview</h2>
        </div>
      </div>
      <dl className="summary-grid connection-summary-grid">
        <DetailItem label="Code" value={connection.code} />
        <DetailItem label="Type" value={getConnectorTypeLabel(connection.connectorType)} />
        <DetailItem label="Scope" value={getConnectionScopeLabel(connection.scope)} />
        {connection.scope.type === ConnectionScopeType.project || connection.scope.type === ConnectionScopeType.environment ? (
          <DetailItem label="Project ID" value={shortConnectionIdentifier(connection.scope.projectId)} />
        ) : null}
        {connection.scope.type === ConnectionScopeType.environment ? (
          <DetailItem label="Environment ID" value={shortConnectionIdentifier(connection.scope.environmentId)} />
        ) : null}
      </dl>
    </section>
  )
}

function ConnectionSynchronization({ connection }: { connection: ConnectionResponse }) {
  const lastSync = connection.lastSync

  return (
    <section className="content-panel connection-section" aria-labelledby="connection-sync-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Latest execution</p>
          <h2 id="connection-sync-heading">Synchronization</h2>
        </div>
      </div>
      {lastSync === null ? <p className="connection-section-empty">Never synchronized</p> : (
        <dl className="summary-grid connection-summary-grid">
          <div><dt>Status</dt><dd><SyncStatusBadge status={lastSync.status} /></dd></div>
          <DetailItem label="Started" value={formatConnectionDateTime(lastSync.startedAt)} />
          <DetailItem label="Finished" value={lastSync.finishedAt === null ? 'In progress' : formatConnectionDateTime(lastSync.finishedAt)} />
          <DetailItem label="Duration" value={formatSyncDuration(lastSync.startedAt, lastSync.finishedAt)} />
        </dl>
      )}
    </section>
  )
}

function ConnectionSchedule({ connection }: { connection: ConnectionResponse }) {
  const schedule = connection.schedule

  return (
    <section className="content-panel connection-section" aria-labelledby="connection-schedule-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Automation</p>
          <h2 id="connection-schedule-heading">Schedule</h2>
        </div>
      </div>
      {schedule === null ? <p className="connection-section-empty">Not configured</p> : schedule.enabled ? (
        <dl className="summary-grid connection-summary-grid">
          <DetailItem label="Status" value="Enabled" />
          <DetailItem label="Interval" value={formatScheduleInterval(schedule.intervalSeconds)} />
          <DetailItem label="Next run" value={formatConnectionDateTime(schedule.nextRunAt)} />
        </dl>
      ) : <p className="connection-section-empty">Disabled</p>}
    </section>
  )
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>
}

function safeErrorMessage(error: Error | null): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}
