import { useId, useState } from 'react'

import {
  useCancelDeployment,
  useCreateDeployment,
  useDeployment,
  useDeploymentPreview,
} from '../../api/configurationDeployments'
import { useResourceContext } from '../../api/infrastructure'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationAssignmentDetail } from '../../types/configurationAssignment'
import {
  ActiveDeploymentStates,
  type Activation,
  type DeploymentDetail,
  type DeploymentExecution,
} from '../../types/configurationDeployment'
import { InlineAlert, PropertyGrid, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import {
  DeploymentDiffView,
  deploymentTone,
  failureText,
  validExecution,
  validUnit,
  validValidator,
} from './deploymentPresentation'

/** Structured execution only: a systemd action on one unit and an optional argv validator. */
export function ExecutionFields({ value, onChange, idPrefix }: {
  value: { activation: Activation; unitName: string; executable: string; args: string }
  onChange: (next: { activation: Activation; unitName: string; executable: string; args: string }) => void
  idPrefix: string
}) {
  const t = useI18n().t.deployments
  const ids = { activation: `${idPrefix}-activation`, unit: `${idPrefix}-unit`, executable: `${idPrefix}-validator`,
    args: `${idPrefix}-args` }
  const unitInvalid = value.activation !== 'NONE' && value.unitName !== '' && !validUnit(value.unitName.trim())
  const validatorInvalid = !validValidator(value.executable.trim(), splitArgs(value.args))
  return <fieldset className="deployment-execution">
    <legend>{t.execution}</legend>
    <div className="configuration-field">
      <label htmlFor={ids.activation}>{t.activation}</label>
      <select id={ids.activation} value={value.activation}
        onChange={event => onChange({ ...value, activation: event.target.value as Activation })}>
        <option value="NONE">{t.none}</option>
        <option value="SYSTEMD_RELOAD">{t.reload}</option>
        <option value="SYSTEMD_RESTART">{t.restart}</option>
      </select>
    </div>
    {value.activation !== 'NONE' ? <div className="configuration-field">
      <label htmlFor={ids.unit}>{t.unit}</label>
      <input id={ids.unit} value={value.unitName} placeholder="xray.service" spellCheck={false} autoComplete="off"
        aria-invalid={unitInvalid} aria-describedby={`${ids.unit}-help`}
        onChange={event => onChange({ ...value, unitName: event.target.value })} />
      <small id={`${ids.unit}-help`} className={unitInvalid ? 'field-error' : undefined}>{unitInvalid ? t.unitInvalid : t.unitHelp}</small>
    </div> : null}
    <div className="configuration-field">
      <label htmlFor={ids.executable}>{t.validator}</label>
      <input id={ids.executable} value={value.executable} placeholder="/usr/sbin/nginx" spellCheck={false} autoComplete="off"
        aria-describedby={`${ids.executable}-help`} onChange={event => onChange({ ...value, executable: event.target.value })} />
      <small id={`${ids.executable}-help`}>{t.validatorHelp}</small>
    </div>
    {value.executable.trim() ? <div className="configuration-field">
      <label htmlFor={ids.args}>{t.validatorArgs}</label>
      <textarea id={ids.args} value={value.args} rows={4} spellCheck={false} placeholder={'-t\n-c\n{candidate}'}
        aria-invalid={validatorInvalid} aria-describedby={`${ids.args}-help`}
        onChange={event => onChange({ ...value, args: event.target.value })} />
      <small id={`${ids.args}-help`} className={validatorInvalid ? 'field-error' : undefined}>
        {validatorInvalid ? t.validatorInvalid : t.validatorArgsHelp}</small>
    </div> : null}
  </fieldset>
}

export const EmptyExecution = { activation: 'NONE' as Activation, unitName: '', executable: '', args: '' }

function splitArgs(text: string): string[] {
  return text.split('\n').map(value => value.trim()).filter(value => value !== '')
}

export function executionOf(value: typeof EmptyExecution): DeploymentExecution {
  const executable = value.executable.trim()
  return {
    activation: value.activation,
    unitName: value.activation === 'NONE' ? null : value.unitName.trim(),
    validator: executable ? { executable, args: splitArgs(value.args) } : null,
    newFileMode: 420,
  }
}

/** Where a deployment is, in plain words; a failed rollback is shown as the emergency it is. */
export function DeploymentStatusView({ deployment, onCancel, cancelling }: {
  deployment: DeploymentDetail
  onCancel?: () => void
  cancelling?: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  const active = ActiveDeploymentStates.includes(deployment.state)
  const step = deployment.state === 'QUEUED' ? t.progress.QUEUED : t.progress[deployment.phase]
  const failure = failureText(deployment.failureCode, i18n)
  return <div className="deployment-status" role="status" aria-live="polite">
    <div className="deployment-status-line">
      <StatusIndicator label={t.states[deployment.state]} tone={deploymentTone(deployment.state)} />
      {active ? <span>{step}</span> : null}
      <small className="cell-secondary">{t.requestedBy(deployment.actor.name)}</small>
    </div>
    {deployment.state === 'SUCCEEDED' ? <p>{t.succeededDetail}</p> : null}
    {deployment.state === 'FAILED' ? <InlineAlert tone="warning" title={failure ?? t.states.FAILED}>{t.failedDetail}</InlineAlert> : null}
    {deployment.state === 'ROLLED_BACK' ? <InlineAlert tone="warning" title={failure ?? t.states.ROLLED_BACK}>{t.rolledBackDetail}</InlineAlert> : null}
    {deployment.state === 'ROLLBACK_FAILED' ? <InlineAlert tone="danger" title={t.states.ROLLBACK_FAILED}>
      {t.rollbackFailedDetail}{failure ? <> {failure}</> : null}</InlineAlert> : null}
    {deployment.state === 'CANCELLED' ? <p>{t.states.CANCELLED}</p> : null}
    {active && deployment.cancelRequested ? <p>{t.cancelRequested}</p> : null}
    {active && onCancel && !deployment.cancelRequested ? <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" disabled={cancelling} onClick={onCancel}>
        {cancelling ? t.cancelling : t.cancel}</button>
      <small>{t.cancelHelp}</small>
    </div> : null}
  </div>
}

/** Preview, then deploy exactly what was previewed: a remote read is tied to one assignment version
  * and one source connection, and the request carries the remote hash that was shown. */
export function ConfigurationDeploymentPanel({ organizationId, assignment }: {
  organizationId: string; assignment: ConfigurationAssignmentDetail
}) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  const connectionId = useId()
  const context = useResourceContext(organizationId, assignment.resource.id)
  const connections = (context.data?.sourceConnections ?? []).filter(source => source.active && source.connectorType === 'SSH')
  const [chosenConnection, setChosenConnection] = useState('')
  const selected = chosenConnection || (connections.length === 1 ? connections[0].id : '')
  const [execution, setExecution] = useState(EmptyExecution)
  const [deploymentId, setDeploymentId] = useState<string | null>(null)
  const [retryOf, setRetryOf] = useState<string | null>(null)
  const preview = useDeploymentPreview(organizationId, assignment.id)
  const create = useCreateDeployment(organizationId, assignment.id)
  const cancel = useCancelDeployment(organizationId)
  const deployment = useDeployment(organizationId, deploymentId)
  const approved = preview.data && preview.data.assignmentVersion === assignment.version &&
    preview.data.connection.id === selected ? preview.data : null
  const request = executionOf(execution)
  const canDeploy = approved !== null && approved.atomicReplaceSupported && validExecution(request) && !create.isPending
  const finished = deployment.data && !ActiveDeploymentStates.includes(deployment.data.state)

  const start = () => {
    if (!approved || !canDeploy) return
    create.mutate({
      expectedAssignmentVersion: assignment.version,
      connectionId: approved.connection.id,
      expectedRemoteSha256: approved.remote.sha256,
      expectedRemoteMissing: !approved.remote.exists,
      requestId: crypto.randomUUID(),
      execution: request,
      retryOfDeploymentId: retryOf,
    }, { onSuccess: value => { setDeploymentId(value.deploymentId); preview.reset() } })
  }

  const retry = () => {
    // A retry is a new attempt from a fresh look at the server, never the old precondition.
    setRetryOf(deploymentId)
    setDeploymentId(null)
    create.reset()
    preview.reset()
  }

  return <WorkspaceSection title={t.title} description={<>{t.description} {t.driftNote}</>}>
    {deploymentId === null ? <>
      <div className="configuration-field">
        <label htmlFor={connectionId}>{t.connection}</label>
        <select id={connectionId} value={selected} onChange={event => { setChosenConnection(event.target.value); preview.reset() }}>
          {connections.length !== 1 ? <option value="">{t.chooseConnection}</option> : null}
          {connections.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}
        </select>
      </div>
      {context.isSuccess && connections.length === 0 ? <InlineAlert tone="warning" title={t.noConnection} /> : null}
      {retryOf ? <InlineAlert tone="info" title={t.retryHelp} /> : null}
      <div className="configuration-editor-actions">
        <button type="button" className="secondary-button" disabled={!selected || preview.isPending}
          onClick={() => preview.mutate({ expectedAssignmentVersion: assignment.version, connectionId: selected })}>
          {preview.isPending ? t.previewing : t.preview}
        </button>
      </div>
      {preview.isError ? <InlineAlert tone="danger" title={describeError(preview.error, i18n)} /> : null}
      {preview.data && !approved ? <InlineAlert tone="warning" title={t.stale} /> : null}
      {approved ? <div className="configuration-preview">
        <PropertyGrid columns={2} items={[
          { label: `${t.remote} · ${t.hash}`, technical: true,
            value: approved.remote.exists ? approved.remote.sha256 : t.remoteMissing },
          { label: `${t.desired} · ${t.hash}`, technical: true, value: approved.desired.sha256 },
          { label: t.connection, value: approved.connection.name },
        ]} />
        {!approved.atomicReplaceSupported ? <InlineAlert tone="danger" title={t.atomicUnsupported} /> : null}
        {!approved.changed ? <InlineAlert tone="success" title={t.noChanges} /> : null}
        {approved.changed && !approved.remote.text ? <InlineAlert tone="info" title={t.remoteBinary} /> : null}
        {approved.changed ? <DeploymentDiffView diff={approved.diff} /> : null}
      </div> : null}
      <ExecutionFields idPrefix={`deploy-${assignment.id}`} value={execution} onChange={setExecution} />
      <div className="configuration-editor-actions">
        <button type="button" className="primary-button" disabled={!canDeploy} onClick={start}>
          {create.isPending ? t.deploying : t.deploy}</button>
      </div>
      {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
    </> : null}
    {deployment.data ? <>
      <h3 className="deployment-heading">{t.status}</h3>
      <DeploymentStatusView deployment={deployment.data} cancelling={cancel.isPending}
        onCancel={() => deploymentId && cancel.mutate(deploymentId)} />
      {cancel.isError ? <InlineAlert tone="danger" title={describeError(cancel.error, i18n)} /> : null}
      {finished ? <div className="configuration-editor-actions">
        {deployment.data.state === 'SUCCEEDED'
          ? <button type="button" className="secondary-button" onClick={() => { setRetryOf(null); setDeploymentId(null); create.reset() }}>
            {t.preview}</button>
          : <button type="button" className="secondary-button" onClick={retry}>{t.retry}</button>}</div> : null}
    </> : null}
    {deployment.isError ? <InlineAlert tone="danger" title={describeError(deployment.error, i18n)} /> : null}
  </WorkspaceSection>
}
