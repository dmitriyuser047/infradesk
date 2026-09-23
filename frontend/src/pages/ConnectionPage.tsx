import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useConnection, useConnectionSyncSessions, useDeactivateConnection, useRunConnectionSync } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { ConnectionStatusBadge } from '../components/connections/ConnectionStatusBadge'
import { formatConnectionDateTime, formatScheduleInterval, formatSyncDuration, getConnectionScopeLabel,
  getConnectorTypeLabel, getSyncFailureMessage, shortConnectionIdentifier } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, PropertyGrid, WorkspaceHeader, WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { contextSearch } from '../components/layout/workspaceNavigation'
import { ConnectionScopeType, SyncStatus, type ConnectionResponse } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

type Tab = 'overview' | 'synchronization'
const tabs: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Overview' }, { id: 'synchronization', label: 'Synchronization' },
]

export function ConnectionPage() {
  const { organizationId, connectionId } = useParams()
  if (!organizationId || !connectionId) return <InvalidRoutePage />
  return <ConnectionContent organizationId={organizationId} connectionId={connectionId} />
}

function ConnectionContent({ organizationId, connectionId }: { organizationId: string; connectionId: string }) {
  const connectionQuery = useConnection(organizationId, connectionId)
  const membership = useMyOrganizations()
  const deactivate = useDeactivateConnection(organizationId, connectionId)
  const sync = useRunConnectionSync(organizationId, connectionId)
  const [tab, setTab] = useState<Tab>('overview')
  const navigate = useNavigate()
  const location = useLocation()
  const context = contextSearch(new URLSearchParams(location.search))
  const isOwner = membership.data?.some(value => value.id === organizationId && value.role === 'OWNER')
  const connectionBase = `/organizations/${encodeURIComponent(organizationId)}/connections`
  const back = `${connectionBase}${context}`

  if (connectionQuery.isPending) return <AppShell><div className="row-skeleton" aria-label="Loading connection"><span /><span /><span /></div></AppShell>
  if (connectionQuery.isError || !connectionQuery.data) {
    const notFound = connectionQuery.error instanceof ApiError && connectionQuery.error.code === 'CONNECTION_NOT_FOUND'
    return <AppShell><div className="inline-error" role="alert">{notFound ? 'Connection not found' : 'Unable to load connection'}
      {!notFound ? <button className="text-button" type="button" onClick={() => connectionQuery.refetch()}>Retry</button> : null}</div></AppShell>
  }
  const connection = connectionQuery.data

  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={connection.name}
      subtitle={`${getConnectorTypeLabel(connection.connectorType)} · ${getConnectionScopeLabel(connection.scope)} · ${connection.code}`}
      back={{ label: 'Connections', to: back }} status={<ConnectionStatusBadge active={connection.active} />}
      actions={<>
        {isOwner && connection.active ? <button className="primary-button" type="button"
          disabled={sync.isPending || connection.lastSync?.status === SyncStatus.running}
          onClick={() => sync.mutate()}>{sync.isPending ? 'Synchronizing…' : 'Sync now'}</button> : null}
        {isOwner && connection.active && connection.connectorType === 'SSH' ? <>
          <Link className="secondary-button" to={`${connectionBase}/${encodeURIComponent(connectionId)}/edit${context}`}>Edit</Link>
          <details className="toolbar-overflow"><summary aria-label="More connection actions" title="More actions">⋯</summary>
            <button type="button" className="danger-action" disabled={deactivate.isPending} onClick={() => {
              if (window.confirm('Deactivate this connection? Scheduled sync will stop.')) {
                deactivate.mutate(undefined, { onSuccess: () => navigate(back) })
              }
            }}>Deactivate</button>
          </details>
        </> : null}
      </>} />
    {deactivate.isError ? <p className="inline-error" role="alert">{safeErrorMessage(deactivate.error)}</p> : null}
    {sync.isError ? <p className="inline-error" role="alert">{sync.error instanceof ApiError && sync.error.code === 'SYNC_ALREADY_RUNNING'
      ? 'Synchronization is already running.' : safeErrorMessage(sync.error)}</p> : null}
    {sync.isSuccess ? <p className="inline-feedback" role="status">{sync.data.status === SyncStatus.failed
      ? getSyncFailureMessage(sync.data.errorMessage) : 'Synchronization completed'}</p> : null}
    <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
      {tab === 'overview' ? <ConnectionOverview connection={connection} /> :
        <ConnectionSyncHistory organizationId={organizationId} connectionId={connectionId} context={context} />}
    </div>
  </div></AppShell>
}

