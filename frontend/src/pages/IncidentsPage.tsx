import { useSearchParams, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useIncidents } from '../api/incidents'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { IncidentList } from '../components/incidents/IncidentList'
import { IncidentStatus, type KnownIncidentStatus } from '../types/incident'
import { InvalidRoutePage } from './InvalidRoutePage'

export function IncidentsPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <IncidentsContent organizationId={organizationId} />
}

function IncidentsContent({ organizationId }: { organizationId: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = parseFilter(searchParams.get('status'))
  const queryStatus = filter === 'ALL' ? undefined : filter
  const incidentsQuery = useIncidents(organizationId, queryStatus)

  const selectFilter = (next: 'OPEN' | 'RESOLVED' | 'ALL') => {
    setSearchParams(previous => { const updated = new URLSearchParams(previous); updated.set('status', next); return updated })
  }

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title="Incidents" subtitle="Infrastructure events for this organization" />
        <WorkspaceSection title={`${filterLabel(filter)} incidents`} actions={incidentsQuery.data ? <span className="resource-count">{incidentsQuery.data.length} incidents</span> : null}>
          <div className="filter-bar" aria-label="Incident status filter">
            <label>Status<select value={filter} onChange={event => selectFilter(event.target.value as 'OPEN' | 'RESOLVED' | 'ALL')}>
              <option value="OPEN">Open</option><option value="RESOLVED">Resolved</option><option value="ALL">All</option>
            </select></label>
            <button className="secondary-button" type="button" onClick={() => incidentsQuery.refetch()}>Refresh</button>
          </div>
          {incidentsQuery.isPending ? <IncidentListSkeleton /> : null}
          {incidentsQuery.isError ? (
            <div className="incident-state incident-state-error" role="alert">
              <h3>Unable to load incidents</h3>
              <p>{safeErrorMessage(incidentsQuery.error)}</p>
              <button className="retry-button" type="button" onClick={() => incidentsQuery.refetch()}>Retry</button>
            </div>
          ) : null}
          {!incidentsQuery.isPending && !incidentsQuery.isError && incidentsQuery.data?.length === 0 ? (
            <EmptyWorkspaceState title={emptyTitle(filter)} detail={emptyDescription(filter)} />
          ) : null}
          {!incidentsQuery.isPending && !incidentsQuery.isError && incidentsQuery.data !== undefined && incidentsQuery.data.length > 0 ? (
            <IncidentList organizationId={organizationId} incidents={incidentsQuery.data} />
          ) : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}

function parseFilter(value: string | null): 'OPEN' | 'RESOLVED' | 'ALL' {
  if (value === IncidentStatus.resolved) {
    return IncidentStatus.resolved
  }
  if (value === 'ALL') {
    return 'ALL'
  }
  return IncidentStatus.open
}

function filterLabel(filter: 'OPEN' | 'RESOLVED' | 'ALL'): string {
  return filter === 'OPEN' ? 'Open' : filter === 'RESOLVED' ? 'Resolved' : 'All'
}

function emptyTitle(filter: 'OPEN' | 'RESOLVED' | 'ALL'): string {
  return filter === 'OPEN' ? 'No open incidents' : filter === 'RESOLVED' ? 'No resolved incidents' : 'No incidents recorded yet'
}

function emptyDescription(filter: 'OPEN' | 'RESOLVED' | 'ALL'): string {
  return filter === 'OPEN' ? 'Nothing currently requires attention.' : filter === 'RESOLVED' ? 'Resolved incidents will appear here.' : 'Incidents created by infrastructure monitoring will appear here.'
}

function safeErrorMessage(error: Error): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}

function IncidentListSkeleton() {
  return <div className="incident-skeleton" aria-label="Loading incidents"><span /><span /><span /></div>
}
