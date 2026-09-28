import { Fragment, useState } from 'react'

import { useDeployment, useDeploymentHistory } from '../../api/configurationDeployments'
import { useRolloutHistory } from '../../api/configurationRollouts'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import { secondsBetween } from '../../i18n/format'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { DeploymentStatusView } from './ConfigurationDeploymentPanel'
import { RolloutStatusView } from './ConfigurationRolloutPanel'
import { deploymentTone, failureText, rolloutTone } from './deploymentPresentation'

function DeploymentDetailRow({ organizationId, id, columns }: { organizationId: string; id: string; columns: number }) {
  const i18n = useI18n()
  const detail = useDeployment(organizationId, id)
  return <tr className="history-detail-row"><td colSpan={columns}>
    {detail.data ? <>
      <DeploymentStatusView deployment={detail.data} />
      <ol className="deployment-events" aria-label={i18n.t.deployments.events}>{detail.data.events.map(event =>
        <li key={event.sequence}><span className="technical-value">{event.type}</span> · {i18n.format.time(event.occurredAt)}</li>)}</ol>
    </> : null}
    {detail.isError ? <InlineAlert tone="danger" title={describeError(detail.error, i18n)} /> : null}
  </td></tr>
}

/** Deployment history of a profile or a node, a page at a time. File content never appears here. */
export function DeploymentHistory({ organizationId, profileId, resourceId }: {
  organizationId: string; profileId?: string; resourceId?: string
}) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  const query = useDeploymentHistory(organizationId, { profileId, resourceId })
  const rows = query.data?.pages.flatMap(page => page.items) ?? []
  const [open, setOpen] = useState<string | null>(null)
  return <WorkspaceSection title={i18n.t.configurations.tabs.deployments} description={t.driftNote}>
    {query.isError ? <InlineAlert tone="danger" title={describeError(query.error, i18n)} /> : null}
    {query.isSuccess && rows.length === 0 ? <EmptyWorkspaceState compact title={t.empty} /> : null}
    {rows.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr>
        <th scope="col">{t.columns.resource}</th><th scope="col">{t.columns.revision}</th>
        <th scope="col">{t.columns.path}</th><th scope="col">{t.columns.state}</th>
        <th scope="col">{t.columns.started}</th><th scope="col">{t.columns.duration}</th>
        <th scope="col">{t.columns.actor}</th><th scope="col"><span className="visually-hidden">{t.columns.detail}</span></th>
      </tr></thead>
      <tbody>{rows.map(row => <Fragment key={row.id}>
        <tr>
          <td>{row.resource.name}</td>
          <td>{i18n.t.configurations.version(row.profileRevisionNumber)}</td>
          <td><code className="technical-value">{row.targetPath}</code></td>
          <td><StatusIndicator label={t.states[row.state]} tone={deploymentTone(row.state)} />
            {row.failureCode ? <small className="cell-secondary">{failureText(row.failureCode, i18n)}</small> : null}</td>
          <td>{i18n.format.dateTime(row.startedAt ?? row.createdAt)}</td>
          <td>{row.startedAt ? i18n.format.duration(secondsBetween(row.startedAt, row.finishedAt)) : '—'}</td>
          <td>{row.actor.name}</td>
          <td><button type="button" className="text-button" aria-expanded={open === row.id}
            onClick={() => setOpen(open === row.id ? null : row.id)}>{open === row.id ? t.close : t.open}</button></td>
        </tr>
        {open === row.id ? <DeploymentDetailRow organizationId={organizationId} id={row.id} columns={8} /> : null}
      </Fragment>)}</tbody>
    </table></div> : null}
    {query.hasNextPage ? <button type="button" className="secondary-button" disabled={query.isFetchingNextPage}
      onClick={() => void query.fetchNextPage()}>{t.showMore}</button> : null}
  </WorkspaceSection>
}

/** Rollouts of a profile: version, nodes, outcome, duration and author; details on demand. */
export function RolloutHistory({ organizationId, profileId }: { organizationId: string; profileId: string }) {
  const i18n = useI18n()
  const t = i18n.t.rollouts
  const query = useRolloutHistory(organizationId, profileId)
  const rows = query.data?.pages.flatMap(page => page.items) ?? []
  const [open, setOpen] = useState<string | null>(null)
  return <WorkspaceSection title={i18n.t.configurations.tabs.rollouts}>
    {query.isError ? <InlineAlert tone="danger" title={describeError(query.error, i18n)} /> : null}
    {query.isSuccess && rows.length === 0 ? <EmptyWorkspaceState compact title={t.empty} /> : null}
    {rows.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr>
        <th scope="col">{t.columns.revision}</th><th scope="col">{t.columns.nodes}</th>
        <th scope="col">{t.columns.state}</th><th scope="col">{t.columns.started}</th>
        <th scope="col">{t.columns.duration}</th><th scope="col">{t.columns.actor}</th>
        <th scope="col"><span className="visually-hidden">{t.open}</span></th>
      </tr></thead>
      <tbody>{rows.map(row => <Fragment key={row.id}>
        <tr>
          <td>{i18n.t.configurations.version(row.profileRevisionNumber)}</td>
          <td>{t.nodes(row.counts.items)}</td>
          <td><StatusIndicator label={t.state[row.state]} tone={rolloutTone(row.state)} /></td>
          <td>{i18n.format.dateTime(row.startedAt ?? row.createdAt)}</td>
          <td>{row.startedAt ? i18n.format.duration(secondsBetween(row.startedAt, row.finishedAt)) : '—'}</td>
          <td>{row.actor.name}</td>
          <td><button type="button" className="text-button" aria-expanded={open === row.id}
            onClick={() => setOpen(open === row.id ? null : row.id)}>{open === row.id ? t.close : t.open}</button></td>
        </tr>
        {open === row.id ? <tr className="history-detail-row"><td colSpan={7}>
          <RolloutStatusView organizationId={organizationId} rolloutId={row.id} /></td></tr> : null}
      </Fragment>)}</tbody>
    </table></div> : null}
    {query.hasNextPage ? <button type="button" className="secondary-button" disabled={query.isFetchingNextPage}
      onClick={() => void query.fetchNextPage()}>{t.showMore}</button> : null}
  </WorkspaceSection>
}
