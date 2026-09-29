import { useState } from 'react'
import { useQueries, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Cable, Plus } from 'lucide-react'

import { useConnections } from '../api/connections'
import { useConnectionInfrastructureCounts } from '../api/infrastructure'
import { getEnvironments, useProjects } from '../api/navigation'
import { ConnectionList } from '../components/connections/ConnectionList'
import { filterConnections } from '../components/connections/connectionFilters'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { useSlowPending } from '../components/layout/useSlowPending'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
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
  const newPath = `/organizations/${organizationId}/connections/new${location.search}`

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title={t.title} subtitle={t.subtitle}
          actions={isOwner ? <Link className="primary-button" to={newPath}><Plus aria-hidden size={16} />{t.add}</Link> : null} />
        <WorkspaceSection title={t.section} actions={connectionsQuery.data ? <span className="resource-count">{i18n.t.common.shown(filtered.length, connectionsQuery.data.length)}</span> : null}>
          <div className="filter-bar">
            <label>{i18n.t.common.search}<input type="search" value={search} placeholder={i18n.t.common.searchPlaceholder} onChange={event => setSearch(event.target.value)} /></label>
            <label>{i18n.t.common.type}<select value={type} onChange={event => setType(event.target.value)}><option value="ALL">{i18n.t.common.all}</option>
              <option value="SSH">SSH</option><option value="DOCKER">Docker</option></select></label>
            <label>{i18n.t.common.status}<select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">{i18n.t.common.all}</option>
              <option value="ACTIVE">{i18n.t.common.active}</option><option value="INACTIVE">{i18n.t.common.inactive}</option></select></label>
            <button className="secondary-button" type="button" onClick={() => connectionsQuery.refetch()}>{i18n.t.common.refresh}</button>
          </div>
          {connectionsQuery.isPending && !slowLoading ? <div className="connection-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
          {slowLoading ? <InlineAlert tone="warning" title={t.slowLoading}
            action={<button className="secondary-button" type="button" onClick={retryConnections}>{i18n.t.common.retry}</button>}>
            {i18n.t.common.slowLoadingDetail}</InlineAlert> : null}
          {connectionsQuery.isError ? <InlineAlert tone="danger" title={t.loadError}
            action={<button className="secondary-button" type="button" onClick={() => connectionsQuery.refetch()}>{i18n.t.common.retry}</button>}>
            {describeError(connectionsQuery.error, i18n)}</InlineAlert> : null}
          {!connectionsQuery.isPending && !connectionsQuery.isError && connectionsQuery.data?.length === 0 ? (
            <EmptyWorkspaceState icon={Cable} title={t.empty} detail={isOwner ? t.emptyDetail : t.emptyMember}
              action={isOwner ? <Link className="primary-button" to={newPath}><Plus aria-hidden size={16} />{t.add}</Link> : undefined} />
          ) : null}
          {!connectionsQuery.isPending && !connectionsQuery.isError && connectionsQuery.data !== undefined && connectionsQuery.data.length > 0 ? (
            filtered.length ? <ConnectionList organizationId={organizationId} connections={filtered}
              projects={projectsQuery.data} environments={environments} counts={counts} /> :
              <EmptyWorkspaceState title={t.noMatch} />
          ) : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}
