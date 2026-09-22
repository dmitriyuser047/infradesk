import { useSearchParams, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useIncidents } from '../api/incidents'
import { AppShell } from '../components/layout/AppShell'
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
    setSearchParams({ status: next })
  }

  return (
    <AppShell>
      <div className="incidents-page">
        <header className="page-header incidents-page-header">
          <div>
            <p className="eyebrow">Operations</p>
            <h1>Incidents</h1>
            <p className="page-subtitle">Persisted infrastructure events for this organization</p>
          </div>
        </header>
        <div className="incident-filters" aria-label="Incident status filter">
          <FilterButton active={filter === 'OPEN'} onClick={() => selectFilter('OPEN')}>Open</FilterButton>
          <FilterButton active={filter === 'RESOLVED'} onClick={() => selectFilter('RESOLVED')}>Resolved</FilterButton>
          <FilterButton active={filter === 'ALL'} onClick={() => selectFilter('ALL')}>All</FilterButton>
        </div>
        <section className="content-panel incidents-panel" aria-labelledby="incident-list-heading">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">{filter === 'OPEN' ? 'Needs attention' : 'History'}</p>
              <h2 id="incident-list-heading">{filterLabel(filter)} incidents</h2>
            </div>
            {incidentsQuery.data !== undefined ? <span className="resource-count">{incidentsQuery.data.length} incidents</span> : null}
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
            <div className="incident-state">
              <h3>{emptyTitle(filter)}</h3>
              <p>{emptyDescription(filter)}</p>
            </div>
          ) : null}
          {!incidentsQuery.isPending && !incidentsQuery.isError && incidentsQuery.data !== undefined && incidentsQuery.data.length > 0 ? (
            <IncidentList organizationId={organizationId} incidents={incidentsQuery.data} />
          ) : null}
        </section>
      </div>
    </AppShell>
  )
}

function FilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: string }) {
  return <button className={`incident-filter ${active ? 'incident-filter-active' : ''}`} type="button" aria-pressed={active} onClick={onClick}>{children}</button>
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
