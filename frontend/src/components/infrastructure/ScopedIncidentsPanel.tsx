import { CheckCircle2 } from 'lucide-react'

import { useOpenScopedIncidents, useResolvedScopedIncidents, type IncidentScope } from '../../api/infrastructure'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import { IncidentList } from '../incidents/IncidentList'
import type { IncidentRowOptions } from '../incidents/IncidentRow'
import { EmptyWorkspaceState, InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useNow } from '../layout/useNow'

/**
 * The incidents of a connection's resources or of one resource: every open one first, then the
 * resolved history a page at a time. Both lists are read only while this panel is shown, and the
 * two sections fail independently.
 */
export function ScopedIncidentsPanel({ organizationId, scope, options }: {
  organizationId: string
  scope: IncidentScope
  options?: IncidentRowOptions
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  const now = useNow(60_000)
  const open = useOpenScopedIncidents(organizationId, scope, true)
  const resolved = useResolvedScopedIncidents(organizationId, scope, true)
  const history = resolved.data?.pages.flat() ?? []

  return <>
    <WorkspaceSection title={t.openIncidents}
      actions={open.data ? <span className="resource-count">{t.openIncidentCount(open.data.length)}</span> : null}>
      {open.isPending ? <div className="incident-skeleton" aria-label={t.loadingIncidents}><span /><span /></div> : null}
      {open.isError ? <InlineAlert tone="danger" title={t.incidentsError}
        action={<button className="secondary-button" type="button" onClick={() => open.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(open.error, i18n)}</InlineAlert> : null}
      {open.data?.length === 0 ? <EmptyWorkspaceState compact tone="success" icon={CheckCircle2}
        title={t.noActiveIncidents} detail={t.noActiveIncidentsDetail} /> : null}
      {open.data && open.data.length > 0 ? <IncidentList organizationId={organizationId} incidents={open.data} now={now}
        label={t.openIncidents} options={options} /> : null}
    </WorkspaceSection>
    <WorkspaceSection title={t.resolvedIncidents}>
      {resolved.isPending ? <div className="incident-skeleton" aria-label={t.loadingIncidents}><span /><span /></div> : null}
      {resolved.isError && history.length === 0 ? <InlineAlert tone="danger" title={t.incidentsError}
        action={<button className="secondary-button" type="button" onClick={() => resolved.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(resolved.error, i18n)}</InlineAlert> : null}
      {resolved.isSuccess && history.length === 0 ? <EmptyWorkspaceState compact title={t.noResolvedIncidents} /> : null}
      {history.length > 0 ? <IncidentList organizationId={organizationId} incidents={history} now={now}
        label={t.resolvedIncidents} options={options} /> : null}
      {resolved.hasNextPage ? <div className="list-more">
        <button className="secondary-button" type="button" disabled={resolved.isFetchingNextPage}
          onClick={() => void resolved.fetchNextPage()}>{resolved.isFetchingNextPage ? i18n.t.common.loading : t.showMore}</button>
      </div> : null}
    </WorkspaceSection>
  </>
}
