import { useResourceHistory } from '../../api/history'
import { useI18n } from '../../i18n'
import { EmptyWorkspaceState, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { ActivityTimeline } from './ActivityTimeline'

/** The timeline of one resource: what happened to it, newest first. */
export function ResourceActivitySection({ organizationId, resourceId }: {
  organizationId: string
  resourceId: string
}) {
  const { t } = useI18n()
  const history = useResourceHistory(organizationId, resourceId)
  const events = history.data?.pages.flat() ?? []

  return <WorkspaceSection title={t.history.title}>
    {history.isPending ? <div className="row-skeleton" aria-label={t.history.loading}><span /><span /></div> : null}
    {history.isError ? <div className="inline-error" role="alert">{t.history.loadError}
      <button className="text-button" type="button" onClick={() => history.refetch()}>{t.common.retry}</button></div> : null}
    {!history.isPending && !history.isError && events.length === 0 ? (
      <EmptyWorkspaceState title={t.history.empty} detail={t.history.emptyDetail} />
    ) : null}
    {events.length > 0 ? <ActivityTimeline events={events} /> : null}
    {history.hasNextPage ? (
      <button className="secondary-button" type="button" disabled={history.isFetchingNextPage}
        onClick={() => history.fetchNextPage()}>
        {history.isFetchingNextPage ? t.history.loadingMore : t.history.loadMore}
      </button>
    ) : null}
  </WorkspaceSection>
}
