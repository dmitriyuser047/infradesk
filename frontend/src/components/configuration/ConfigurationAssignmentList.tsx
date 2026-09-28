import { Link } from 'react-router-dom'

import { useConfigurationAssignmentPages, useRemoveConfigurationAssignment } from '../../api/configurationAssignments'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationAssignment, ConfigurationAssignmentFilter } from '../../types/configurationAssignment'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import '../../styles/pages/configurations.css'

export const assignmentPath = (organizationId: string, assignmentId: string) =>
  `/organizations/${encodeURIComponent(organizationId)}/configuration-assignments/${encodeURIComponent(assignmentId)}`

export function newAssignmentPath(organizationId: string, preset: ConfigurationAssignmentFilter): string {
  const query = new URLSearchParams()
  if (preset.profileId) query.set('profileId', preset.profileId)
  if (preset.resourceId) query.set('resourceId', preset.resourceId)
  const search = query.toString()
  return `/organizations/${encodeURIComponent(organizationId)}/configuration-assignments/new${search ? `?${search}` : ''}`
}

export const resourcePagePath = (organizationId: string, assignment: ConfigurationAssignment) =>
  `/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(assignment.resource.environment.id)}` +
  `/resources/${encodeURIComponent(assignment.resource.id)}`

/**
 * What an assignment's state is, in words that claim nothing about the server: it is assigned, its
 * target may be inactive, its profile may be archived or have a newer version. Never "applied".
 */
export function AssignmentStatus({ assignment }: { assignment: ConfigurationAssignment }) {
  const t = useI18n().t.assignments
  return <span className="assignment-status">
    {assignment.resource.active
      ? <StatusIndicator label={t.assigned} tone="info" />
      : <StatusIndicator label={t.targetInactive} tone="warning" />}
    {assignment.profile.archived ? <StatusIndicator label={t.profileArchived} tone="neutral" /> : null}
    {!assignment.profile.archived && assignment.profile.latestRevisionNumber > assignment.profileRevisionNumber
      ? <StatusIndicator label={t.newerAvailable} tone="neutral" /> : null}
  </span>
}

/**
 * The active assignments of a profile (its targets) or of a node (its configurations), a page at a
 * time. One read per page, whatever the rows mention; the full values are never listed.
 */
export function ConfigurationAssignmentList({ organizationId, filter, view, canAssign = true }: {
  organizationId: string
  filter: ConfigurationAssignmentFilter
  view: 'profile' | 'resource'
  canAssign?: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  const pages = useConfigurationAssignmentPages(organizationId, filter, true)
  const remove = useRemoveConfigurationAssignment(organizationId)
  const rows = pages.data?.pages.flat() ?? []
  const title = view === 'profile' ? t.targetsTitle : t.resourceTitle
  const confirmRemove = (assignment: ConfigurationAssignment) => {
    if (window.confirm(t.removeConfirm)) remove.mutate(assignment)
  }

  return <WorkspaceSection title={title} description={<>{view === 'profile' ? t.targetsDescription : t.resourceDescription} {t.notApplied}</>}
    actions={canAssign ? <Link className="secondary-button" to={newAssignmentPath(organizationId, filter)}>
      {view === 'profile' ? t.assign : t.assignConfiguration}</Link> : undefined}>
    {pages.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {pages.isError ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" type="button" onClick={() => pages.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(pages.error, i18n)}</InlineAlert> : null}
    {remove.isError ? <InlineAlert tone="danger" title={describeError(remove.error, i18n)} /> : null}
    {pages.isSuccess && rows.length === 0
      ? <EmptyWorkspaceState compact title={view === 'profile' ? t.empty : t.emptyResource}
        detail={view === 'profile' ? t.emptyDetail : t.emptyResourceDetail} /> : null}
    {rows.length > 0 ? <div className="table-scroll"><table className="data-grid assignment-grid">
      <thead><tr>
        {view === 'profile'
          ? <><th scope="col">{t.columns.resource}</th><th scope="col">{t.columns.environment}</th></>
          : <th scope="col">{t.columns.profile}</th>}
        <th scope="col">{t.columns.path}</th>
        <th scope="col">{view === 'profile' ? t.columns.assigned : t.columns.desired}</th>
        <th scope="col">{t.columns.status}</th>
        <th scope="col"><span className="visually-hidden">{t.columns.actions}</span></th>
      </tr></thead>
      <tbody>{rows.map(assignment => <tr key={assignment.id}>
        {view === 'profile' ? <>
          <td><Link className="grid-link" to={`${resourcePagePath(organizationId, assignment)}?tab=configurations`}>{assignment.resource.name}</Link></td>
          <td>{assignment.resource.environment.name}</td>
        </> : <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organizationId)}/configurations/${encodeURIComponent(assignment.profile.id)}`}>
          {assignment.profile.name}</Link><small className="cell-secondary technical-value">{assignment.profile.code}</small></td>}
        <td><code className="technical-value">{assignment.targetPath}</code></td>
        <td>{i18n.t.configurations.version(assignment.profileRevisionNumber)}
          {assignment.profile.latestRevisionNumber !== assignment.profileRevisionNumber
            ? <small className="cell-secondary">{t.latestVersion(assignment.profile.latestRevisionNumber)}</small> : null}</td>
        <td><AssignmentStatus assignment={assignment} /></td>
        <td className="assignment-actions">
          <Link className="text-button" to={`${assignmentPath(organizationId, assignment.id)}?from=${view}`}
            aria-label={t.editLabel(assignment.targetPath)}>{t.edit}</Link>
          <button type="button" className="text-button danger-text" disabled={remove.isPending}
            aria-label={t.removeLabel(assignment.targetPath)} onClick={() => confirmRemove(assignment)}>{t.remove}</button>
        </td>
      </tr>)}</tbody>
    </table></div> : null}
    {pages.hasNextPage ? <button type="button" className="secondary-button" disabled={pages.isFetchingNextPage}
      onClick={() => pages.fetchNextPage()}>{t.showMore}</button> : null}
  </WorkspaceSection>
}
