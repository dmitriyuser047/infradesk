import { CheckCircle2 } from 'lucide-react'

import { useScopedIncidentPages, type IncidentScope } from '../../api/infrastructure'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import { IncidentList } from '../incidents/IncidentList'
import type { IncidentRowOptions } from '../incidents/IncidentRow'
import { EmptyWorkspaceState, InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useNow } from '../layout/useNow'

/**
 * The incidents of a connection's resources or of one resource: the open ones first, then the
 * resolved history, both a page at a time. Both lists are read only while this panel is shown, and
 * the two sections fail independently.
 *
 * `openTotal` is the authoritative count the page already holds (the connection summary or the
 * resource context). The heading shows it rather than how many rows happen to be loaded.
 */
export function ScopedIncidentsPanel({ organizationId, scope, options, openTotal }: {
  organizationId: string
  scope: IncidentScope
  options?: IncidentRowOptions
  openTotal?: number
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  const now = useNow(60_000)
  const open = useScopedIncidentPages(organizationId, scope, 'OPEN', true)
  const resolved = useScopedIncidentPages(organizationId, scope, 'RESOLVED', true)
  const openRows = open.data?.pages.flat() ?? []
  const history = resolved.data?.pages.flat() ?? []
  const total = openTotal !== undefined && openTotal >= openRows.length ? openTotal : undefined

  return <>
    <WorkspaceSection title={t.openIncidents} actions={open.data ? <span className="resource-count">
      {total === undefined ? loadedCount(openRows.length, open.hasNextPage, i18n)
        : openRows.length < total ? i18n.t.common.shown(openRows.length, total) : t.openIncidentCount(total)}</span> : null}>
      {open.isPending ? <div className="incident-skeleton" aria-label={t.loadingIncidents}><span /><span /></div> : null}
      {open.isError && openRows.length === 0 ? <InlineAlert tone="danger" title={t.incidentsError}
        action={<button className="secondary-button" type="button" onClick={() => open.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(open.error, i18n)}</InlineAlert> : null}
      {open.isSuccess && openRows.length === 0 ? <EmptyWorkspaceState compact tone="success" icon={CheckCircle2}
        title={t.noActiveIncidents} detail={t.noActiveIncidentsDetail} /> : null}
      {openRows.length > 0 ? <IncidentList organizationId={organizationId} incidents={openRows} now={now}
        label={t.openIncidents} options={options} /> : null}
      <ShowMore query={open} />
    </WorkspaceSection>
    <WorkspaceSection title={t.resolvedIncidents}>
      {resolved.isPending ? <div className="incident-skeleton" aria-label={t.loadingIncidents}><span /><span /></div> : null}
      {resolved.isError && history.length === 0 ? <InlineAlert tone="danger" title={t.incidentsError}
        action={<button className="secondary-button" type="button" onClick={() => resolved.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(resolved.error, i18n)}</InlineAlert> : null}
      {resolved.isSuccess && history.length === 0 ? <EmptyWorkspaceState compact title={t.noResolvedIncidents} /> : null}
      {history.length > 0 ? <IncidentList organizationId={organizationId} incidents={history} now={now}
        label={t.resolvedIncidents} options={options} /> : null}
      <ShowMore query={resolved} />
    </WorkspaceSection>
  </>
}

/** Without a known total, the loaded count says whether more exist: "50+", never a false total. */
export function loadedCount(count: number, more: boolean, i18n: ReturnType<typeof useI18n>): string {
  return more ? i18n.t.infrastructure.atLeast(count) : i18n.t.incidents.count(count)
}

/** The next page on request; a failed page keeps the rows already shown and offers a retry. */
export function ShowMore({ query }: {
  query: { hasNextPage: boolean; isFetchingNextPage: boolean; isFetchNextPageError: boolean; fetchNextPage: () => Promise<unknown> }
}) {
  const i18n = useI18n()
  if (!query.hasNextPage) return null
  return <div className="list-more">
    {query.isFetchNextPageError ? <span className="muted-cell" role="alert">{i18n.t.infrastructure.incidentsError}</span> : null}
    <button className="secondary-button" type="button" disabled={query.isFetchingNextPage}
      onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? i18n.t.common.loading : i18n.t.infrastructure.showMore}</button>
  </div>
}