function ConnectionOverview({ connection }: { connection: ConnectionResponse }) {
  const scopeItems = connection.scope.type === ConnectionScopeType.organization ? [] : [
    { label: 'Project ID', value: shortConnectionIdentifier(connection.scope.projectId) },
    ...(connection.scope.type === ConnectionScopeType.environment ?
      [{ label: 'Environment ID', value: shortConnectionIdentifier(connection.scope.environmentId) }] : []),
  ]
  const latest = connection.lastSync
  return <>
    <div className="workspace-split detail-split">
      <WorkspaceSection title="Connection"><PropertyGrid items={[
        { label: 'Code', value: connection.code },
        { label: 'Type', value: getConnectorTypeLabel(connection.connectorType) },
        { label: 'Scope', value: getConnectionScopeLabel(connection.scope) },
        ...scopeItems,
        { label: 'Schedule', value: connection.schedule?.enabled ? formatScheduleInterval(connection.schedule.intervalSeconds) : 'Manual' },
        ...(connection.schedule?.enabled ? [{ label: 'Next run', value: formatConnectionDateTime(connection.schedule.nextRunAt) }] : []),
      ]} /></WorkspaceSection>
      <WorkspaceSection title="Latest synchronization">{latest ? <PropertyGrid items={[
        { label: 'Status', value: <SyncStatusBadge status={latest.status} /> },
        { label: 'Started', value: formatConnectionDateTime(latest.startedAt) },
        { label: 'Finished', value: latest.finishedAt ? formatConnectionDateTime(latest.finishedAt) : 'In progress' },
        { label: 'Duration', value: formatSyncDuration(latest.startedAt, latest.finishedAt) },
        ...(latest.status === SyncStatus.failed ? [{ label: 'Error', value: getSyncFailureMessage(latest.errorMessage) }] : []),
      ]} /> : <EmptyWorkspaceState title="Never synchronized" />}</WorkspaceSection>
    </div>
    {connection.ssh ? <WorkspaceSection title="SSH settings"><PropertyGrid items={[
      { label: 'Host', value: connection.ssh.host }, { label: 'Port', value: connection.ssh.port },
      { label: 'Username', value: connection.ssh.username },
      { label: 'Host key', value: connection.ssh.hostKeyFingerprint ?? 'Not pinned' },
      { label: 'Credentials', value: connection.ssh.credentialConfigured ? 'Configured' : 'Missing' },
    ]} /></WorkspaceSection> : null}
  </>
}

function ConnectionSyncHistory({ organizationId, connectionId, context }: { organizationId: string; connectionId: string; context: string }) {
  const history = useConnectionSyncSessions(organizationId, connectionId)
  return <WorkspaceSection title="Synchronization history" actions={history.data ? <span className="resource-count">{history.data.length} runs</span> : null}>
    {history.isPending ? <div className="row-skeleton" aria-label="Loading synchronization history"><span /><span /></div> : null}
    {history.isError ? <div className="inline-error" role="alert">Unable to load synchronization history
      <button type="button" className="text-button" onClick={() => history.refetch()}>Retry</button></div> : null}
    {history.data?.length === 0 ? <EmptyWorkspaceState title="No synchronization runs yet" /> : null}
    {history.data && history.data.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>Status</th><th>Started</th><th>Finished</th><th>Duration</th><th>Error</th></tr></thead>
      <tbody>{history.data.map(session => <tr key={session.id}>
        <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}/sync-sessions/${encodeURIComponent(session.id)}${context}`}>
          <SyncStatusBadge status={session.status} /></Link></td>
        <td>{formatConnectionDateTime(session.startedAt)}</td>
        <td>{session.finishedAt ? formatConnectionDateTime(session.finishedAt) : '—'}</td>
        <td>{formatSyncDuration(session.startedAt, session.finishedAt)}</td>
        <td className="truncate-cell">{session.status === SyncStatus.failed ? getSyncFailureMessage(session.errorMessage) : '—'}</td>
      </tr>)}</tbody>
    </table></div> : null}
  </WorkspaceSection>
}

function safeErrorMessage(error: Error | null): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}
