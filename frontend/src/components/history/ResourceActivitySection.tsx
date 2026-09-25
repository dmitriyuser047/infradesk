import { useResourceHistory } from '../../api/history'
import { EmptyWorkspaceState, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { ActivityTimeline } from './ActivityTimeline'

/** The timeline of one resource: what happened to it, newest first. */
export function ResourceActivitySection({ organizationId, resourceId }: {
  organizationId: string
  resourceId: string
}) {
  const history = useResourceHistory(organizationId, resourceId)
  const events = history.data?.pages.flat() ?? []

  return <WorkspaceSection title="Activity">
    {history.isPending ? <div className="row-skeleton" aria-label="Loading activity"><span /><span /></div> : null}
    {history.isError ? <div className="inline-error" role="alert">Unable to load activity.
      <button className="text-button" type="button" onClick={() => history.refetch()}>Retry</button></div> : null}
    {!history.isPending && !history.isError && events.length === 0 ? (
      <EmptyWorkspaceState title="Nothing has happened yet"
        detail="Discovery, incidents and operations appear here as they happen." />
    ) : null}
    {events.length > 0 ? <ActivityTimeline events={events} /> : null}
    {history.hasNextPage ? (
      <button className="secondary-button" type="button" disabled={history.isFetchingNextPage}
        onClick={() => history.fetchNextPage()}>
        {history.isFetchingNextPage ? 'Loading…' : 'Load more'}
      </button>
    ) : null}
  </WorkspaceSection>
}
