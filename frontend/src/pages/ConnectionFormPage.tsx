import { type FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useConnection, useCreateConnection, useProbeSshHostKey, useTestSshConnection, useUpdateConnection } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { useEnvironments, useProjects } from '../api/navigation'
import { buildSshConnectionRequest, MIN_SSH_SYNC_INTERVAL_SECONDS } from '../components/connections/buildSshConnectionRequest'
import { canOrganization } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextSearch } from '../components/layout/workspaceNavigation'
import type { ConnectionResponse, SshAuthenticationType } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionFormPage() {
  const { organizationId, connectionId } = useParams()
  const membership = useMyOrganizations()
  const existing = useConnection(organizationId, connectionId)
  if (!organizationId) return <InvalidRoutePage />
  if (membership.isPending || (connectionId && existing.isPending)) return <AppShell><p className="compact-state">Loading connection…</p></AppShell>
  if (!canOrganization(membership.data?.find(value => value.id === organizationId)?.role, 'manageConnections')) {
    return <AppShell><p className="compact-state">Only organization owners can manage connections.</p></AppShell>
  }
  if (connectionId && (existing.isError || !existing.data)) return <AppShell><p className="compact-state">Connection not found.</p></AppShell>
  if (existing.data && (existing.data.connectorType !== 'SSH' || !existing.data.active)) {
    return <Navigate to={`/organizations/${organizationId}/connections/${connectionId}`} replace />
  }
  return <ConnectionForm key={connectionId ?? 'new'} organizationId={organizationId} existing={existing.data} />
}

function ConnectionForm({ organizationId, existing }: { organizationId: string; existing?: ConnectionResponse }) {
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
        throw new Error('Confirm the host fingerprint before saving')
      }
      if (credentialRequired && !credentialEntered) {
        throw new Error(authenticationType === 'PRIVATE_KEY'
          ? 'A private key is required for this authentication method'
          : 'A password is required for this authentication method')
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
    } catch (error) { setValidationError(error instanceof Error ? error.message : 'Invalid sync interval') }
  }

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={existing ? 'Edit connection' : 'Add SSH connection'}
      subtitle="A working SSH login is verified before saving" back={{ label: 'Connections', to: back }} />
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title="Connection"><div className="field-grid">
        <label>Name <input required maxLength={255} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>Code <input required maxLength={64} value={code} onChange={e => setCode(e.target.value)} /></label>
        <label>Project <select value={projectId} onChange={e => { setScopeTouched(true); setEnvironmentTouched(false); setProjectId(e.target.value); setEnvironmentId('') }}>
          <option value="">Organization-wide</option>
          {projects.data?.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
        </select></label>
        {projectId ? <label>Environment <select value={environmentId} onChange={e => { setEnvironmentTouched(true); setEnvironmentId(e.target.value) }}>
          <option value="">Project-wide</option>
          {environments.data?.map(environment => <option key={environment.id} value={environment.id}>{environment.name}</option>)}
        </select></label> : null}
      </div></WorkspaceSection>
      <WorkspaceSection title="SSH"><div className="field-grid host-port-grid">
        <label>Host <input required value={host} onChange={e => setHost(e.target.value)} /></label>
        <label>Port <input required type="number" min="1" max="65535" value={port} onChange={e => setPort(e.target.value)} /></label>
      </div><div className="field-grid ssh-credentials-grid">
        <label>Username <input required value={username} onChange={e => setUsername(e.target.value)} /></label>
        <label>Authentication
          <select value={authenticationType}
            onChange={e => setAuthenticationType(e.target.value as SshAuthenticationType)}>
            <option value="PASSWORD">Password</option>
            <option value="PRIVATE_KEY">Private key</option>
          </select>
        </label>
        {authenticationType === 'PASSWORD' ? (
          <label>Password {existing && !methodChanged ? '(leave blank to keep current)' : ''}
            <input type="password" required={credentialRequired} autoComplete="new-password"
              value={password} onChange={e => setPassword(e.target.value)} />
          </label>
        ) : (
          <>
            <label>Private key {existing && !methodChanged ? '(leave blank to keep current)' : ''}
              <textarea rows={6} required={credentialRequired} spellCheck={false}
                autoComplete="off" value={privateKey}
                onChange={e => setPrivateKey(e.target.value)} />
            </label>
            <label>Passphrase (optional)
              <input type="password" autoComplete="new-password" value={passphrase}
                onChange={e => setPassphrase(e.target.value)} />
            </label>
          </>
        )}
      </div></WorkspaceSection>
      <WorkspaceSection title="Host identity"><div className="field-grid">
        <label>Trusted fingerprint
          <input readOnly value={hostKeyFingerprint} placeholder="Not verified"
            aria-label="Trusted host fingerprint" />
        </label>
        <p className={hostKeyChanged ? 'inline-error' : 'muted-copy'} role={hostKeyChanged ? 'alert' : undefined}>
          {hostKeyChanged
            ? `Host identity changed. The server now presents ${probedFingerprint}, not the ` +
              `fingerprint this connection trusts. Confirm the replacement only if you changed ` +
              `the host key yourself.`
            : hostConfirmed
              ? 'Trusted. A credential is only ever sent to this identity.'
              : 'Not verified. Read the host key and confirm it before saving.'}
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
            }}>{probe.isPending ? 'Reading…' : 'Read host key'}</button>
          {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? (
            <button className="secondary-button" type="button"
              onClick={() => setHostKeyFingerprint(probedFingerprint)}>
              {hostKeyChanged ? `Replace with ${probedFingerprint}` : `Trust ${probedFingerprint}`}
            </button>
          ) : null}
        </div>
        {probe.isError ? <p className="inline-error" role="alert">{errorText(probe.error)}</p> : null}
      </div></WorkspaceSection>
      <WorkspaceSection title="Synchronization"><div className="field-grid">
        <label>Interval (seconds) <input required type="number" min={MIN_SSH_SYNC_INTERVAL_SECONDS} value={intervalSeconds} onChange={e => setIntervalSeconds(e.target.value)} /></label>
        <label className="checkbox-field"><input type="checkbox" checked={scheduleEnabled} onChange={e => setScheduleEnabled(e.target.checked)} /> Enable scheduled sync</label>
      </div></WorkspaceSection>
      {validationError ? <p className="inline-error" role="alert">{validationError}</p> : null}
      {testedFingerprint ? <p className="inline-feedback" role="status">Connection verified · {testedFingerprint}</p> : null}
      {test.isError ? <p className="inline-error" role="alert">{errorText(test.error)}</p> : null}
      {save.isError ? <p className="inline-error" role="alert">{errorText(save.error)}</p> : null}
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
          }}>{test.isPending ? 'Testing…' : 'Test connection'}</button>
        <Link className="secondary-button" to={back}>Cancel</Link>
        <button className="primary-button" type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save connection'}</button>
      </div>
    </form>
  </div></AppShell>
}

function errorText(error: Error): string {
  return error instanceof ApiError ? error.message : 'Unable to complete request. Please try again.'
}
