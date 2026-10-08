import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/httpClient'
import { createRequestId, useIntegrationAction, useIntegrationActions, useRequestIntegrationAction } from '../../api/integrationActions'
import { useSyncIntegration } from '../../api/integrationInventory'
import { useOrganizationPermissions } from '../auth/authorization'
import { InlineAlert, StatusIndicator } from '../layout/WorkspacePrimitives'
import { PageActionMenu, type PageAction } from '../layout/PageActionMenu'
import { IntegrationDialog } from './IntegrationDialog'
import { useSyncErrorText } from './integrationPresentation'
import { useI18n } from '../../i18n'
import type { IntegrationActionCode, IntegrationActionExecution, InventoryObject, RemnawaveNodeSummary } from '../../types/integration'

type PendingSubmission = { action: IntegrationActionCode; requestId: string }

function savedSubmission(key: string): PendingSubmission | null {
  try {
    const raw = window.sessionStorage.getItem(key)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (typeof value === 'object' && value !== null && 'requestId' in value && 'action' in value &&
      typeof value.requestId === 'string' &&
      (value.action === 'NODE_ENABLE' || value.action === 'NODE_DISABLE' || value.action === 'NODE_RESTART' || value.action === 'NODE_DELETE'))
      return value as PendingSubmission
  } catch { /* Session storage may be unavailable. Keep the in-memory request ID. */ }
  return null
}

function storeSubmission(key: string, value: PendingSubmission | null): void {
  try {
    if (value) window.sessionStorage.setItem(key, JSON.stringify(value))
    else window.sessionStorage.removeItem(key)
  } catch { /* The current mounted component still retains the ID. */ }
}

