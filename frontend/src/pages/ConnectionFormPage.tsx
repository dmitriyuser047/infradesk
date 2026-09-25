import { type FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useConnection, useCreateConnection, useProbeSshHostKey, useTestSshConnection, useUpdateConnection } from '../api/connections'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { useEnvironments, useProjects } from '../api/navigation'
import { buildSshConnectionRequest, MIN_SSH_SYNC_INTERVAL_SECONDS, SyncIntervalError } from '../components/connections/buildSshConnectionRequest'
import { canOrganization } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextSearch } from '../components/layout/workspaceNavigation'
import type { ConnectionResponse, SshAuthenticationType } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionFormPage() {
  const { t } = useI18n()
  const { organizationId, connectionId } = useParams()
  const membership = useMyOrganizations()
  const existing = useConnection(organizationId, connectionId)
  if (!organizationId) return <InvalidRoutePage />
  if (membership.isPending || (connectionId && existing.isPending)) return <AppShell><p className="compact-state">{t.connections.form.loading}</p></AppShell>
  if (!canOrganization(membership.data?.find(value => value.id === organizationId)?.role, 'manageConnections')) {
    return <AppShell><p className="compact-state">{t.connections.form.ownersOnly}</p></AppShell>
  }
  if (connectionId && (existing.isError || !existing.data)) return <AppShell><p className="compact-state">{t.connections.form.notFound}</p></AppShell>
  if (existing.data && (existing.data.connectorType !== 'SSH' || !existing.data.active)) {
    return <Navigate to={`/organizations/${organizationId}/connections/${connectionId}`} replace />
  }
  return <ConnectionForm key={connectionId ?? 'new'} organizationId={organizationId} existing={existing.data} />
}

