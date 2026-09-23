import { useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useEnvironmentResources } from '../api/resources'
import { useEnvironments } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { ResourceTree } from '../components/resources/ResourceTree'
import { resourcePresentationRegistry } from '../components/resources/presentation/resourcePresentations'
import { filterResourcesForTree } from '../components/resources/filterResourcesForTree'
import { InvalidRoutePage } from './InvalidRoutePage'

export function EnvironmentPage() {
  const { organizationId, environmentId } = useParams()

  if (organizationId === undefined || environmentId === undefined) {
    return <InvalidRoutePage />
  }

  return <EnvironmentContent organizationId={organizationId} environmentId={environmentId} />
}

interface EnvironmentContentProps {
  organizationId: string
  environmentId: string
}

function EnvironmentContent({ organizationId, environmentId }: EnvironmentContentProps) {
  const resourcesQuery = useEnvironmentResources(organizationId, environmentId)
  const [searchParams] = useSearchParams()
  const projectId = searchParams.get('project')
  const environments = useEnvironments(organizationId, projectId)
  const environment = environments.data?.find(item => item.id === environmentId)
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const visibleResources = filterResourcesForTree(resourcesQuery.data ?? [], search, type)

  return (
    <AppShell>
      <div className="workspace-page">
      <WorkspaceHeader title="Infrastructure" subtitle={environment ? `${environment.name} · ${environment.kind}` : `Environment · ${shortId(environmentId)}`} />
      <WorkspaceSection title="Resources" actions={resourcesQuery.data ? <span className="resource-count">{visibleResources.length} of {resourcesQuery.data.length}</span> : null}>
        <div className="filter-bar">
          <label>Search<input type="search" placeholder="Name or code" value={search} onChange={event => setSearch(event.target.value)} /></label>
          <label>Type<select value={type} onChange={event => setType(event.target.value)}><option value="ALL">All</option>
            {resourcePresentationRegistry.list().map(presentation =>
              <option key={presentation.code} value={presentation.code}>{presentation.label}</option>)}</select></label>
          <button className="secondary-button" type="button" onClick={() => resourcesQuery.refetch()}>Refresh</button>
        </div>
        {resourcesQuery.isPending ? <ResourceTreeSkeleton /> : null}
        {resourcesQuery.isError ? <ResourceTreeError error={resourcesQuery.error} retry={resourcesQuery.refetch} /> : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length === 0 ? <ResourceTreeEmpty /> : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length > 0 ? (
          visibleResources.length ? <div className="table-scroll"><div className="tree-grid-header"><span>Name</span><span>Type</span><span>Status</span></div><ResourceTree
            resources={visibleResources}
            organizationId={organizationId}
            environmentId={environmentId}
          /></div> : <EmptyWorkspaceState title="No resources match these filters" />
        ) : null}
      </WorkspaceSection></div>
    </AppShell>
  )
}

function ResourceTreeSkeleton() {
  return (
    <div className="tree-skeleton" aria-label="Loading resources">
      <span />
      <span />
      <span />
      <span />
    </div>
  )
}

function ResourceTreeEmpty() {
  return (
    <EmptyWorkspaceState title="No resources discovered yet" detail="Resources appear after a connection is synchronized." />
  )
}

interface ResourceTreeErrorProps {
  error: Error
  retry: () => void
}

function ResourceTreeError({ error, retry }: ResourceTreeErrorProps) {
  const message = error instanceof ApiError ? error.message : 'Please try again shortly.'

  return (
    <div className="state-message state-message-error" role="alert">
      <h3>Unable to load infrastructure</h3>
      <p>{message}</p>
      <button className="retry-button" type="button" onClick={() => retry()}>Retry</button>
    </div>
  )
}

function shortId(value: string): string {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}
