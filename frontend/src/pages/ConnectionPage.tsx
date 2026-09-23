import { Cable } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSessions, useDeactivateConnection, useRunConnectionSync } from '../api/connections'
import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { ConnectionStatusBadge } from '../components/connections/ConnectionStatusBadge'
import {
  formatConnectionDateTime,
  formatScheduleInterval,
  formatSyncDuration,
  getSyncFailureMessage,
  getConnectionScopeLabel,
  getConnectorTypeLabel,
  shortConnectionIdentifier,
} from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { ConnectionScopeType, SyncStatus, type ConnectionResponse } from '../types/connection'
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
  const membership = useMyOrganizations()
  const deactivate = useDeactivateConnection(organizationId, connectionId)
  const sync = useRunConnectionSync(organizationId, connectionId)
  const navigate = useNavigate()
  const isOwner = membership.data?.find(value => value.id === organizationId)?.role === 'OWNER'
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
          {isOwner && connection.active && connection.connectorType === 'SSH' ? <div className="state-actions">
            <Link className="retry-button" to={`/organizations/${organizationId}/connections/${connectionId}/edit`}>Edit</Link>
            <button type="button" disabled={deactivate.isPending} onClick={() => {
              if (window.confirm('Deactivate this connection? Scheduled sync will stop.')) {
                deactivate.mutate(undefined, { onSuccess: () => navigate(connectionsPath) })
              }
            }}>Deactivate</button>
          </div> : null}
          {isOwner && connection.active ? <button type="button"
            disabled={sync.isPending || connection.lastSync?.status === SyncStatus.running}
            onClick={() => sync.mutate()}>
            {sync.isPending ? 'Synchronizing…' : connection.lastSync?.status === SyncStatus.running ? 'Synchronization running' : 'Sync now'}
          </button> : null}
        </header>
        {deactivate.isError ? <p role="alert">{safeErrorMessage(deactivate.error)}</p> : null}
        {sync.isError ? <p role="alert">{sync.error instanceof ApiError && sync.error.code === 'SYNC_ALREADY_RUNNING'
          ? 'Synchronization is already running.' : safeErrorMessage(sync.error)}</p> : null}
        {sync.isSuccess ? <p role="status">{sync.data.status === SyncStatus.failed
          ? getSyncFailureMessage(sync.data.errorMessage) : 'Synchronization completed'}</p> : null}
        <ConnectionOverview connection={connection} />
        <ConnectionSynchronization connection={connection} />
        <ConnectionSyncHistory organizationId={organizationId} connectionId={connectionId} />
        <ConnectionSchedule connection={connection} />
        {connection.ssh ? <section className="content-panel connection-section"><h2>SSH</h2><dl className="summary-grid connection-summary-grid">
          <DetailItem label="Host" value={connection.ssh.host} />
          <DetailItem label="Port" value={String(connection.ssh.port)} />
          <DetailItem label="Username" value={connection.ssh.username} />
          <DetailItem label="Host key fingerprint" value={connection.ssh.hostKeyFingerprint ?? 'Not pinned'} />
          <DetailItem label="Credentials" value={connection.ssh.credentialConfigured ? 'Configured' : 'Missing'} />
        </dl></section> : null}
      </div>
    </AppShell>
  )
}

function ConnectionSyncHistory({ organizationId, connectionId }: { organizationId: string; connectionId: string }) {
  const history = useConnectionSyncSessions(organizationId, connectionId)
  return <section className="content-panel connection-section" aria-labelledby="sync-history-heading">
    <div className="panel-heading"><div><p className="eyebrow">Executions</p>
      <h2 id="sync-history-heading">Synchronization history</h2></div></div>
    {history.isPending ? <div className="connection-skeleton" aria-label="Loading synchronization history"><span /><span /></div> : null}
    {history.isError ? <div className="connection-state" role="alert"><p>Unable to load synchronization history</p>
      <button type="button" className="retry-button" onClick={() => history.refetch()}>Retry</button></div> : null}
    {history.data?.length === 0 ? <p className="connection-section-empty">No synchronization runs yet</p> : null}
    {history.data?.map(session => <Link className="sync-history-row" key={session.id}
      to={`/organizations/${organizationId}/connections/${connectionId}/sync-sessions/${session.id}`}>
      <SyncStatusBadge status={session.status} />
      <span>{formatConnectionDateTime(session.startedAt)}</span>
      <span>{formatSyncDuration(session.startedAt, session.finishedAt)}</span>
    </Link>)}
  </section>
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
          {lastSync.status === SyncStatus.failed
            ? <DetailItem label="Error" value={getSyncFailureMessage(lastSync.errorMessage)} /> : null}
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