function ConnectionForm({ organizationId, existing }: { organizationId: string; existing?: ConnectionResponse }) {
  const i18n = useI18n()
  const t = i18n.t.connections.form
  const navigate = useNavigate()
  const location = useLocation()
  const context = contextSearch(new URLSearchParams(location.search))
  const projects = useProjects(organizationId)
  const [name, setName] = useState(existing?.name ?? '')
  const [code, setCode] = useState(existing?.code ?? '')
  const [projectId, setProjectId] = useState(existing?.scope.type === 'ORGANIZATION' ? '' : existing?.scope.projectId ?? '')
  const [environmentId, setEnvironmentId] = useState(existing?.scope.type === 'ENVIRONMENT' ? existing.scope.environmentId : '')
  const [scopeTouched, setScopeTouched] = useState(false)
  const [environmentTouched, setEnvironmentTouched] = useState(false)
  const [host, setHost] = useState(existing?.ssh?.host ?? '')
  const [port, setPort] = useState(String(existing?.ssh?.port ?? 22))
  const [username, setUsername] = useState(existing?.ssh?.username ?? '')
  const [authenticationType, setAuthenticationType] =
    useState<SshAuthenticationType>(existing?.ssh?.authenticationType ?? 'PASSWORD')
  const [password, setPassword] = useState('')
  const [privateKey, setPrivateKey] = useState('')
  const [passphrase, setPassphrase] = useState('')
  // The identity of the host, as confirmed by whoever is editing this connection.
  const [hostKeyFingerprint, setHostKeyFingerprint] = useState(existing?.ssh?.hostKeyFingerprint ?? '')
  const [probedFingerprint, setProbedFingerprint] = useState<string | null>(null)
  const [intervalSeconds, setIntervalSeconds] = useState(String(existing?.schedule?.intervalSeconds ?? 600))
  const [validationError, setValidationError] = useState<string | null>(null)
  const [scheduleEnabled, setScheduleEnabled] = useState(existing?.schedule?.enabled ?? true)
  const [testedFingerprint, setTestedFingerprint] = useState<string | null>(null)
  const environments = useEnvironments(organizationId, projectId || null)
  useEffect(() => {
    if (!existing && !scopeTouched && !projectId && projects.data?.length) setProjectId(projects.data[0].id)
  }, [existing, scopeTouched, projectId, projects.data])
  useEffect(() => {
    if (!existing && !environmentTouched && projectId && !environmentId && environments.data?.length) {
      setEnvironmentId(environments.data[0].id)
    }
  }, [existing, environmentTouched, projectId, environmentId, environments.data])
  const create = useCreateConnection(organizationId)
  const update = useUpdateConnection(organizationId, existing?.id ?? '')
  const test = useTestSshConnection(organizationId)
  const probe = useProbeSshHostKey(organizationId)
  const methodChanged = existing?.ssh !== undefined &&
    existing.ssh !== null && existing.ssh.authenticationType !== authenticationType
  const credentialEntered = authenticationType === 'PRIVATE_KEY' ? privateKey.trim() !== '' : password !== ''
  const credentialRequired = existing === undefined || methodChanged
  const storedFingerprint = existing?.ssh?.hostKeyFingerprint ?? null
  const hostKeyChanged = probedFingerprint !== null && storedFingerprint !== null &&
    probedFingerprint !== storedFingerprint
  const hostConfirmed = hostKeyFingerprint.trim() !== ''
  const save = existing ? update : create
  const back = existing ? `/organizations/${organizationId}/connections/${existing.id}${context}` : `/organizations/${organizationId}/connections${context}`

  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (!hostConfirmed) {
        throw new Error(t.validation.confirmHost)
      }
      if (credentialRequired && !credentialEntered) {
        throw new Error(authenticationType === 'PRIVATE_KEY'
          ? t.validation.keyRequired
          : t.validation.passwordRequired)
      }
      const body = buildSshConnectionRequest({ code, name, projectId, environmentId, host, port,
        username, authenticationType, password, privateKey, passphrase, hostKeyFingerprint,
        scheduleEnabled, intervalSeconds })
      setValidationError(null)
      save.submit(body, connection => {
        // The credential leaves the form as soon as it has been submitted.
        setPassword('')
        setPrivateKey('')
        setPassphrase('')
        navigate(`/organizations/${organizationId}/connections/${connection.id}${context}`)
      })
    } catch (error) {
      setValidationError(error instanceof SyncIntervalError ? t.validation.interval(MIN_SSH_SYNC_INTERVAL_SECONDS)
        : error instanceof Error ? error.message : i18n.t.errors.generic)
    }
  }

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={existing ? t.titleEdit : t.titleNew}
      subtitle={t.subtitle} back={{ label: t.back, to: back }} />
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title={t.general}><div className="field-grid">
        <label>{i18n.t.common.name} <input required maxLength={255} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>{i18n.t.common.code} <input required maxLength={64} value={code} onChange={e => setCode(e.target.value)} /></label>
        <label>{i18n.t.context.project} <select value={projectId} onChange={e => { setScopeTouched(true); setEnvironmentTouched(false); setProjectId(e.target.value); setEnvironmentId('') }}>
          <option value="">{t.organizationWide}</option>
          {projects.data?.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
        </select></label>
        {projectId ? <label>{i18n.t.context.environment} <select value={environmentId} onChange={e => { setEnvironmentTouched(true); setEnvironmentId(e.target.value) }}>
          <option value="">{t.projectWide}</option>
          {environments.data?.map(environment => <option key={environment.id} value={environment.id}>{environment.name}</option>)}
        </select></label> : null}
      </div></WorkspaceSection>
      <WorkspaceSection title={t.server}><div className="field-grid host-port-grid">
        <label>{t.host} <input required value={host} onChange={e => setHost(e.target.value)} /></label>
        <label>{t.port} <input required type="number" min="1" max="65535" value={port} onChange={e => setPort(e.target.value)} /></label>
      </div><div className="field-grid ssh-credentials-grid">
        <label>{t.username} <input required value={username} onChange={e => setUsername(e.target.value)} /></label>
        <label>{t.method}
          <select value={authenticationType}
            onChange={e => setAuthenticationType(e.target.value as SshAuthenticationType)}>
            <option value="PASSWORD">{t.password}</option>
            <option value="PRIVATE_KEY">{t.privateKey}</option>
          </select>
        </label>
        {authenticationType === 'PASSWORD' ? (
          <label>{t.password} {existing && !methodChanged ? <small>{t.keepStored}</small> : null}
            <input type="password" required={credentialRequired} autoComplete="new-password"
              value={password} onChange={e => setPassword(e.target.value)} />
          </label>
        ) : (
          <>
            <label>{t.privateKey} {existing && !methodChanged ? <small>{t.keepStored}</small> : null}
              <textarea rows={6} required={credentialRequired} spellCheck={false}
                autoComplete="off" value={privateKey}
                onChange={e => setPrivateKey(e.target.value)} />
            </label>
            <label>{t.passphrase}
              <input type="password" autoComplete="new-password" value={passphrase}
                onChange={e => setPassphrase(e.target.value)} />
            </label>
          </>
        )}
      </div></WorkspaceSection>
      <WorkspaceSection title={t.trust}><div className="field-grid">
        <label>{t.fingerprint}
          <input readOnly value={hostKeyFingerprint} placeholder={t.notVerified}
            aria-label={t.fingerprintLabel} />
        </label>
        <p className={hostKeyChanged ? 'inline-error' : 'muted-copy'} role={hostKeyChanged ? 'alert' : undefined}>
          {hostKeyChanged
            ? `${t.statusMismatch}. ${t.statusMismatchDetail}`
            : hostConfirmed
              ? `${t.statusTrusted}. ${t.statusTrustedDetail}`
              : `${t.statusUntrusted}. ${t.statusUntrustedDetail}`}
        </p>
        <div className="form-toolbar">
          <button className="secondary-button" type="button"
            disabled={!host || probe.isPending || save.isPending}
            onClick={() => {
              setProbedFingerprint(null)
              probe.submit(
                { host: host.trim(), port: Number(port), username: username.trim() },
                result => setProbedFingerprint(result.hostKeyFingerprint),
              )
            }}>{probe.isPending ? t.reading : t.readHostKey}</button>
          {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? (
            <button className="secondary-button" type="button"
              onClick={() => setHostKeyFingerprint(probedFingerprint)}>
              {hostKeyChanged ? t.replaceWith : t.trustThis}
            </button>
          ) : null}
        </div>
        {probe.isError ? <p className="inline-error" role="alert">{describeError(probe.error, i18n)}</p> : null}
      </div></WorkspaceSection>
      <WorkspaceSection title={t.synchronization}><div className="field-grid">
        <label>{t.interval} <input required type="number" min={MIN_SSH_SYNC_INTERVAL_SECONDS} value={intervalSeconds} onChange={e => setIntervalSeconds(e.target.value)} /></label>
        <label className="checkbox-field"><input type="checkbox" checked={scheduleEnabled} onChange={e => setScheduleEnabled(e.target.checked)} /> {t.scheduleEnabled}</label>
      </div></WorkspaceSection>
      {validationError ? <p className="inline-error" role="alert">{validationError}</p> : null}
      {testedFingerprint ? <p className="inline-feedback" role="status">{t.verified(testedFingerprint)}</p> : null}
      {test.isError ? <p className="inline-error" role="alert">{describeError(test.error, i18n)}</p> : null}
      {save.isError ? <p className="inline-error" role="alert">{describeError(save.error, i18n)}</p> : null}
      <div className="form-toolbar">
        <button className="secondary-button" type="button"
          disabled={!credentialEntered || !hostConfirmed || test.isPending || save.isPending}
          onClick={() => {
            setTestedFingerprint(null)
            test.submit({
              host: host.trim(),
              port: Number(port),
              username: username.trim(),
              hostKeyFingerprint: hostKeyFingerprint.trim(),
              credentials: authenticationType === 'PRIVATE_KEY'
                ? { type: 'PRIVATE_KEY', privateKey, ...(passphrase === '' ? {} : { passphrase }) }
                : { type: 'PASSWORD', password },
            }, result => setTestedFingerprint(result.hostKeyFingerprint))
          }}>{test.isPending ? t.testing : t.test}</button>
        <Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={save.isPending}>{save.isPending ? i18n.t.common.saving : t.submit}</button>
      </div>
    </form>
  </div></AppShell>
}
