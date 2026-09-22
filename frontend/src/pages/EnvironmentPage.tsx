import { useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useEnvironmentResources } from '../api/resources'
import { AppShell } from '../components/layout/AppShell'
import { ResourceTree } from '../components/resources/ResourceTree'
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

  return (
    <AppShell>
      <header className="page-header">
        <div>
          <p className="eyebrow">InfraDesk</p>
          <h1>Environment</h1>
          <p className="page-subtitle">{shortId(environmentId)}</p>
        </div>
      </header>
      <section className="content-panel" aria-labelledby="infrastructure-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Inventory</p>
            <h2 id="infrastructure-heading">Infrastructure</h2>
          </div>
          {resourcesQuery.data !== undefined ? (
            <span className="resource-count">{resourcesQuery.data.length} resources</span>
          ) : null}
        </div>
        {resourcesQuery.isPending ? <ResourceTreeSkeleton /> : null}
        {resourcesQuery.isError ? <ResourceTreeError error={resourcesQuery.error} retry={resourcesQuery.refetch} /> : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length === 0 ? <ResourceTreeEmpty /> : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length > 0 ? (
          <ResourceTree
            resources={resourcesQuery.data}
            organizationId={organizationId}
            environmentId={environmentId}
          />
        ) : null}
      </section>
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
    <div className="state-message">
      <h3>No resources discovered yet</h3>
      <p>Resources will appear here after an infrastructure connection is synchronized.</p>
    </div>
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
