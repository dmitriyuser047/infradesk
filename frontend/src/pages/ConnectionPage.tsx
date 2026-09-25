import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSessions, useDeactivateConnection, useRunConnectionSync } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { useEnvironments, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { ConnectionStatusBadge } from '../components/connections/ConnectionStatusBadge'
import { environmentName, projectName } from '../components/connections/connectionContextPresentation'
import { formatScheduleInterval, formatSyncDuration, getConnectionScopeLabel,
  getConnectorTypeLabel, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import {
  CopyButton, EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { contextSearch } from '../components/layout/workspaceNavigation'
import { ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { ConnectionScopeType, SyncStatus, type ConnectionResponse } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

type Tab = 'overview' | 'synchronization'

export function ConnectionPage() {
  const { organizationId, connectionId } = useParams()
  if (!organizationId || !connectionId) return <InvalidRoutePage />
  return <ConnectionContent organizationId={organizationId} connectionId={connectionId} />
}

function ConnectionContent({ organizationId, connectionId }: { organizationId: string; connectionId: string }) {
  const i18n = useI18n()
  const t = i18n.t.connections.page
  const tabs: { id: Tab; label: string }[] = [
    { id: 'overview', label: t.tabs.overview }, { id: 'synchronization', label: t.tabs.synchronization },
  ]
  const connectionQuery = useConnection(organizationId, connectionId)
  const projectsQuery = useProjects(organizationId)
  const scope = connectionQuery.data?.scope
  const scopedProjectId = scope && scope.type !== ConnectionScopeType.organization ? scope.projectId : null
  const environmentsQuery = useEnvironments(organizationId, scopedProjectId)
  const permissions = useOrganizationPermissions(organizationId)
  const deactivate = useDeactivateConnection(organizationId, connectionId)
  const sync = useRunConnectionSync(organizationId, connectionId)
  const [tab, setTab] = useState<Tab>('overview')
  const navigate = useNavigate()
  const location = useLocation()
  const context = contextSearch(new URLSearchParams(location.search))
  const canManage = permissions.can('manageConnections')
  const canSync = permissions.can('runConnectionSync')
  const connectionBase = `/organizations/${encodeURIComponent(organizationId)}/connections`
  const back = `${connectionBase}${context}`

  if (connectionQuery.isPending) return <AppShell><PageLoading title={t.loading} back={{ label: t.back, to: back }} label={t.loading} /></AppShell>
  if (connectionQuery.isError || !connectionQuery.data) {
    return <AppShell><PageUnavailable back={{ label: t.back, to: back }} onRetry={() => connectionQuery.refetch()} error={connectionQuery.error}
      notFound={connectionQuery.error instanceof ApiError && connectionQuery.error.code === 'CONNECTION_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  }
  const connection = connectionQuery.data

  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={connection.name}
      subtitle={`${getConnectorTypeLabel(connection.connectorType, i18n)} · ${getConnectionScopeLabel(connection.scope, i18n)} · ${connection.code}`}
      back={{ label: t.back, to: back }} status={<ConnectionStatusBadge active={connection.active} />}
      actions={<>
        {canSync && connection.active ? <button className="primary-button" type="button"
          disabled={sync.isPending || connection.lastSync?.status === SyncStatus.running}
          onClick={() => sync.mutate()}>{sync.isPending ? t.synchronizing : t.syncNow}</button> : null}
        {canManage && connection.active && connection.connectorType === 'SSH' ? <>
          <Link className="secondary-button" to={`${connectionBase}/${encodeURIComponent(connectionId)}/edit${context}`}>{i18n.t.common.edit}</Link>
          <details className="toolbar-overflow"><summary aria-label={t.moreActions} title={t.moreActions}>⋯</summary>
            <button type="button" className="danger-action" disabled={deactivate.isPending} onClick={() => {
              if (window.confirm(t.deactivateConfirm)) {
                deactivate.mutate(undefined, { onSuccess: () => navigate(back) })
              }
            }}>{t.deactivate}</button>
          </details>
        </> : null}
      </>} />
    {connection.lastSync?.errorCode === 'SSH_HOST_KEY_MISMATCH' ? <InlineAlert tone="danger"
      title={i18n.t.connections.form.statusMismatch}>{i18n.t.connections.form.statusMismatchDetail}</InlineAlert> : null}
    {deactivate.isError ? <InlineAlert tone="danger" title={describeError(deactivate.error, i18n)} /> : null}
    {sync.isError ? <InlineAlert tone="danger" title={describeError(sync.error, i18n)} /> : null}
    {sync.isSuccess ? <InlineAlert tone={sync.data.status === SyncStatus.failed ? 'danger' : 'success'}
      title={sync.data.status === SyncStatus.failed ? getSyncFailureMessage(sync.data, i18n) : t.syncCompleted} /> : null}
    <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
      {tab === 'overview' ? <ConnectionOverview connection={connection}
        projects={projectsQuery.data} environments={environmentsQuery.data} /> :
        <ConnectionSyncHistory organizationId={organizationId} connectionId={connectionId} context={context} />}
    </div>
  </div></AppShell>
}

function ConnectionOverview({ connection, projects, environments }: {
  connection: ConnectionResponse
  projects: ReturnType<typeof useProjects>['data']
  environments: ReturnType<typeof useEnvironments>['data']
}) {
  const i18n = useI18n()
  const t = i18n.t.connections.page
  const scopeItems = connection.scope.type === ConnectionScopeType.organization ? [] : [
    { label: t.project, value: projectName(connection.scope.projectId, projects) },
    ...(connection.scope.type === ConnectionScopeType.environment ?
      [{ label: t.environment, value: environmentName(connection.scope.environmentId, environments) }] : []),
  ]
  const latest = connection.lastSync
  const ssh = connection.ssh
  return <>
    <div className="workspace-split detail-split">
      <WorkspaceSection title={t.connection}><PropertyGrid items={[
        { label: i18n.t.common.code, value: connection.code, technical: true },
        { label: i18n.t.common.type, value: getConnectorTypeLabel(connection.connectorType, i18n) },
        { label: t.scope, value: getConnectionScopeLabel(connection.scope, i18n) },
        ...scopeItems,
        { label: t.schedule, value: connection.schedule?.enabled ? formatScheduleInterval(connection.schedule.intervalSeconds, i18n) : i18n.t.common.manual },
        ...(connection.schedule?.enabled ? [{ label: t.nextRun, value: i18n.format.dateTime(connection.schedule.nextRunAt) }] : []),
      ]} /></WorkspaceSection>
      <WorkspaceSection title={t.latest}>{latest ? <PropertyGrid items={[
        { label: i18n.t.common.status, value: <SyncStatusBadge status={latest.status} /> },
        { label: t.started, value: i18n.format.dateTime(latest.startedAt) },
        { label: t.finished, value: latest.finishedAt ? i18n.format.dateTime(latest.finishedAt) : i18n.t.common.inProgress },
        { label: t.duration, value: formatSyncDuration(latest.startedAt, latest.finishedAt, i18n) },
        ...(latest.status === SyncStatus.failed ? [{ label: t.error, value: getSyncFailureMessage(latest, i18n) }] : []),
      ]} /> : <EmptyWorkspaceState title={t.neverSynced} />}</WorkspaceSection>
    </div>
    {ssh ? <WorkspaceSection title={t.ssh}><PropertyGrid items={[
      { label: t.host, value: ssh.host, technical: true }, { label: t.port, value: ssh.port, technical: true },
      { label: t.username, value: ssh.username, technical: true },
      { label: t.authentication, value: i18n.t.connections.authTypes[ssh.authenticationType] ?? ssh.authenticationType },
      { label: i18n.t.connections.columns.trust, value: latest?.errorCode === 'SSH_HOST_KEY_MISMATCH'
        ? <StatusIndicator label={i18n.t.connections.form.statusMismatch} tone="danger" icon={ShieldAlert} />
        : ssh.hostTrusted ? <StatusIndicator label={i18n.t.connections.trust.trusted} tone="success" icon={ShieldCheck} />
          : <StatusIndicator label={i18n.t.connections.trust.untrusted} tone="warning" icon={ShieldQuestion} /> },
      { label: t.hostKey, value: ssh.hostKeyFingerprint
        ? <span className="fingerprint-value"><code className="fingerprint">{ssh.hostKeyFingerprint}</code><CopyButton value={ssh.hostKeyFingerprint} /></span>
        : t.notPinned },
      { label: t.credentials, value: ssh.credentialConfigured
        // Named the way the SSH form names it: which credential is stored, never the credential.
        ? <StatusIndicator label={ssh.authenticationType === 'PASSWORD' ? i18n.t.connections.form.passwordConfigured
          : i18n.t.connections.form.privateKeyConfigured} tone="success" /> : t.credentialsMissing },
    ]} /></WorkspaceSection> : null}
  </>
}

function ConnectionSyncHistory({ organizationId, connectionId, context }: { organizationId: string; connectionId: string; context: string }) {
  const i18n = useI18n()
  const t = i18n.t.connections.page
  const history = useConnectionSyncSessions(organizationId, connectionId)
  return <WorkspaceSection title={t.history} actions={history.data ? <span className="resource-count">{t.runs(history.data.length)}</span> : null}>
    {history.isPending ? <div className="row-skeleton" aria-label={t.loadingHistory}><span /><span /></div> : null}
    {history.isError ? <InlineAlert tone="danger" title={t.historyError}
      action={<button type="button" className="secondary-button" onClick={() => history.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {history.data?.length === 0 ? <EmptyWorkspaceState title={t.noRuns} /> : null}
    {history.data && history.data.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{i18n.t.common.status}</th><th>{t.started}</th><th>{t.finished}</th><th>{t.duration}</th><th>{t.error}</th></tr></thead>
      <tbody>{history.data.map(session => <tr key={session.id}>
        <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}/sync-sessions/${encodeURIComponent(session.id)}${context}`}>
          <SyncStatusBadge status={session.status} /></Link></td>
        <td>{i18n.format.dateTime(session.startedAt)}</td>
        <td>{session.finishedAt ? i18n.format.dateTime(session.finishedAt) : '—'}</td>
        <td>{formatSyncDuration(session.startedAt, session.finishedAt, i18n)}</td>
        <td className="truncate-cell">{session.status === SyncStatus.failed ? getSyncFailureMessage(session, i18n) : '—'}</td>
      </tr>)}</tbody>
    </table></div> : null}
  </WorkspaceSection>
}
