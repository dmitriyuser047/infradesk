import { useState } from 'react'
import { ApiError } from '../../api/httpClient'
import { useAvailableResourceOperations, useExecuteResourceOperation, useResourceOperationExecutions } from '../../api/resourceOperations'
import { WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useOrganizationPermissions } from '../auth/authorization'
import type { ResourceOperationCode } from '../../types/resourceOperation'

const labels: Record<ResourceOperationCode, string> = {
  CONTAINER_START: 'Start', CONTAINER_STOP: 'Stop', CONTAINER_RESTART: 'Restart',
}

export function ResourceOperationsPanel({ organizationId, resourceId, resourceName }: { organizationId: string; resourceId: string; resourceName: string }) {
  const available = useAvailableResourceOperations(organizationId, resourceId)
  const history = useResourceOperationExecutions(organizationId, resourceId)
  const mutation = useExecuteResourceOperation(organizationId, resourceId)
  const canExecute = useOrganizationPermissions(organizationId).can('executeOperations')
  const [selected, setSelected] = useState<ResourceOperationCode | null>(null)

  const execute = () => {
    if (!selected) return
    mutation.mutate(selected, { onSuccess: () => setSelected(null) })
  }

  if (available.data?.operations.length === 0 && history.data?.length === 0) return null

  return <WorkspaceSection title="Operations">
    {available.isPending ? <div className="row-skeleton" aria-label="Loading operations"><span /><span /></div> : null}
    {available.isError ? <div className="inline-error" role="alert">Unable to load available operations.
      <button className="text-button" type="button" onClick={() => available.refetch()}>Retry</button></div> : null}
    {available.data && canExecute ? <div className="operation-actions">
      {available.data.operations.map(operation => <button key={operation} className="secondary-button" type="button"
        disabled={mutation.isPending} onClick={() => setSelected(operation)}>{labels[operation]}</button>)}
      {available.data.operations.length === 0 ? <span className="muted-copy">No controlled operations are available.</span> : null}
    </div> : null}
    {mutation.isError ? <div className="inline-error" role="alert">
      {mutation.error instanceof ApiError ? mutation.error.message : 'Operation could not be started.'}</div> : null}

    <h3 className="section-subtitle">Recent executions</h3>
    {history.isPending ? <div className="row-skeleton" aria-label="Loading operation history"><span /><span /></div> : null}
    {history.isError ? <div className="inline-error" role="alert">Unable to load operation history.
      <button className="text-button" type="button" onClick={() => history.refetch()}>Retry</button></div> : null}
    {history.data?.length === 0 ? <p className="muted-copy">No operations have been executed yet.</p> : null}
    {history.data && history.data.length > 0 ? <div className="data-table operation-history" role="table" aria-label="Operation history">
      {history.data.map(item => <div className="data-row operation-history-row" role="row" key={item.id}>
        <span>{labels[item.operationCode]}</span><span className={`operation-status ${statusClass[item.status]}`}>{item.status}</span>
        <span>{new Date(item.startedAt).toLocaleString()}</span><span>{item.finishedAt ? new Date(item.finishedAt).toLocaleString() : '—'}</span>
        <span>{item.errorMessage ?? '—'}</span>
      </div>)}
    </div> : null}

    {selected ? <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget && !mutation.isPending) setSelected(null)
    }}><section className="monitor-rule-dialog" role="dialog" aria-modal="true" aria-labelledby="operation-confirm-title">
      <div className="dialog-header"><h2 id="operation-confirm-title">Confirm {labels[selected].toLowerCase()}</h2></div>
      <div className="dialog-body"><p>{labels[selected]} container &quot;{resourceName}&quot;?</p>
        <p className="muted-copy">This sends one controlled command to the discovered container.</p></div>
      <div className="dialog-actions"><button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setSelected(null)}>Cancel</button>
        <button className="primary-button" type="button" disabled={mutation.isPending} onClick={execute}>{mutation.isPending ? 'Running…' : 'Confirm'}</button></div>
    </section></div> : null}
  </WorkspaceSection>
}

const statusClass = {
  RUNNING: 'operation-status-running', SUCCEEDED: 'operation-status-succeeded',
  FAILED: 'operation-status-failed', UNKNOWN: 'operation-status-unknown',
} as const
