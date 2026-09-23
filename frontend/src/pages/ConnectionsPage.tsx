import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'

import { useConnections } from '../api/connections'
import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { getEnvironments, useProjects } from '../api/navigation'
import { ConnectionList } from '../components/connections/ConnectionList'
import { filterConnections } from '../components/connections/connectionFilters'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionsPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <ConnectionsContent organizationId={organizationId} />
}

function ConnectionsContent({ organizationId }: { organizationId: string }) {
  const connectionsQuery = useConnections(organizationId)
  const projectsQuery = useProjects(organizationId)
  const scopedProjectIds = [...new Set((connectionsQuery.data ?? []).flatMap(connection =>
    connection.scope.type === 'ENVIRONMENT' ? [connection.scope.projectId] : []))]
  const environmentQueries = useQueries({ queries: scopedProjectIds.map(projectId => ({
    queryKey: ['environments', organizationId, projectId],
    queryFn: () => getEnvironments(organizationId, projectId),
  })) })
  const environments = environmentQueries.flatMap(query => query.data ?? [])
  const location = useLocation()
  const membership = useMyOrganizations()
  const isOwner = membership.data?.find(value => value.id === organizationId)?.role === 'OWNER'
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const filtered = filterConnections(connectionsQuery.data ?? [], search, type, status)

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title="Connections" subtitle="Infrastructure integrations and synchronization"
          actions={isOwner ? <Link className="primary-button" to={`/organizations/${organizationId}/connections/new${location.search}`}>+ New connection</Link> : null} />
        <WorkspaceSection title="Configured connections" actions={connectionsQuery.data ? <span className="resource-count">{filtered.length} of {connectionsQuery.data.length}</span> : null}>
          <div className="filter-bar">
            <label>Search<input type="search" value={search} placeholder="Name or code" onChange={event => setSearch(event.target.value)} /></label>
            <label>Type<select value={type} onChange={event => setType(event.target.value)}><option value="ALL">All</option>
              <option value="SSH">SSH</option><option value="DOCKER">Docker</option></select></label>
            <label>Status<select value={status} onChange={event => setStatus(event.target.value)}><option value="ALL">All</option>
              <option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label>
            <button className="secondary-button" type="button" onClick={() => connectionsQuery.refetch()}>Refresh</button>
          </div>
          {connectionsQuery.isPending ? <ConnectionsListSkeleton /> : null}
          {connectionsQuery.isError ? (
            <div className="connection-state connection-state-error" role="alert">
              <h3>Unable to load connections</h3>
              <p>{safeErrorMessage(connectionsQuery.error)}</p>
              <button className="retry-button" type="button" onClick={() => connectionsQuery.refetch()}>Retry</button>
            </div>
          ) : null}
          {!connectionsQuery.isPending && !connectionsQuery.isError && connectionsQuery.data?.length === 0 ? (
            <EmptyWorkspaceState title="No connections configured" detail="Add a connection to synchronize infrastructure."
              action={isOwner ? <Link to={`/organizations/${organizationId}/connections/new${location.search}`}>New connection</Link> : undefined} />
          ) : null}
          {!connectionsQuery.isPending && !connectionsQuery.isError && connectionsQuery.data !== undefined && connectionsQuery.data.length > 0 ? (
            filtered.length ? <ConnectionList organizationId={organizationId} connections={filtered}
              projects={projectsQuery.data} environments={environments} /> :
              <EmptyWorkspaceState title="No connections match these filters" />
          ) : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}

function ConnectionsListSkeleton() {
  return <div className="connection-skeleton" aria-label="Loading connections"><span /><span /><span /></div>
}

function safeErrorMessage(error: Error): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}
