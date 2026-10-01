import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '../../api/httpClient'
import { useRemoveDesiredState, useSetDesiredState, useSetManagementMode } from '../../api/integrationDesiredState'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import { useOrganizationPermissions } from '../auth/authorization'
import { InlineAlert, StatusIndicator, WorkspaceSection, type StatusTone } from '../layout/WorkspacePrimitives'
import type {
  DesiredNodeState, DesiredStateCounts, DesiredStateStatus, DesiredStateView, IntegrationManagementMode,
  IntegrationResponse,
} from '../../types/integration'

const copy = {
  en: {
    management: 'Management', observe: 'Observe', managed: 'Automatic management enabled',
    observeDetail: 'InfraDesk observes Remnawave and allows manual actions. It does not automatically change node state.',
    managedDetail: 'InfraDesk automatically keeps selected nodes in the specified state. Other nodes are only monitored.',
    startManaging: 'Manage selected nodes', stopManaging: 'Stop managing nodes',
    requiresSync: 'Managing nodes requires automatic synchronization. Enable the integration first.',
    confirmStartTitle: 'Let InfraDesk manage selected nodes?',
    confirmStart: 'This turns automation on. For every node you then choose to manage, InfraDesk will enable or disable it in Remnawave whenever a different state is observed. Nodes you do not select stay observation-only.',
    confirmStopTitle: 'Stop managing selected nodes?',
    confirmStop: (count: number) => `Desired states for ${count} nodes will be removed. Their current state in Remnawave will not be changed.`,
    confirm: 'Confirm', cancel: 'Cancel', modeError: 'Unable to change the management mode',
    counters: { managed: 'Under management', compliant: 'In the specified state', drifted: 'Need changes', applying: 'Changing now', attention: 'Problems' },
    desired: 'Management', notManaged: 'Not managed', manage: 'Manage', change: 'Change rule',
    state: { ENABLED: 'Enabled', DISABLED: 'Disabled' } as Record<DesiredNodeState, string>,
    ruleState: { ENABLED: 'Should be enabled', DISABLED: 'Should be disabled' } as Record<DesiredNodeState, string>,
    actual: { enabled: 'Currently enabled', disabled: 'Currently disabled' },
    status: {
      COMPLIANT: 'In the specified state', DRIFTED: 'Drift detected', APPLYING: 'Applying', WAITING_REFRESH: 'Waiting for observation',
      REMEDIATION_FAILED: 'Remediation failed', UNAVAILABLE: 'Node unavailable',
    } as Record<DesiredStateStatus, string>,
    dialogTitle: (node: string) => `Manage ${node}`, desiredLabel: 'Desired state',
    dialogDetail: 'InfraDesk will automatically restore this state when a different state is observed. This is persistent automation, not a one-time command.',
    manageNode: 'Manage node', save: 'Change desired state', stop: 'Stop managing',
    stopDetail: 'Stopping removes the desired state. The node stays as it is in Remnawave.',
    saveError: 'Unable to change the desired state',
    observed: 'Observed', compliance: 'Compliance',
    codes: {
      INTEGRATION_ACTION_ALREADY_RUNNING: 'An action on this node is still queued or running. Wait for it to finish.',
      INTEGRATION_MANAGEMENT_REQUIRES_SYNC: 'Managing nodes requires automatic synchronization.',
      INTEGRATION_MANAGEMENT_ACTIVE: 'Switch back to Observe before changing the endpoint or the credentials.',
      INTEGRATION_MANAGEMENT_MODE_REQUIRED: 'The integration does not manage selected nodes.',
      INTEGRATION_DESIRED_STATE_DISABLED: 'Desired state is disabled for this deployment.',
      INTEGRATION_DESIRED_STATE_UNSUPPORTED: 'Only Remnawave nodes can have a desired state.',
      INTEGRATION_OBJECT_INACTIVE: 'This node is no longer reported by Remnawave.',
      INTEGRATION_OBJECT_NOT_FOUND: 'This node is no longer in the inventory.',
    } as Record<string, string>,
  },
  ru: {
    management: 'Управление', observe: 'Наблюдение', managed: 'Автоматическое управление включено',
    observeDetail: 'InfraDesk наблюдает Remnawave и позволяет выполнять ручные действия. Состояние нод автоматически не меняется.',
    managedDetail: 'InfraDesk автоматически поддерживает выбранные ноды в заданном состоянии. Остальные ноды только отслеживаются.',
    startManaging: 'Управлять выбранными нодами', stopManaging: 'Прекратить управление',
    requiresSync: 'Для управления нужна автоматическая синхронизация. Сначала включите интеграцию.',
    confirmStartTitle: 'Разрешить InfraDesk управлять выбранными нодами?',
    confirmStart: 'Это включает автоматизацию. Каждую ноду, которую вы затем выберете, InfraDesk будет включать или отключать в Remnawave, когда наблюдаемое состояние отличается от желаемого. Невыбранные ноды остаются только под наблюдением.',
    confirmStopTitle: 'Прекратить управление выбранными нодами?',
    confirmStop: (count: number) => `Желаемые состояния для ${count} нод будут удалены. Их текущее состояние в Remnawave не изменится.`,
    confirm: 'Подтвердить', cancel: 'Отмена', modeError: 'Не удалось изменить режим управления',
    counters: { managed: 'Под управлением', compliant: 'В нужном состоянии', drifted: 'Требуют изменения', applying: 'Изменяются сейчас', attention: 'Проблемы' },
    desired: 'Управление', notManaged: 'Не управляется', manage: 'Управлять', change: 'Изменить правило',
    state: { ENABLED: 'Включена', DISABLED: 'Отключена' } as Record<DesiredNodeState, string>,
    ruleState: { ENABLED: 'Должна быть включена', DISABLED: 'Должна быть выключена' } as Record<DesiredNodeState, string>,
    actual: { enabled: 'Сейчас включена', disabled: 'Сейчас выключена' },
    status: {
      COMPLIANT: 'В нужном состоянии', DRIFTED: 'Обнаружено расхождение', APPLYING: 'Применяется',
      WAITING_REFRESH: 'Ожидание синхронизации', REMEDIATION_FAILED: 'Не удалось применить', UNAVAILABLE: 'Нода отсутствует',
    } as Record<DesiredStateStatus, string>,
    dialogTitle: (node: string) => `Управление: ${node}`, desiredLabel: 'Желаемое состояние',
    dialogDetail: 'InfraDesk будет автоматически восстанавливать это состояние, когда наблюдается другое. Это постоянная автоматизация, а не разовая команда.',
    manageNode: 'Управлять нодой', save: 'Изменить желаемое состояние', stop: 'Прекратить управление',
    stopDetail: 'Желаемое состояние будет удалено. Нода в Remnawave останется как есть.',
    saveError: 'Не удалось изменить желаемое состояние',
    observed: 'Наблюдается', compliance: 'Соответствие',
    codes: {
      INTEGRATION_ACTION_ALREADY_RUNNING: 'Действие над этой нодой ещё в очереди или выполняется. Дождитесь завершения.',
      INTEGRATION_MANAGEMENT_REQUIRES_SYNC: 'Для управления нодами нужна автоматическая синхронизация.',
      INTEGRATION_MANAGEMENT_ACTIVE: 'Вернитесь в режим наблюдения, прежде чем менять адрес или учётные данные.',
      INTEGRATION_MANAGEMENT_MODE_REQUIRED: 'Интеграция не управляет выбранными нодами.',
      INTEGRATION_DESIRED_STATE_DISABLED: 'Желаемое состояние отключено в этой установке.',
      INTEGRATION_DESIRED_STATE_UNSUPPORTED: 'Желаемое состояние есть только у нод Remnawave.',
      INTEGRATION_OBJECT_INACTIVE: 'Remnawave больше не сообщает об этой ноде.',
      INTEGRATION_OBJECT_NOT_FOUND: 'Этой ноды больше нет в инвентаре.',
    } as Record<string, string>,
  },
}

