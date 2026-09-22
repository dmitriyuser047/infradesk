import { Link, useParams } from 'react-router-dom'

import { useConnections } from '../api/connections'
import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { ConnectionList } from '../components/connections/ConnectionList'
import { AppShell } from '../components/layout/AppShell'
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
  const membership = useMyOrganizations()
  const isOwner = membership.data?.find(value => value.id === organizationId)?.role === 'OWNER'

  return (
    <AppShell>
      <div className="connections-page">
        <header className="page-header connections-page-header">
          <div>
            <p className="eyebrow">Operations</p>
            <h1>Connections</h1>
            <p className="page-subtitle">Infrastructure integrations and synchronization status</p>
          </div>
          {isOwner ? <Link className="retry-button" to={`/organizations/${organizationId}/connections/new`}>Add SSH connection</Link> : null}
        </header>
        <section className="content-panel connections-panel" aria-labelledby="connections-list-heading">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Integrations</p>
              <h2 id="connections-list-heading">Configured connections</h2>
            </div>
            {connectionsQuery.data !== undefined ? <span className="resource-count">{connectionsQuery.data.length} connections</span> : null}
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
            <div className="connection-state">
              <h3>No connections configured</h3>
              <p>Infrastructure connections will appear here once they are configured.</p>
            </div>
          ) : null}
          {!connectionsQuery.isPending && !connectionsQuery.isError && connectionsQuery.data !== undefined && connectionsQuery.data.length > 0 ? (
            <ConnectionList organizationId={organizationId} connections={connectionsQuery.data} />
          ) : null}
        </section>
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
