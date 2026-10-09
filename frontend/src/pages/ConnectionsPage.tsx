import { useState } from 'react'
import { useQueries, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Cable, Plus, Search, RefreshCw, Power, Terminal, Boxes } from 'lucide-react'

import { useConnections } from '../api/connections'
import { useConnectionInfrastructureCounts } from '../api/infrastructure'
import { getEnvironments, useProjects } from '../api/navigation'
import { ConnectionList } from '../components/connections/ConnectionList'
import { filterConnections } from '../components/connections/connectionFilters'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { isUnavailableError, RefreshWarning } from '../components/layout/RefreshWarning'
import { AppShell } from '../components/layout/AppShell'
import { useSlowPending } from '../components/layout/useSlowPending'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection, WorkspaceMetrics } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionsPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <ConnectionsContent organizationId={organizationId} />
}

function ConnectionsContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.connections
  const connectionsQuery = useConnections(organizationId)
  const slowLoading = useSlowPending(connectionsQuery.isPending)
  const queryClient = useQueryClient()
  // A first load still in flight is not restarted by refetch(): drop it, then ask again.
  const retryConnections = () => {
    void queryClient.cancelQueries({ queryKey: ['connections', organizationId] }).then(() => connectionsQuery.refetch())
  }
  const projectsQuery = useProjects(organizationId)
  const scopedProjectIds = [...new Set((connectionsQuery.data ?? []).flatMap(connection =>
    connection.scope.type === 'ENVIRONMENT' ? [connection.scope.projectId] : []))]
  const environmentQueries = useQueries({ queries: scopedProjectIds.map(projectId => ({
    queryKey: ['environments', organizationId, projectId],
    queryFn: () => getEnvironments(organizationId, projectId),
  })) })
  const environments = environmentQueries.flatMap(query => query.data ?? [])
  const location = useLocation()
  const permissions = useOrganizationPermissions(organizationId)
  const isOwner = permissions.can('manageConnections')
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const filtered = filterConnections(connectionsQuery.data ?? [], search, type, status)
  // One grouped read for every row; the list still renders without it.
  const countsQuery = useConnectionInfrastructureCounts(organizationId)
  const counts = countsQuery.data ? new Map(countsQuery.data.map(value => [value.connectionId, value])) : undefined
  const connections = isUnavailableError(connectionsQuery.error) ? undefined : connectionsQuery.data
  const newPath = `/organizations/${organizationId}/connections/new${location.search}`

  return (
    <AppShell>
      <div className="workspace-page work-page connections-page">
        <WorkspaceHeader title={t.title} subtitle={t.subtitle}
          actions={isOwner ? <Link className="primary-button" to={newPath}><Plus aria-hidden size={16} />{t.add}</Link> : null} />
        {connections ? <WorkspaceMetrics items={[
          { label: i18n.t.design.configuredConnections, value: connections.length, icon: Cable, detail: i18n.t.design.listSnapshot },
          { label: i18n.t.design.activeConnections, value: connections.filter(item => item.active).length, icon: Power,
            detail: i18n.t.design.activeNotHealth, onSelect: () => { setSearch(''); setType('ALL'); setStatus('ACTIVE') } },
          { label: 'SSH', value: connections.filter(item => item.connectorType === 'SSH').length, icon: Terminal,
            detail: i18n.t.design.connectionType, onSelect: () => { setSearch(''); setStatus('ALL'); setType('SSH') } },
          { label: 'Docker', value: connections.filter(item => item.connectorType === 'DOCKER').length, icon: Boxes,
            detail: i18n.t.design.connectionType, onSelect: () => { setSearch(''); setStatus('ALL'); setType('DOCKER') } },
        ]} /> : null}
        <WorkspaceSection title={t.section} actions={connectionsQuery.data ? <span className="resource-count">{i18n.t.common.shown(filtered.length, connectionsQuery.data.length)}</span> : null}>
          <div className="filter-bar list-filter-bar">
            <div className="search-field"><Search className="search-field-icon" size={16} aria-hidden />
              <input type="search" aria-label={i18n.t.common.search} value={search} placeholder={i18n.t.workScreens.connectionSearch} onChange={event => setSearch(event.target.value)} /></div>
            <label>{i18n.t.common.status}<select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">{i18n.t.common.all}</option>
              <option value="ACTIVE">{i18n.t.common.active}</option><option value="INACTIVE">{i18n.t.common.inactive}</option></select></label>
            <label>{i18n.t.common.type}<select value={type} onChange={event => setType(event.target.value)}><option value="ALL">{i18n.t.common.all}</option>
              <option value="SSH">SSH</option><option value="DOCKER">Docker</option></select></label>
            <button className="secondary-button" type="button" onClick={() => connectionsQuery.refetch()}><RefreshCw size={15} aria-hidden />{i18n.t.common.refresh}</button>
          </div>
          {connectionsQuery.isPending && !slowLoading ? <div className="connection-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
          {slowLoading ? <InlineAlert tone="warning" title={t.slowLoading}
            action={<button className="secondary-button" type="button" onClick={retryConnections}>{i18n.t.common.retry}</button>}>
            {i18n.t.common.slowLoadingDetail}</InlineAlert> : null}
          {connectionsQuery.isError && connections ? <RefreshWarning updatedAt={connectionsQuery.dataUpdatedAt} retry={() => connectionsQuery.refetch()} /> : null}
          {connectionsQuery.isError && !connections ? <InlineAlert tone="danger" title={t.loadError}
            action={<button className="secondary-button" type="button" onClick={() => connectionsQuery.refetch()}>{i18n.t.common.retry}</button>}>
            {describeError(connectionsQuery.error, i18n)}</InlineAlert> : null}
          {!connectionsQuery.isPending && connections?.length === 0 ? (
            <EmptyWorkspaceState icon={Cable} title={t.empty} detail={isOwner ? t.emptyDetail : t.emptyMember}
              action={isOwner ? <Link className="primary-button" to={newPath}><Plus aria-hidden size={16} />{t.add}</Link> : undefined} />
          ) : null}
          {!connectionsQuery.isPending && connections !== undefined && connections.length > 0 ? (
            filtered.length ? <ConnectionList organizationId={organizationId} connections={filtered}
              projects={projectsQuery.data} environments={environments} counts={counts} /> :
              <EmptyWorkspaceState compact title={t.noMatch} detail={i18n.t.resources.filter.noResultsDetail}
                action={<button className="secondary-button" type="button" onClick={() => { setSearch(''); setType('ALL'); setStatus('ALL') }}>{i18n.t.resources.filter.reset}</button>} />
          ) : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}
