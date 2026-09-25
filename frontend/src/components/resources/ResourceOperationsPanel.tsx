import { useState } from 'react'
import { useAvailableResourceOperations, useExecuteResourceOperation, useResourceOperationExecutions } from '../../api/resourceOperations'
import { useI18n } from '../../i18n'
import { describeError, describeFailure } from '../../i18n/errors'
import { StatusIndicator, WorkspaceSection, type StatusTone } from '../layout/WorkspacePrimitives'
import { useOrganizationPermissions } from '../auth/authorization'
import type { OperationExecutionStatus, ResourceOperationCode } from '../../types/resourceOperation'

const statusTones: Record<OperationExecutionStatus, StatusTone> = {
  RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'danger', UNKNOWN: 'warning',
}

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

  if (available.data?.operations.length === 0 && history.data?.length === 0) return null

  return <WorkspaceSection title={t.title}>
    {available.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {available.isError ? <div className="inline-error" role="alert">{t.loadError}
      <button className="text-button" type="button" onClick={() => available.refetch()}>{i18n.t.common.retry}</button></div> : null}
    {available.data && canExecute ? <div className="operation-actions">
      {available.data.operations.map(operation => <button key={operation} className="secondary-button" type="button"
        disabled={mutation.isPending} onClick={() => setSelected(operation)}>{label(operation)}</button>)}
      {available.data.operations.length === 0 ? <span className="muted-copy">{t.noneAvailable}</span> : null}
    </div> : null}
    {mutation.isError ? <div className="inline-error" role="alert">{describeError(mutation.error, i18n)}</div> : null}

    <h3 className="section-subtitle">{t.recent}</h3>
    {history.isPending ? <div className="row-skeleton" aria-label={t.loadingHistory}><span /><span /></div> : null}
    {history.isError ? <div className="inline-error" role="alert">{t.historyError}
      <button className="text-button" type="button" onClick={() => history.refetch()}>{i18n.t.common.retry}</button></div> : null}
    {history.data?.length === 0 ? <p className="muted-copy">{t.noHistory}</p> : null}
    {history.data && history.data.length > 0 ? <div className="data-table operation-history" role="table" aria-label={t.historyLabel}>
      {history.data.map(item => <div className="data-row operation-history-row" role="row" key={item.id}>
        <span>{label(item.operationCode)}</span>
        <StatusIndicator label={t.statuses[item.status] ?? item.status} tone={statusTones[item.status] ?? 'neutral'} />
        <span>{i18n.format.dateTime(item.startedAt)}</span><span>{item.finishedAt ? i18n.format.dateTime(item.finishedAt) : '—'}</span>
        <span>{item.errorCode !== null || item.errorMessage !== null
          ? describeFailure(item.errorCode, item.errorMessage, i18n, t.statuses[item.status] ?? item.status) : '—'}</span>
      </div>)}
    </div> : null}

    {selected ? <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget && !mutation.isPending) setSelected(null)
    }}><section className="monitor-rule-dialog" role="dialog" aria-modal="true" aria-labelledby="operation-confirm-title">
      <div className="dialog-heading"><h2 id="operation-confirm-title">{t.confirmTitle(label(selected))}</h2></div>
      <div className="dialog-body"><p>{t.confirmQuestion(label(selected), resourceName)}</p>
        <p className="muted-copy">{t.confirmDetail}</p></div>
      <div className="dialog-actions"><button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setSelected(null)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" type="button" disabled={mutation.isPending} onClick={execute}>{mutation.isPending ? t.running : i18n.t.common.confirm}</button></div>
    </section></div> : null}
  </WorkspaceSection>
}