export function NodeActionControls({ organizationId, integrationId, node, compact = false, extraActions = [] }: {
  organizationId: string; integrationId: string; node: InventoryObject<RemnawaveNodeSummary>; compact?: boolean; extraActions?: PageAction[]
}) {
  const i18n = useI18n()
  const t = i18n.t.integrationNodeActions
  const errorText = useSyncErrorText()
  const permissions = useOrganizationPermissions(organizationId)
  const canExecute = permissions.can('manageIntegrations') && permissions.can('executeOperations')
  const history = useIntegrationActions(organizationId, integrationId, canExecute)
  const mutation = useRequestIntegrationAction(organizationId, integrationId)
  const sync = useSyncIntegration(organizationId, integrationId)
  const storageKey = `integration-action-request:${organizationId}:${integrationId}:${node.id}`
  const [unresolved, setUnresolved] = useState<PendingSubmission | null>(() => savedSubmission(storageKey))
  const [pendingAction, setPendingAction] = useState<IntegrationActionCode | null>(() => savedSubmission(storageKey)?.action ?? null)
  const [executionId, setExecutionId] = useState<string | null>(null)
  const submitting = useRef(false)
  const requestId = useRef<string | null>(unresolved?.requestId ?? null)
  const execution = useIntegrationAction(organizationId, integrationId, executionId)
  useEffect(() => {
    if (!unresolved) return
    const found = history.data?.find(value => value.requestId === unresolved.requestId)
    if (!found) return
    storeSubmission(storageKey, null)
    requestId.current = null
    setUnresolved(null)
    setPendingAction(null)
    setExecutionId(found.id)
    mutation.reset()
  }, [history.data, unresolved, storageKey])
  if (!canExecute || !node.active) return compact && extraActions.length ? <PageActionMenu actions={extraActions} /> : null
  const current = execution.data ?? history.data?.find(value => value.inventoryObjectId === node.id &&
    (value.status === 'QUEUED' || value.status === 'RUNNING'))
  const active = current?.status === 'QUEUED' || current?.status === 'RUNNING'
  const unknown = (current?.status === 'UNKNOWN' ? current : null) ??
    history.data?.find(value => value.inventoryObjectId === node.id && value.status === 'UNKNOWN')
  const requiresRefresh = unknown?.finishedAt && Date.parse(node.lastSeenAt) <= Date.parse(unknown.finishedAt)
  // A one-shot action that works against the node's desired state is not offered; the backend refuses it anyway.
  const desired = node.desiredState?.state
  const observedChoices: IntegrationActionCode[] = node.summary.isDisabled ? ['NODE_ENABLE', 'NODE_DELETE'] : ['NODE_DISABLE', 'NODE_RESTART', 'NODE_DELETE']
  const choices = observedChoices.filter(action =>
    !(desired === 'ENABLED' && action === 'NODE_DISABLE') && !(desired === 'DISABLED' && action === 'NODE_ENABLE'))
  const label = (action: IntegrationActionCode) => action === 'NODE_DELETE' ? t.delete : action === 'NODE_ENABLE' ? t.enable : action === 'NODE_DISABLE' ? t.disable : t.restart
  const submit = () => {
    if (!pendingAction || submitting.current) return
    submitting.current = true
    requestId.current ??= createRequestId()
    const submitted = { action: pendingAction, requestId: requestId.current }
    storeSubmission(storageKey, submitted)
    mutation.mutate({ objectId: node.id, action: pendingAction, requestId: requestId.current }, {
      onSuccess: value => {
        storeSubmission(storageKey, null)
        setUnresolved(null); setExecutionId(value.id); setPendingAction(null); requestId.current = null
      },
      onError: error => {
        if (!(error instanceof ApiError) || error.status >= 500) setUnresolved(submitted)
        else { storeSubmission(storageKey, null); setUnresolved(null); setPendingAction(null); requestId.current = null }
      },
      onSettled: () => { submitting.current = false },
    })
  }
  return <div className="integration-node-actions">
    {compact ? <PageActionMenu actions={[
      ...(!active && !requiresRefresh && !unresolved ? choices.map(action => ({ label: label(action),
        danger: action === 'NODE_DISABLE' || action === 'NODE_DELETE', onSelect: () => { mutation.reset(); requestId.current = null; setPendingAction(action) } })) : []),
      ...extraActions,
    ]} /> : !active && !requiresRefresh && !unresolved ? choices.map(action => <button key={action} className="secondary-button" type="button"
      onClick={() => { mutation.reset(); requestId.current = null; setPendingAction(action) }}>{label(action)}</button>) : null}
    {active ? <StatusIndicator label={current?.status === 'QUEUED' ? t.queued : t.running} tone="neutral" /> : null}
    {requiresRefresh ? <InlineAlert tone="warning" title={t.unknown}
      action={<button className="secondary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>{t.sync}</button>} /> : null}
    {current?.status === 'SUCCEEDED' ? <span className="integration-inline"><span className="muted-copy">{t.succeeded}</span>
      <button className="secondary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>{t.sync}</button>
    </span> : null}
    {current?.status === 'FAILED' ? <InlineAlert tone="danger" title={t.failed}>{errorText(current.errorCode)}</InlineAlert> : null}
    {sync.isError || sync.data?.status === 'FAILED' ?
      <InlineAlert tone="danger" title={i18n.t.integrationInventory.syncFailed}>
        {errorText(sync.data?.errorCode ?? (sync.error instanceof ApiError ? sync.error.code : null))}</InlineAlert> : null}
    {mutation.isError && !unresolved && mutation.error instanceof ApiError &&
      mutation.error.code === 'INTEGRATION_ACTION_REQUIRES_REFRESH' ?
      <InlineAlert tone="warning" title={t.unknown}
        action={<button className="secondary-button" type="button" disabled={sync.isPending}
          onClick={() => sync.mutate()}>{t.sync}</button>} /> : mutation.isError && !unresolved ?
      <InlineAlert tone="danger" title={t.failed}>{mutation.error instanceof ApiError ? errorText(mutation.error.code) : t.failed}</InlineAlert>
      : null}
    {pendingAction ? <IntegrationDialog title={t.title(label(pendingAction), node.displayName)}
      busy={mutation.isPending || Boolean(unresolved)} onClose={() => { requestId.current = null; setPendingAction(null) }}
      actions={<><button className="secondary-button" type="button" disabled={mutation.isPending || Boolean(unresolved)}
        onClick={() => { requestId.current = null; setPendingAction(null) }}>{t.cancel}</button>
        <button className="primary-button" type="button" disabled={mutation.isPending} onClick={submit}>
          {unresolved ? t.retryRequest : t.confirm}</button></>}>
      <div><p className="property-technical">{node.summary.address}:{node.summary.port} · {node.externalId}</p>
        {pendingAction === 'NODE_DELETE' ? <p>{t.warningDelete}</p> : pendingAction === 'NODE_DISABLE' ? t.warningDisable
        : pendingAction === 'NODE_RESTART' ? t.warningRestart : null}
        {unresolved ? <p>{t.uncertainRequest}</p> : null}</div></IntegrationDialog> : null}
  </div>
}
