import { type FormEvent, useEffect, useState } from 'react'
import { KeyRound, LockKeyhole, ScanSearch, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useConnection, useCreateConnection, useProbeSshHostKey, useTestSshConnection, useUpdateConnection } from '../api/connections'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { useEnvironments, useProjects } from '../api/navigation'
import { buildSshConnectionRequest, MIN_SSH_SYNC_INTERVAL_SECONDS, SyncIntervalError } from '../components/connections/buildSshConnectionRequest'
import { canOrganization } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextSearch } from '../components/layout/workspaceNavigation'
import { confirm, endpointId, hostTrust, initialConfirmations, type EndpointKey } from '../components/connections/hostTrust'
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
    useState<SshAuthenticationType>(existing?.ssh?.authenticationType ?? 'PRIVATE_KEY')
  const [password, setPassword] = useState('')
  const [privateKey, setPrivateKey] = useState('')
  const [passphrase, setPassphrase] = useState('')
  // The endpoint the server trusts now, and the confirmations made in this draft, per endpoint.
  const storedEndpoint: EndpointKey | null = existing?.ssh?.hostKeyFingerprint
    ? { host: existing.ssh.host, port: existing.ssh.port, fingerprint: existing.ssh.hostKeyFingerprint } : null
  const [confirmations, setConfirmations] = useState(() => initialConfirmations(storedEndpoint))
  const [probed, setProbed] = useState<EndpointKey | null>(null)
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
  const currentEndpoint = { host: host.trim(), port: Number(port) }
  const hostKey = hostTrust({ current: currentEndpoint, stored: storedEndpoint, confirmations, probed })
  // Only a key confirmed for this very host and port counts; it is also the one sent on save.
  const hostKeyFingerprint = hostKey.confirmed ?? ''
  const hostConfirmed = hostKey.confirmed !== null
  const hostKeyChanged = hostKey.state === 'mismatch'
  const probedFingerprint = hostKey.presented
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

  const trust = hostKey.state
  const storedCredential = existing?.ssh?.credentialConfigured === true && !methodChanged

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={existing ? t.titleEdit : t.titleNew}
      subtitle={t.subtitle} back={{ label: t.back, to: back }} />
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title={t.general} description={t.generalHint}><div className="section-body"><div className="field-grid">
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
        <p className="field-hint field-span">{t.placementHint}</p>
      </div></div></WorkspaceSection>

      <WorkspaceSection title={t.server} description={t.serverHint}><div className="section-body">
        <div className="field-grid host-port-grid">
          <label>{t.host} <input required value={host} autoComplete="off" spellCheck={false} onChange={e => setHost(e.target.value)} /></label>
          <label>{t.port} <input required type="number" min="1" max="65535" value={port} onChange={e => setPort(e.target.value)} /></label>
        </div>
        <div className="field-grid field-grid-spaced">
          <label>{t.username} <input required value={username} autoComplete="off" spellCheck={false} onChange={e => setUsername(e.target.value)} /></label>
        </div>
      </div></WorkspaceSection>

      <WorkspaceSection title={t.authentication} description={t.authenticationHint}><div className="section-body">
        <fieldset className="segmented" aria-label={t.method}>
          {(['PRIVATE_KEY', 'PASSWORD'] as const).map(method => <label key={method}
            className={`segmented-option ${authenticationType === method ? 'selected' : ''}`}>
            <input type="radio" name="authentication" value={method} checked={authenticationType === method}
              onChange={() => setAuthenticationType(method)} />
            {method === 'PRIVATE_KEY' ? <KeyRound aria-hidden size={16} /> : <LockKeyhole aria-hidden size={16} />}
            {i18n.t.connections.authTypes[method]}
          </label>)}
        </fieldset>
        {storedCredential ? <div className="credential-state">
          <StatusIndicator label={t.credentialsStored} tone="success" icon={ShieldCheck} />
          <span className="field-hint">{t.keepStored}</span>
        </div> : null}
        {authenticationType === 'PASSWORD' ? (
          <div className="field-grid field-grid-spaced">
            <label>{t.password}
              <input type="password" required={credentialRequired} autoComplete="new-password"
                value={password} onChange={e => setPassword(e.target.value)} />
            </label>
          </div>
        ) : (
          <div className="field-grid field-grid-spaced">
            <label className="field-span">{t.privateKey}
              <textarea rows={6} required={credentialRequired} spellCheck={false} className="monospace-input"
                autoComplete="off" value={privateKey}
                onChange={e => setPrivateKey(e.target.value)} />
              <small>{t.privateKeyHint}</small>
            </label>
            <label>{t.passphrase}
              <input type="password" autoComplete="new-password" value={passphrase}
                onChange={e => setPassphrase(e.target.value)} />
            </label>
          </div>
        )}
      </div></WorkspaceSection>

      <WorkspaceSection title={t.trust} description={t.trustHint}><div className="section-body trust-body">
        <div className={`trust-state trust-${trust}`} role={trust === 'mismatch' ? 'alert' : 'status'}>
          {trust === 'mismatch' ? <ShieldAlert aria-hidden size={20} /> : trust === 'trusted'
            ? <ShieldCheck aria-hidden size={20} /> : <ShieldQuestion aria-hidden size={20} />}
          <div>
            <strong>{trust === 'mismatch' ? t.statusMismatch : trust === 'trusted' ? t.statusTrusted
              : trust === 'changed' ? t.statusEndpointChanged : t.statusUntrusted}</strong>
            <p>{trust === 'mismatch' ? t.statusMismatchDetail : trust === 'trusted' ? t.statusTrustedDetail
              : trust === 'changed' && storedEndpoint ? t.statusEndpointChangedDetail(endpointId(storedEndpoint))
                : t.statusUntrustedDetail}</p>
          </div>
        </div>
        <div className="fingerprint-field">
          <span className="field-label" id="fingerprint-label">{t.fingerprint}</span>
          <output className={`fingerprint ${hostKeyFingerprint ? '' : 'empty'}`} aria-label={t.fingerprintLabel}>{hostKeyFingerprint || t.notVerified}</output>
        </div>
        {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? <div className="fingerprint-field">
          <span className="field-label">{t.presented}</span>
          <output className={`fingerprint ${hostKeyChanged ? 'fingerprint-changed' : ''}`}>{probedFingerprint}</output>
        </div> : null}
        <div className="form-toolbar form-toolbar-start">
          <button className="secondary-button" type="button"
            disabled={!host || probe.isPending || save.isPending}
            onClick={() => {
              const endpoint = currentEndpoint
              setProbed(null)
              probe.submit(
                { ...endpoint, username: username.trim() },
                // The key is bound to the address it was read from, not to whatever the form shows later.
                result => setProbed({ ...endpoint, fingerprint: result.hostKeyFingerprint }),
              )
            }}><ScanSearch aria-hidden size={16} />{probe.isPending ? t.reading : t.readHostKey}</button>
          {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? (
            <button className={hostKeyChanged ? 'danger-button' : 'primary-button'} type="button"
              onClick={() => setConfirmations(previous => confirm(previous, { ...currentEndpoint, fingerprint: probedFingerprint }))}>
              {hostKeyChanged ? t.replaceWith : t.trustThis}
            </button>
          ) : null}
        </div>
        {probe.isError ? <InlineAlert tone="danger" title={describeError(probe.error, i18n)} /> : null}
      </div></WorkspaceSection>

      <WorkspaceSection title={t.synchronization} description={t.synchronizationHint}><div className="section-body"><div className="field-grid">
        <label>{t.interval} <input required type="number" min={MIN_SSH_SYNC_INTERVAL_SECONDS} value={intervalSeconds} onChange={e => setIntervalSeconds(e.target.value)} /></label>
        <label className="checkbox-field checkbox-aligned"><input type="checkbox" checked={scheduleEnabled} onChange={e => setScheduleEnabled(e.target.checked)} /> {t.scheduleEnabled}</label>
      </div></div></WorkspaceSection>

      {validationError ? <InlineAlert tone="danger" title={validationError} /> : null}
      {testedFingerprint ? <InlineAlert tone="success" title={t.verified(testedFingerprint)} /> : null}
      {test.isError ? <InlineAlert tone="danger" title={describeError(test.error, i18n)} /> : null}
      {save.isError ? <InlineAlert tone="danger" title={describeError(save.error, i18n)} /> : null}
      <div className="form-toolbar form-footer">
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
