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


/** Each status has its own meaning and tone; they are not one red "error". */
export const desiredStatusTones: Record<DesiredStateStatus, StatusTone> = {
  COMPLIANT: 'success', DRIFTED: 'warning', APPLYING: 'info', WAITING_REFRESH: 'info',
  REMEDIATION_FAILED: 'danger', UNAVAILABLE: 'neutral',
}

export function useDesiredStateCopy() {
  const { t } = useI18n()
  return t.integrationDesiredState
}

function useErrorText() {
  const i18n = useI18n(); const t = i18n.t.integrationDesiredState
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
    {value.status === 'COMPLIANT' || value.status === 'DRIFTED'
      ? <StatusIndicator label={observedDisabled ? t.actual.disabled : t.actual.enabled}
        tone={desiredStatusTones[value.status]} /> : null}
    {value.status !== 'COMPLIANT' ? <StatusIndicator label={t.status[value.status]}
      tone={desiredStatusTones[value.status]} /> : null}</span>
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