/** Each status has its own meaning and tone; they are not one red "error". */
export const desiredStatusTones: Record<DesiredStateStatus, StatusTone> = {
  COMPLIANT: 'success', DRIFTED: 'warning', APPLYING: 'info', WAITING_REFRESH: 'info',
  REMEDIATION_FAILED: 'danger', UNAVAILABLE: 'neutral',
}

export function useDesiredStateCopy() {
  const { locale } = useI18n()
  return copy[locale]
}

function useErrorText() {
  const i18n = useI18n(); const t = copy[i18n.locale]
  return (error: unknown) => error instanceof ApiError && t.codes[error.code] ? t.codes[error.code] : describeError(error, i18n)
}

/** Whether this person may change intent: it leads to remote writes, so it needs both permissions. */
export function useCanManageDesiredState(organizationId: string): boolean {
  const permissions = useOrganizationPermissions(organizationId)
  return permissions.can('manageIntegrations') && permissions.can('executeOperations')
}

function Dialog({ labelledBy, busy, onClose, children }: {
  labelledBy: string; busy: boolean; onClose: () => void; children: ReactNode
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onClose() }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [busy, onClose])
  return <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
    if (event.target === event.currentTarget && !busy) onClose()
  }}><section className="monitor-rule-dialog integration-bind-dialog" role="dialog" aria-modal="true" aria-labelledby={labelledBy}>
    {children}</section></div>
}

