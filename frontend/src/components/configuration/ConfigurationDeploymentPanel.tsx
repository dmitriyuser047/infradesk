import { useId, useState } from 'react'

import { useCreateDeployment, useDeployment, useDeploymentPreview } from '../../api/configurationDeployments'
import { useResourceContext } from '../../api/infrastructure'
import { InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationAssignmentDetail } from '../../types/configurationAssignment'
import type { Activation } from '../../types/configurationDeployment'

/** A remote preview is tied to one assignment version and one source connection. */
export function ConfigurationDeploymentPanel({ organizationId, assignment }: {
  organizationId: string; assignment: ConfigurationAssignmentDetail
}) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  const ids = { connection: useId(), activation: useId(), unit: useId(), validator: useId(), args: useId() }
  const context = useResourceContext(organizationId, assignment.resource.id)
  const connections = (context.data?.sourceConnections ?? []).filter(source => source.active && source.connectorType === 'SSH')
  const [chosenConnection, setChosenConnection] = useState('')
  const selected = chosenConnection || (connections.length === 1 ? connections[0].id : '')
  const [activation, setActivation] = useState<Activation>('NONE')
  const [unitName, setUnitName] = useState('')
  const [validatorExecutable, setValidatorExecutable] = useState('')
  const [validatorArgs, setValidatorArgs] = useState('')
  const [deploymentId, setDeploymentId] = useState<string | null>(null)
  const preview = useDeploymentPreview(organizationId, assignment.id)
  const create = useCreateDeployment(organizationId, assignment.id)
  const deployment = useDeployment(organizationId, deploymentId)
  const approved = preview.data && preview.data.assignmentVersion === assignment.version && preview.data.connection.id === selected
    ? preview.data : null

  const start = () => {
    if (!approved) return
    create.mutate({
      expectedAssignmentVersion: assignment.version,
      connectionId: approved.connection.id,
      expectedRemoteSha256: approved.remote.sha256,
      expectedRemoteMissing: !approved.remote.exists,
      requestId: crypto.randomUUID(),
      execution: {
        activation,
        unitName: activation === 'NONE' ? null : unitName.trim(),
        validator: validatorExecutable.trim() ? {
          executable: validatorExecutable.trim(), args: validatorArgs.split('\n').map(value => value.trim()).filter(Boolean),
        } : null,
        newFileMode: 420,
      },
    }, { onSuccess: value => setDeploymentId(value.deploymentId) })
  }

  return <WorkspaceSection title={t.title}>
    <div className="configuration-field">
      <label htmlFor={ids.connection}>{t.connection}</label>
      <select id={ids.connection} value={selected} onChange={event => { setChosenConnection(event.target.value); preview.reset() }}>
        {connections.length !== 1 ? <option value="">{t.chooseConnection}</option> : null}
        {connections.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}
      </select>
    </div>
    {context.isSuccess && connections.length === 0 ? <InlineAlert tone="warning" title={t.noConnection} /> : null}
    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" disabled={!selected || preview.isPending}
        onClick={() => preview.mutate({ expectedAssignmentVersion: assignment.version, connectionId: selected })}>
        {preview.isPending ? t.previewing : t.preview}
      </button>
    </div>
    {preview.isError ? <InlineAlert tone="danger" title={describeError(preview.error, i18n)} /> : null}
    {preview.data && !approved ? <InlineAlert tone="warning" title={t.stale} /> : null}
    {approved ? <div className="configuration-preview">
      <p>{approved.remote.exists ? `${t.remoteHash}: ${approved.remote.sha256}` : t.remoteMissing}</p>
      <p>{t.desiredHash}: <span className="technical-value">{approved.desired.sha256}</span></p>
      {approved.changed ? <pre className="configuration-code" tabIndex={0}>{approved.diff.text}</pre>
        : <p>{t.noChanges}</p>}
      {approved.diff.truncated ? <InlineAlert tone="info" title={t.truncated} /> : null}
    </div> : null}
    <div className="configuration-field">
      <label htmlFor={ids.activation}>{t.activation}</label>
      <select id={ids.activation} value={activation} onChange={event => setActivation(event.target.value as Activation)}>
        <option value="NONE">{t.none}</option><option value="SYSTEMD_RELOAD">{t.reload}</option>
        <option value="SYSTEMD_RESTART">{t.restart}</option>
      </select>
    </div>
    {activation !== 'NONE' ? <div className="configuration-field"><label htmlFor={ids.unit}>{t.unit}</label>
      <input id={ids.unit} value={unitName} onChange={event => setUnitName(event.target.value)} placeholder="nginx.service" /></div> : null}
    <div className="configuration-field"><label htmlFor={ids.validator}>{t.validator}</label>
      <input id={ids.validator} value={validatorExecutable} onChange={event => setValidatorExecutable(event.target.value)}
        placeholder="/usr/sbin/nginx" /></div>
    {validatorExecutable.trim() ? <div className="configuration-field"><label htmlFor={ids.args}>{t.validatorArgs}</label>
      <textarea id={ids.args} value={validatorArgs} onChange={event => setValidatorArgs(event.target.value)} />
      <small>{t.validatorHelp}</small></div> : null}
    <div className="configuration-editor-actions"><button type="button" className="primary-button"
      disabled={!approved || create.isPending || (activation !== 'NONE' && !/^[A-Za-z0-9_.@:-]+\.service$/.test(unitName))}
      onClick={start}>{create.isPending ? t.deploying : t.deploy}</button></div>
    {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
    {deployment.data ? <div role="status" aria-live="polite">
      <h3>{t.status}</h3><p>{t.states[deployment.data.state]}</p>
      <p>{t.phase}: {deployment.data.phase}</p>
      {deployment.data.failureCode ? <p>{t.failure}: {deployment.data.failureCode}</p> : null}
    </div> : null}
    {deployment.isError ? <InlineAlert tone="danger" title={describeError(deployment.error, i18n)} /> : null}
  </WorkspaceSection>
}
