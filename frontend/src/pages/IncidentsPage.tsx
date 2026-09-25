import { useSearchParams, useParams } from 'react-router-dom'

import { useIncidents } from '../api/incidents'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { IncidentList } from '../components/incidents/IncidentList'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { IncidentStatus } from '../types/incident'
import { InvalidRoutePage } from './InvalidRoutePage'

type Filter = 'OPEN' | 'RESOLVED' | 'ALL'

export function IncidentsPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <IncidentsContent organizationId={organizationId} />
}

function IncidentsContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.incidents
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = parseFilter(searchParams.get('status'))
  const queryStatus = filter === 'ALL' ? undefined : filter
  const incidentsQuery = useIncidents(organizationId, queryStatus)

  const selectFilter = (next: Filter) => {
    setSearchParams(previous => { const updated = new URLSearchParams(previous); updated.set('status', next); return updated })
  }

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title={t.title} subtitle={t.subtitle} />
        <WorkspaceSection title={t.section[filter]} actions={incidentsQuery.data ? <span className="resource-count">{t.count(incidentsQuery.data.length)}</span> : null}>
          <div className="filter-bar" aria-label={t.filterLabel}>
            <label>{i18n.t.common.status}<select value={filter} onChange={event => selectFilter(event.target.value as Filter)}>
              <option value="OPEN">{t.filters.OPEN}</option><option value="RESOLVED">{t.filters.RESOLVED}</option><option value="ALL">{t.filters.ALL}</option>
            </select></label>
            <button className="secondary-button" type="button" onClick={() => incidentsQuery.refetch()}>{i18n.t.common.refresh}</button>
          </div>
          {incidentsQuery.isPending ? <div className="incident-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
          {incidentsQuery.isError ? (
            <div className="incident-state incident-state-error" role="alert">
              <h3>{t.loadError}</h3>
              <p>{describeError(incidentsQuery.error, i18n)}</p>
              <button className="retry-button" type="button" onClick={() => incidentsQuery.refetch()}>{i18n.t.common.retry}</button>
            </div>
          ) : null}
          {!incidentsQuery.isPending && !incidentsQuery.isError && incidentsQuery.data?.length === 0 ? (
            <EmptyWorkspaceState title={t.empty[filter].title} detail={t.empty[filter].detail} />
          ) : null}
          {!incidentsQuery.isPending && !incidentsQuery.isError && incidentsQuery.data !== undefined && incidentsQuery.data.length > 0 ? (
            <IncidentList organizationId={organizationId} incidents={incidentsQuery.data} />
          ) : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}

function parseFilter(value: string | null): Filter {
  if (value === IncidentStatus.resolved) {
    return IncidentStatus.resolved
  }
  if (value === 'ALL') {
    return 'ALL'
  }
  return IncidentStatus.open
}