/**
 * The integration's management mode, with the counters of its managed nodes. Turning automation
 * on or off is a deliberate, confirmed step — never a small checkbox.
 */
export function ManagementSection({ organizationId, integration, counts }: {
  organizationId: string; integration: IntegrationResponse; counts: DesiredStateCounts | undefined
}) {
  const t = useDesiredStateCopy()
  const canChange = useCanManageDesiredState(organizationId)
  const mutation = useSetManagementMode(organizationId, integration.id)
  const errorText = useErrorText()
  const [confirming, setConfirming] = useState<IntegrationManagementMode | null>(null)
  const managed = integration.managementMode === 'MANAGED_SELECTED'
  return <WorkspaceSection title={t.management}
    actions={canChange ? <button className="secondary-button" type="button" disabled={!managed && !integration.enabled}
      onClick={() => { mutation.reset(); setConfirming(managed ? 'OBSERVE' : 'MANAGED_SELECTED') }}>
      {managed ? t.stopManaging : t.startManaging}</button> : undefined}>
    <p><StatusIndicator label={managed ? t.managed : t.observe} tone={managed ? 'info' : 'neutral'} /></p>
    <p className="muted-copy">{managed ? t.managedDetail : t.observeDetail}</p>
    {!managed && !integration.enabled && canChange ? <p className="muted-copy">{t.requiresSync}</p> : null}
    {managed && counts ? <dl className="integration-counters">
      <div><dt>{t.counters.managed}</dt><dd>{counts.managed}</dd></div>
      <div><dt>{t.counters.compliant}</dt><dd>{counts.compliant}</dd></div>
      <div><dt>{t.counters.drifted}</dt><dd>{counts.drifted}</dd></div>
      <div><dt>{t.counters.applying}</dt><dd>{counts.applying}</dd></div>
      <div><dt>{t.counters.attention}</dt><dd>{counts.needsAttention}</dd></div>
    </dl> : null}
    {confirming ? <Dialog labelledBy="management-mode-title" busy={mutation.isPending} onClose={() => setConfirming(null)}>
      <div className="dialog-heading"><h2 id="management-mode-title">
        {confirming === 'MANAGED_SELECTED' ? t.confirmStartTitle : t.confirmStopTitle}</h2></div>
      <div className="dialog-body"><p>{confirming === 'MANAGED_SELECTED' ? t.confirmStart : t.confirmStop(counts?.managed ?? 0)}</p>
        {mutation.isError ? <InlineAlert tone="danger" title={t.modeError}>{errorText(mutation.error)}</InlineAlert> : null}</div>
      <div className="dialog-actions">
        <button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setConfirming(null)}>{t.cancel}</button>
        <button className="primary-button" type="button" disabled={mutation.isPending}
          onClick={() => mutation.mutate(confirming, { onSuccess: () => setConfirming(null) })}>{t.confirm}</button>
      </div>
    </Dialog> : null}
  </WorkspaceSection>
}

