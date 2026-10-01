import { lazy, Suspense, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { CheckCircle2, Server, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'

import { useConnection, useConnectionSyncSessions, useDeactivateConnection, useRunConnectionSync } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { useConnectionInfrastructureSummary, useConnectionResources } from '../api/infrastructure'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { useEnvironments, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { ConnectionStatusBadge } from '../components/connections/ConnectionStatusBadge'
import { environmentName, projectName } from '../components/connections/connectionContextPresentation'
import { formatScheduleInterval, formatSyncDuration, getConnectionScopeLabel,
  getConnectorTypeLabel, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { IncidentList } from '../components/incidents/IncidentList'
import { InfrastructureContextPath, type ContextPathItem } from '../components/infrastructure/InfrastructureContextPath'
import { environmentPath, originPath, originQuery, projectPath, readOrigin, workspaceQuery } from '../components/infrastructure/infrastructureLinks'
import { groupResourcesByEnvironment } from '../components/infrastructure/resourceGroups'
import { ScopedIncidentsPanel } from '../components/infrastructure/ScopedIncidentsPanel'
import { AppShell } from '../components/layout/AppShell'
import {
  CopyButton, EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { useNow } from '../components/layout/useNow'
import { ResourceTree } from '../components/resources/ResourceTree'
import { ConnectionScopeType, SyncStatus, type ConnectionResponse } from '../types/connection'
import type { ConnectionInfrastructureSummary } from '../types/infrastructure'
import { InvalidRoutePage } from './InvalidRoutePage'

const TerminalPanel = lazy(() => import('../components/terminal/TerminalPanel').then(module => ({ default: module.TerminalPanel })))
const Tabs = ['overview', 'resources', 'terminal', 'incidents', 'synchronization'] as const
type Tab = typeof Tabs[number]

export function ConnectionPage() {
  const { organizationId, connectionId } = useParams()
  if (!organizationId || !connectionId) return <InvalidRoutePage />
  return <ConnectionContent organizationId={organizationId} connectionId={connectionId} />
}

/**
 * One infrastructure source: what it discovered, what is wrong with it and how its
 * synchronization goes, with its technical settings as supporting detail. A connection is where
 * resources were discovered, not their owner; the resources and incidents shown are those linked to
 * it through discovery. The overview reads a bounded summary; the full lists load with their tab.
 */
function ConnectionContent({ organizationId, connectionId }: { organizationId: string; connectionId: string }) {
  const i18n = useI18n()
  const t = i18n.t.connections.page
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = parseTab(searchParams.get('tab'))
  const connectionQuery = useConnection(organizationId, connectionId)
  const summaryQuery = useConnectionInfrastructureSummary(organizationId, connectionId)
  const projectsQuery = useProjects(organizationId)
  const scope = connectionQuery.data?.scope
  const scopedProjectId = scope && scope.type !== ConnectionScopeType.organization ? scope.projectId : null
  const environmentsQuery = useEnvironments(organizationId, scopedProjectId)
  const permissions = useOrganizationPermissions(organizationId)
  const deactivate = useDeactivateConnection(organizationId, connectionId)
  const sync = useRunConnectionSync(organizationId, connectionId)
  const navigate = useNavigate()
  const context = workspaceQuery(searchParams)
  const canManage = permissions.can('manageConnections')
  const canSync = permissions.can('runConnectionSync')
  const connectionBase = `/organizations/${encodeURIComponent(organizationId)}/connections`
  // Back to the resource or incident this connection was opened from, otherwise to the list.
  const origin = readOrigin(searchParams)
  const backLink = origin && origin.kind !== 'connection'
    ? { label: origin.kind === 'incident' ? i18n.t.incidents.page.crumb : i18n.t.infrastructure.resource,
      to: originPath(organizationId, origin, context) }
    : { label: t.back, to: `${connectionBase}${context}` }
  // Links out of this page remember it, so "back" on the resource or incident returns here.
  const linkQuery = originQuery(searchParams, { kind: 'connection', id: connectionId })
  const selectTab = (next: Tab) => setSearchParams(previous => {
    const updated = new URLSearchParams(previous)
    if (next === 'overview') updated.delete('tab')
    else updated.set('tab', next)
    return updated
  }, { replace: true })

  if (connectionQuery.isPending) return <AppShell><PageLoading title={t.loading} back={backLink} label={t.loading} /></AppShell>
  if (connectionQuery.isError || !connectionQuery.data) {
    return <AppShell><PageUnavailable back={backLink} onRetry={() => connectionQuery.refetch()} error={connectionQuery.error}
      notFound={connectionQuery.error instanceof ApiError && connectionQuery.error.code === 'CONNECTION_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  }
  const connection = connectionQuery.data
  const summary = summaryQuery.data
  const tabs: { id: Tab; label: string; count?: number }[] = [
    { id: 'overview', label: t.tabs.overview },
    { id: 'resources', label: t.tabs.resources, count: summary?.activeResourceCount },
    // Open incidents are counted only when there are some; the overview says so either way.
    { id: 'incidents', label: t.tabs.incidents, count: summary?.openIncidentCount || undefined },
    { id: 'synchronization', label: t.tabs.synchronization },
  ]
  const syncAction = canSync && connection.active ? <button className="primary-button" type="button"
    disabled={sync.isPending || connection.lastSync?.status === SyncStatus.running}
    onClick={() => sync.mutate()}>{sync.isPending ? t.synchronizing : t.syncNow}</button> : null
  const canOpenTerminal = connection.connectorType === 'SSH' && permissions.can('openTerminal')
  if (canOpenTerminal) tabs.splice(2, 0, { id: 'terminal', label: i18n.t.terminal.title })
  const activeTab = tab === 'terminal' && !canOpenTerminal ? 'overview' : tab

  return <AppShell><div className="workspace-page">
    <InfrastructureContextPath items={connectionPathItems(organizationId, connection, projectsQuery.data, environmentsQuery.data)} />
    <WorkspaceHeader title={connection.name}
      subtitle={`${getConnectorTypeLabel(connection.connectorType, i18n)} · ${getConnectionScopeLabel(connection.scope, i18n)} · ${connection.code}`}
      back={backLink} status={<ConnectionStatusBadge active={connection.active} />}
      actions={<>
        {syncAction}
        {canManage && connection.active && connection.connectorType === 'SSH' ? <>
          <Link className="secondary-button" to={`${connectionBase}/${encodeURIComponent(connectionId)}/edit${context}`}>{i18n.t.common.edit}</Link>
          <details className="toolbar-overflow"><summary aria-label={t.moreActions} title={t.moreActions}>⋯</summary>
            <button type="button" className="danger-action" disabled={deactivate.isPending} onClick={() => {
              if (window.confirm(t.deactivateConfirm)) {
                deactivate.mutate(undefined, { onSuccess: () => navigate(`${connectionBase}${context}`) })
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
    <WorkspaceTabs tabs={tabs} active={activeTab} onChange={selectTab} />
    <div role="tabpanel" id={`panel-${activeTab}`} aria-labelledby={`tab-${activeTab}`}>
      {activeTab === 'terminal' ? <Suspense fallback={<div className="row-skeleton" />}><TerminalPanel
        organizationId={organizationId} connection={connection}
        editLink={canManage ? `${connectionBase}/${encodeURIComponent(connectionId)}/edit${context}` : undefined} /></Suspense> : null}
      {activeTab === 'overview' ? <>
        <InfrastructureOverview organizationId={organizationId} connection={connection} query={summaryQuery}
          linkQuery={linkQuery} syncAction={syncAction} openTab={selectTab} />
        <ConnectionOverview connection={connection} projects={projectsQuery.data} environments={environmentsQuery.data} />
      </> : null}
      {activeTab === 'resources' ? <ConnectionResources organizationId={organizationId} connection={connection}
        summary={summary} linkQuery={linkQuery} syncAction={syncAction} /> : null}
      {activeTab === 'incidents' ? <ScopedIncidentsPanel organizationId={organizationId} scope={{ kind: 'connection', id: connectionId }}
        options={{ linkQuery }} openTotal={summary?.openIncidentCount} /> : null}
      {activeTab === 'synchronization' ? <ConnectionSyncHistory organizationId={organizationId} connectionId={connectionId} context={context} /> : null}
    </div>
  </div></AppShell>
}

function parseTab(value: string | null): Tab {
  return Tabs.find(tab => tab === value) ?? 'overview'
}

/** The connection's own scope, by name. Shown only once the names are known: never an identifier. */
function connectionPathItems(
  organizationId: string,
  connection: ConnectionResponse,
  projects: ReturnType<typeof useProjects>['data'],
  environments: ReturnType<typeof useEnvironments>['data'],
): ContextPathItem[] {
  const scope = connection.scope
  if (scope.type === ConnectionScopeType.organization) return []
  const project = projects?.find(value => value.id === scope.projectId)
  if (!project) return []
  const items: ContextPathItem[] = [{ label: project.name, to: projectPath(organizationId, project.id) }]
  if (scope.type === ConnectionScopeType.environment) {
    const environment = environments?.find(value => value.id === scope.environmentId)
    if (!environment) return []
    items.push({ label: environment.name, to: environmentPath(organizationId, project.id, environment.id) })
  }
  return [...items, { label: connection.name }]
}

/**
 * What the connection discovered and what is wrong with it, from one bounded summary read. A
 * failed summary is reported here alone; the connection's own details below stay usable.
 */
function InfrastructureOverview({ organizationId, connection, query, linkQuery, syncAction, openTab }: {
  organizationId: string
  connection: ConnectionResponse
  query: ReturnType<typeof useConnectionInfrastructureSummary>
  linkQuery: string
  syncAction: ReactNode
  openTab: (tab: Tab) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  const now = useNow(60_000)
  if (query.isPending) return <div className="row-skeleton" aria-label={t.loadingSummary}><span /><span /><span /></div>
  if (query.isError || !query.data) {
    return <InlineAlert tone="danger" title={t.summaryError}
      action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(query.error, i18n)}</InlineAlert>
  }
  const summary = query.data
  const latest = connection.lastSync
  const groups = groupResourcesByEnvironment(summary.resources)

  return <>
    <dl className="summary-strip">
      <div className="summary-item">
        <dt>{t.activeResources}</dt>
        <dd><button type="button" className="summary-value" onClick={() => openTab('resources')}>{summary.activeResourceCount}</button>
          <small>{typeCounts(summary, i18n)}</small>
          {summary.inactiveResourceCount > 0 ? <><br /><small>{t.inactiveCount(summary.inactiveResourceCount)}</small></> : null}</dd>
      </div>
      <div className="summary-item">
        <dt>{t.openIncidents}</dt>
        <dd><button type="button" className={`summary-value ${summary.openIncidentCount > 0 ? 'summary-alert' : ''}`}
          onClick={() => openTab('incidents')}>{summary.openIncidentCount}</button>
          <small>{summary.openIncidentCount > 0 ? t.openIncidentCount(summary.openIncidentCount) : t.noActiveIncidents}</small></dd>
      </div>
      <div className="summary-item">
        <dt>{t.lastSync}</dt>
        <dd>{latest ? <><SyncStatusBadge status={latest.status} />
          <small><time dateTime={latest.startedAt} title={i18n.format.dateTime(latest.startedAt)}>
            {i18n.format.relative(latest.startedAt, now)}</time></small></>
          : <span className="muted-cell">{i18n.t.connections.neverSynchronized}</span>}</dd>
      </div>
    </dl>
    <div className="workspace-split detail-split">
      <WorkspaceSection title={t.infrastructure} actions={summary.activeResourceCount > 0 ? <button className="text-button" type="button"
        onClick={() => openTab('resources')}>{t.viewAllResources}</button> : null}>
        {summary.activeResourceCount === 0 ? <NoResources connection={connection} syncAction={syncAction} />
          : groups.map(group => <div key={group.environment.id} className="resource-group">
            {groups.length > 1 ? <h3 className="resource-group-title">{group.environment.name}</h3> : null}
            <ResourceTree roots={group.roots} organizationId={organizationId} linkQuery={linkQuery}
              label={`${t.infrastructure} · ${group.environment.name}`} />
          </div>)}
      </WorkspaceSection>
      <WorkspaceSection title={t.openIncidents} actions={summary.openIncidentCount > 0 ? <button className="text-button" type="button"
        onClick={() => openTab('incidents')}>{t.viewAllIncidents}</button> : null}>
        {summary.openIncidents.length === 0 ? <EmptyWorkspaceState compact tone="success" icon={CheckCircle2}
          title={t.noActiveIncidents} detail={t.noActiveIncidentsDetail} />
          : <IncidentList organizationId={organizationId} incidents={summary.openIncidents} now={now}
            label={t.openIncidents} options={{ linkQuery, showContext: false }} />}
      </WorkspaceSection>
    </div>
  </>
}

function typeCounts(summary: ConnectionInfrastructureSummary, i18n: ReturnType<typeof useI18n>): string {
  return [...summary.resourceTypeCounts]
    .sort((a, b) => Number(b.resourceTypeCode === 'NODE') - Number(a.resourceTypeCode === 'NODE'))
    .map(count => i18n.t.infrastructure.resourceTypeCount(count.resourceTypeCode, count.count))
    .join(' · ')
}

/** Before the first synchronization there is nothing yet; after one, nothing was found. */
function NoResources({ connection, syncAction }: { connection: ConnectionResponse; syncAction: ReactNode }) {
  const { t } = useI18n()
  return connection.lastSync === null
    ? <EmptyWorkspaceState compact icon={Server} title={t.infrastructure.resourcesAfterSync}
      detail={t.infrastructure.resourcesAfterSyncDetail} action={syncAction ?? undefined} />
    : <EmptyWorkspaceState compact icon={Server} title={t.infrastructure.noResources} detail={t.infrastructure.noResourcesDetail} />
}

/**
 * Every active resource the connection discovered, one tree per environment: a connection scoped
 * to an organization or project may reach several, and they are never mixed without saying which.
 */
function ConnectionResources({ organizationId, connection, summary, linkQuery, syncAction }: {
  organizationId: string
  connection: ConnectionResponse
  summary: ConnectionInfrastructureSummary | undefined
  linkQuery: string
  syncAction: ReactNode
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  const query = useConnectionResources(organizationId, connection.id, true)
  const resources = query.data
  const groups = resources ? groupResourcesByEnvironment(resources) : []
  const columns = i18n.t.resources.columns

  return <WorkspaceSection title={t.resources}
    actions={resources ? <span className="resource-count">{t.resourceCount(summary?.activeResourceCount ?? resources.length)}</span> : null}>
    {query.isPending ? <div className="tree-skeleton" aria-label={t.loadingResources}><span /><span /><span /></div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.resourcesError}
      action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(query.error, i18n)}</InlineAlert> : null}
    {resources?.length === 0 ? <NoResources connection={connection} syncAction={syncAction} /> : null}
    {resources && summary && resources.length < summary.activeResourceCount
      ? <InlineAlert tone="info" title={t.truncated(resources.length, summary.activeResourceCount)} /> : null}
    {groups.map(group => <div key={group.environment.id} className="resource-group">
      {groups.length > 1 ? <h3 className="resource-group-title">{group.environment.name}</h3> : null}
      <div className="table-scroll">
        <div className="tree-grid-header"><span>{columns.name}</span><span>{columns.type}</span><span>{columns.status}</span></div>
        <ResourceTree roots={group.roots} organizationId={organizationId} linkQuery={linkQuery}
          label={`${t.resources} · ${group.environment.name}`} />
      </div>
    </div>)}
  </WorkspaceSection>
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
