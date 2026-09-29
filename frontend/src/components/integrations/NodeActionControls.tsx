import { useRef, useState } from 'react'
import { ApiError } from '../../api/httpClient'
import { createRequestId, useIntegrationAction, useIntegrationActions, useRequestIntegrationAction } from '../../api/integrationActions'
import { useSyncIntegration } from '../../api/integrationInventory'
import { useOrganizationPermissions } from '../auth/authorization'
import { InlineAlert, StatusIndicator } from '../layout/WorkspacePrimitives'
import { useI18n } from '../../i18n'
import type { IntegrationActionCode, IntegrationActionExecution, InventoryObject, RemnawaveNodeSummary } from '../../types/integration'

const copy = {
  en: { enable: 'Enable', disable: 'Disable', restart: 'Restart', cancel: 'Cancel', confirm: 'Confirm',
    warningDisable: 'Users of this node may lose their connection.',
    warningRestart: 'Connections may be interrupted during restart.',
    unknown: 'The operation result is unknown. InfraDesk cannot tell whether Remnawave ran it. Synchronize before another action.',
    failed: 'The action failed', sync: 'Sync now', queued: 'Queued', running: 'Running',
    succeeded: 'Succeeded. Sync now to refresh the observed state.',
    title: (verb: string, name: string) => `${verb} node ${name}?` },
  ru: { enable: 'Включить', disable: 'Отключить', restart: 'Перезапустить', cancel: 'Отмена', confirm: 'Подтвердить',
    warningDisable: 'Пользователи этого узла могут потерять соединение.',
    warningRestart: 'Во время перезапуска соединения могут временно прерваться.',
    unknown: 'Результат операции неизвестен. InfraDesk не может определить, выполнил ли Remnawave команду. Синхронизируйте состояние перед новым действием.',
    failed: 'Действие не выполнено', sync: 'Синхронизировать', queued: 'В очереди', running: 'Выполняется',
    succeeded: 'Действие выполнено. Синхронизируйте наблюдаемое состояние.',
    title: (verb: string, name: string) => `${verb} узел ${name}?` },
}

export function NodeActionControls({ organizationId, integrationId, node }: {
  organizationId: string; integrationId: string; node: InventoryObject<RemnawaveNodeSummary>
}) {
  const i18n = useI18n()
  const t = copy[i18n.locale]
  const permissions = useOrganizationPermissions(organizationId)
  const canExecute = permissions.can('manageIntegrations') && permissions.can('executeOperations')
  const history = useIntegrationActions(organizationId, integrationId, canExecute)
  const mutation = useRequestIntegrationAction(organizationId, integrationId)
  const sync = useSyncIntegration(organizationId, integrationId)
  const [pendingAction, setPendingAction] = useState<IntegrationActionCode | null>(null)
  const [executionId, setExecutionId] = useState<string | null>(null)
  const submitting = useRef(false)
  const requestId = useRef<string | null>(null)
  const execution = useIntegrationAction(organizationId, integrationId, executionId)
  if (!canExecute || !node.active) return null
  const current = execution.data ?? history.data?.find(value => value.inventoryObjectId === node.id &&
    (value.status === 'QUEUED' || value.status === 'RUNNING'))
  const active = current?.status === 'QUEUED' || current?.status === 'RUNNING'
  const unknown = (current?.status === 'UNKNOWN' ? current : null) ??
    history.data?.find(value => value.inventoryObjectId === node.id && value.status === 'UNKNOWN')
  const requiresRefresh = unknown?.finishedAt && Date.parse(node.lastSeenAt) <= Date.parse(unknown.finishedAt)
  const choices: IntegrationActionCode[] = node.summary.isDisabled ? ['NODE_ENABLE'] : ['NODE_DISABLE', 'NODE_RESTART']
  const label = (action: IntegrationActionCode) => action === 'NODE_ENABLE' ? t.enable : action === 'NODE_DISABLE' ? t.disable : t.restart
  const submit = () => {
    if (!pendingAction || submitting.current) return
    submitting.current = true
    requestId.current ??= createRequestId()
    mutation.mutate({ objectId: node.id, action: pendingAction, requestId: requestId.current }, {
      onSuccess: value => { setExecutionId(value.id); setPendingAction(null); requestId.current = null },
      onError: error => {
        if (error instanceof ApiError && (error.code === 'INTEGRATION_ACTION_REQUIRES_REFRESH' ||
          error.code === 'INTEGRATION_ACTION_ALREADY_RUNNING')) {
          setPendingAction(null); requestId.current = null
        }
      },
      onSettled: () => { submitting.current = false },
    })
  }
  return <div className="integration-node-actions">
    {!active && !requiresRefresh ? choices.map(action => <button key={action} className="secondary-button" type="button"
      onClick={() => { requestId.current = null; setPendingAction(action) }}>{label(action)}</button>) : null}
    {active ? <StatusIndicator label={current?.status === 'QUEUED' ? t.queued : t.running} tone="neutral" /> : null}
    {requiresRefresh ? <InlineAlert tone="warning" title={t.unknown}
      action={<button className="secondary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>{t.sync}</button>} /> : null}
    {current?.status === 'SUCCEEDED' ? <span className="integration-inline"><span className="muted-copy">{t.succeeded}</span>
      <button className="secondary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>{t.sync}</button>
    </span> : null}
    {current?.status === 'FAILED' ? <InlineAlert tone="danger" title={t.failed}>{current.errorCode}</InlineAlert> : null}
    {sync.isError || sync.data?.status === 'FAILED' ?
      <InlineAlert tone="danger" title={i18n.t.integrationInventory.syncFailed}>
        {sync.data?.errorCode ?? (sync.error instanceof ApiError ? sync.error.code : null)}</InlineAlert> : null}
    {mutation.isError && mutation.error instanceof ApiError &&
      mutation.error.code === 'INTEGRATION_ACTION_REQUIRES_REFRESH' ?
      <InlineAlert tone="warning" title={t.unknown}
        action={<button className="secondary-button" type="button" disabled={sync.isPending}
          onClick={() => sync.mutate()}>{t.sync}</button>} /> : mutation.isError ?
      <InlineAlert tone="danger" title={t.failed}>{mutation.error instanceof ApiError ? mutation.error.code : t.failed}</InlineAlert>
      : null}
    {pendingAction ? <div className="dialog-backdrop" role="presentation"><section className="monitor-rule-dialog"
      role="dialog" aria-modal="true" aria-labelledby={`action-${node.id}`}>
      <div className="dialog-heading"><h2 id={`action-${node.id}`}>{t.title(label(pendingAction), node.displayName)}</h2></div>
      <div className="dialog-body">{pendingAction === 'NODE_DISABLE' ? t.warningDisable
        : pendingAction === 'NODE_RESTART' ? t.warningRestart : null}</div>
      <div className="dialog-actions"><button className="secondary-button" type="button" disabled={mutation.isPending}
        onClick={() => { requestId.current = null; setPendingAction(null) }}>{t.cancel}</button>
        <button className="primary-button" type="button" disabled={mutation.isPending} onClick={submit}>{t.confirm}</button></div>
    </section></div> : null}
  </div>
}
