import { useState } from 'react'
import { useAvailableResourceOperations, useExecuteResourceOperation, useResourceOperationExecutions } from '../../api/resourceOperations'
import { useI18n } from '../../i18n'
import { secondsBetween } from '../../i18n/format'
import { describeError, describeFailure } from '../../i18n/errors'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useOrganizationPermissions } from '../auth/authorization'
import type { ResourceOperationCode } from '../../types/resourceOperation'
import { operationAppearance, operationStatusTones, operationsApplicability } from './operationPresentation'

const impactClass = { normal: '', caution: ' impact-caution', disruptive: ' impact-disruptive' } as const

/**
 * The controlled operations of one resource and what happened to the recent ones. A command is
 * sent only after confirmation, and nothing on the page assumes its effect: the resource changes
 * when the next synchronization reports it.
 */
export function ResourceOperationsPanel({ organizationId, resourceId, resourceName }: { organizationId: string; resourceId: string; resourceName: string }) {
  const i18n = useI18n()
  const t = i18n.t.operations
  const available = useAvailableResourceOperations(organizationId, resourceId)
  const history = useResourceOperationExecutions(organizationId, resourceId)
  const mutation = useExecuteResourceOperation(organizationId, resourceId)
  const canExecute = useOrganizationPermissions(organizationId).can('executeOperations')
  const [selected, setSelected] = useState<ResourceOperationCode | null>(null)
  const label = (operation: string) => t.labels[operation] ?? operation

  const execute = () => {
    if (!selected) return
    mutation.mutate(selected, { onSuccess: () => setSelected(null) })
  }

  if (operationsApplicability(available, history) === 'not-applicable') return null
  const running = history.data?.some(item => item.status === 'RUNNING') ?? false
  const selectedImpact = selected ? operationAppearance(selected).impact : 'normal'

  return <div className="resource-operations">
    <WorkspaceSection title={t.actions}>
      {available.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
      {available.isError ? <InlineAlert tone="danger" title={t.loadError}
        action={<button className="secondary-button" type="button" onClick={() => available.refetch()}>{i18n.t.common.retry}</button>} /> : null}
      {running ? <InlineAlert tone="info" title={t.statuses.RUNNING}>{t.runningNotice}</InlineAlert> : null}
      {available.data && available.data.operations.length === 0 ? <p className="muted-copy">{t.noneAvailable}</p> : null}
      {available.data && available.data.operations.length > 0 && canExecute ? <div className="operation-actions">
        {available.data.operations.map(operation => {
          const { Icon, impact } = operationAppearance(operation)
          return <button key={operation} className={`secondary-button operation-button${impactClass[impact]}`} type="button"
            disabled={mutation.isPending} onClick={() => setSelected(operation)}>
            <Icon aria-hidden size={15} />{label(operation)}</button>
        })}
      </div> : null}
      {available.data && available.data.operations.length > 0 && !canExecute ? <p className="muted-copy">{t.notPermitted}</p> : null}
      {mutation.isError ? <InlineAlert tone="danger" title={t.startFailed}>{describeError(mutation.error, i18n)}</InlineAlert> : null}
    </WorkspaceSection>

    <WorkspaceSection title={t.recent}>
      {history.isPending ? <div className="row-skeleton" aria-label={t.loadingHistory}><span /><span /></div> : null}
      {history.isError ? <InlineAlert tone="danger" title={t.historyError}
        action={<button className="secondary-button" type="button" onClick={() => history.refetch()}>{i18n.t.common.retry}</button>} /> : null}
      {history.data?.length === 0 ? <EmptyWorkspaceState compact title={t.noHistory} /> : null}
      {history.data && history.data.length > 0 ? <ol className="operation-history" aria-label={t.historyLabel}>
        {history.data.map(item => {
          const status = t.statuses[item.status] ?? item.status
          const took = item.finishedAt === null ? null : secondsBetween(item.startedAt, item.finishedAt)
          const failure = item.errorCode !== null || item.errorMessage !== null
            ? describeFailure(item.errorCode, item.errorMessage, i18n, status) : null
          return <li key={item.id} className={`operation-entry operation-${item.status.toLowerCase()}`}>
            <div className="operation-entry-heading">
              <span className="operation-entry-title">{label(item.operationCode)}</span>
              <StatusIndicator label={status} tone={operationStatusTones[item.status] ?? 'neutral'} />
              <time className="operation-entry-time" dateTime={item.startedAt} title={i18n.format.dateTime(item.startedAt)}>
                {i18n.format.relative(item.startedAt)}{took !== null ? ` · ${i18n.format.duration(took)}` : ''}</time>
            </div>
            {failure !== null ? <p className="operation-entry-detail">{failure}</p> : null}
          </li>
        })}
      </ol> : null}
    </WorkspaceSection>

    {selected ? <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget && !mutation.isPending) setSelected(null)
    }}><section className="monitor-rule-dialog" role="dialog" aria-modal="true" aria-labelledby="operation-confirm-title">
      <div className="dialog-heading"><h2 id="operation-confirm-title">{t.confirmTitle(label(selected))}</h2></div>
      <div className="dialog-body"><p>{t.confirmQuestion(label(selected), resourceName)}</p>
        <p className="muted-copy">{t.confirmDetail}</p></div>
      <div className="dialog-actions"><button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setSelected(null)}>{i18n.t.common.cancel}</button>
        <button className={selectedImpact === 'disruptive' ? 'danger-button' : 'primary-button'} type="button" disabled={mutation.isPending}
          onClick={execute}>{mutation.isPending ? t.running : i18n.t.common.confirm}</button></div>
    </section></div> : null}
  </div>
}
