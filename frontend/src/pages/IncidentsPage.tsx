import { CheckCircle2, History, Siren } from 'lucide-react'
import { useSearchParams, useParams } from 'react-router-dom'

import { useIncidentPages } from '../api/incidents'
import { isUnavailableError } from '../components/layout/RefreshWarning'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, SegmentedControl, WorkspaceHeader, WorkspaceSection,
} from '../components/layout/WorkspacePrimitives'
import { useNow } from '../components/layout/useNow'
import { IncidentList } from '../components/incidents/IncidentList'
import { loadedCount, ShowMore } from '../components/infrastructure/ScopedIncidentsPanel'
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

/** Each empty filter means something different; only "no open incidents" is the healthy state. */
const emptyIcons = { OPEN: CheckCircle2, RESOLVED: History, ALL: Siren } as const

/**
 * The incident list of an organization, a page at a time. The filter is the `status` the list
 * request already takes, kept in the URL; each filter pages from its own first page, and each page
 * is one request however many resources its incidents name.
 */
function IncidentsContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.incidents
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = parseFilter(searchParams.get('status'))
  const queryStatus = filter === 'ALL' ? undefined : filter
  const incidentsQuery = useIncidentPages(organizationId, queryStatus)
  const now = useNow(60_000)
  const incidents = isUnavailableError(incidentsQuery.error) ? undefined : incidentsQuery.data?.pages.flat()

  const selectFilter = (next: Filter) => {
    setSearchParams(previous => { const updated = new URLSearchParams(previous); updated.set('status', next); return updated })
  }
  const retry = <button className="secondary-button" type="button" onClick={() => incidentsQuery.refetch()}>{i18n.t.common.retry}</button>

  return (
    <AppShell>
      <div className="workspace-page work-page">
        <WorkspaceHeader title={t.title} subtitle={t.subtitle} />
        <WorkspaceSection title={t.section[filter]} actions={incidents ? <span className="resource-count">{loadedCount(incidents.length, incidentsQuery.hasNextPage, i18n)}</span> : null}>
          <div className="filter-bar">
            <SegmentedControl name="incident-status" label={t.filterLabel} value={filter} onChange={selectFilter}
              options={(['OPEN', 'RESOLVED', 'ALL'] as const).map(value => ({ value, label: t.filters[value] }))} />
            <button className="secondary-button" type="button" onClick={() => incidentsQuery.refetch()}>{i18n.t.common.refresh}</button>
          </div>
          {incidentsQuery.isPending ? <div className="incident-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
          {incidentsQuery.isError && incidents === undefined ? (
            <InlineAlert tone="danger" title={t.loadError} action={retry}>{describeError(incidentsQuery.error, i18n)}</InlineAlert>
          ) : null}
          {/* A failed refresh keeps the last list and says how old it is. */}
          {incidentsQuery.isError && incidents !== undefined ? (
            <InlineAlert tone="warning" title={t.refreshError} action={retry}>
              {t.staleSince(i18n.format.dateTime(new Date(incidentsQuery.dataUpdatedAt).toISOString()))} {describeError(incidentsQuery.error, i18n)}
            </InlineAlert>
          ) : null}
          {incidents !== undefined && incidents.length === 0 ? (
            <EmptyWorkspaceState compact tone={filter === 'OPEN' ? 'success' : 'neutral'} icon={emptyIcons[filter]}
              title={t.empty[filter].title} detail={t.empty[filter].detail} />
          ) : null}
          {incidents !== undefined && incidents.length > 0 ? (
            <IncidentList organizationId={organizationId} incidents={incidents} now={now} />
          ) : null}
          <ShowMore query={incidentsQuery} />
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