/** The desired state of one node as text and a status, never color alone. */
export function DesiredStateBadge({ value, observedDisabled }: { value: DesiredStateView; observedDisabled: boolean }) {
  const t = useDesiredStateCopy()
  return <span className="integration-inline"><strong>{t.ruleState[value.state]}</strong>
    <StatusIndicator label={value.status === 'COMPLIANT' ? observedDisabled ? t.actual.disabled : t.actual.enabled : t.status[value.status]}
      tone={desiredStatusTones[value.status]} /></span>
}

/**
 * One node's desired state and the way to change it. In OBSERVE nothing can be managed; in
 * MANAGED_SELECTED a node is managed only after a person chooses it here.
 */
export function DesiredStateControl({ organizationId, integrationId, managementMode, node }: {
  organizationId: string; integrationId: string; managementMode: IntegrationManagementMode
  node: { id: string; displayName: string; active: boolean; summary: { isDisabled: boolean }; desiredState?: DesiredStateView | null }
}) {
  const t = useDesiredStateCopy()
  const canChange = useCanManageDesiredState(organizationId)
  const [open, setOpen] = useState(false)
  const desired = node.desiredState ?? null
  const managing = managementMode === 'MANAGED_SELECTED'
  // An unmanaged node can only be chosen while Remnawave still reports it; a managed one can always be released.
  const offer = managing && canChange && (desired !== null || node.active)
  return <div className="integration-desired">
    {desired ? <DesiredStateBadge value={desired} observedDisabled={node.summary.isDisabled} /> : <span className="muted-copy">{t.notManaged}</span>}
    {offer ? <button className="secondary-button" type="button" aria-label={desired ? t.change : undefined}
      onClick={() => setOpen(true)}>{desired ? t.change : t.manage}</button> : null}
    {open ? <ManageNodeDialog organizationId={organizationId} integrationId={integrationId} node={node}
      onClose={() => setOpen(false)} /> : null}
  </div>
}

function ManageNodeDialog({ organizationId, integrationId, node, onClose }: {
  organizationId: string; integrationId: string
  node: { id: string; displayName: string; active: boolean; desiredState?: DesiredStateView | null }
  onClose: () => void
}) {
  const t = useDesiredStateCopy()
  const desired = node.desiredState ?? null
  const [state, setState] = useState<DesiredNodeState>(desired?.state ?? 'ENABLED')
  const save = useSetDesiredState(organizationId, integrationId)
  const remove = useRemoveDesiredState(organizationId, integrationId)
  const errorText = useErrorText()
  const busy = save.isPending || remove.isPending
  const failure = save.error ?? remove.error
  return <Dialog labelledBy={`manage-${node.id}`} busy={busy} onClose={onClose}>
    <div className="dialog-heading"><h2 id={`manage-${node.id}`}>{t.dialogTitle(node.displayName)}</h2></div>
    <div className="dialog-body">
      <fieldset className="integration-candidates"><legend>{t.desiredLabel}</legend>
        {(['ENABLED', 'DISABLED'] as const).map(option => <label key={option} className="integration-candidate">
          <input type="radio" name={`desired-${node.id}`} value={option} checked={state === option}
            disabled={!node.active} onChange={() => setState(option)} />
          <span>{t.ruleState[option]}</span></label>)}
      </fieldset>
      <p className="muted-copy">{t.dialogDetail}</p>
      {desired ? <p className="muted-copy">{t.stopDetail}</p> : null}
      {failure ? <InlineAlert tone="danger" title={t.saveError}>{errorText(failure)}</InlineAlert> : null}
    </div>
    <div className="dialog-actions">
      <button className="secondary-button" type="button" disabled={busy} onClick={onClose}>{t.cancel}</button>
      {desired ? <button className="secondary-button" type="button" disabled={busy}
        onClick={() => remove.mutate(node.id, { onSuccess: onClose })}>{t.stop}</button> : null}
      <button className="primary-button" type="button" disabled={busy || !node.active || desired?.state === state}
        onClick={() => save.mutate({ objectId: node.id, state }, { onSuccess: onClose })}>
        {desired ? t.save : t.manageNode}</button>
    </div>
  </Dialog>
}
