import { type FormEvent, useEffect, useRef, useState } from 'react'
import { KeyRound, LockKeyhole, ScanSearch, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useConnection, useCreateConnection, useProbeSshHostKey, useTestSshConnection, useUpdateConnection } from '../api/connections'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { useEnvironments, useProjects } from '../api/navigation'
import { buildSshConnectionRequest, MIN_SSH_SYNC_INTERVAL_SECONDS, SyncIntervalError } from '../components/connections/buildSshConnectionRequest'
import { PermissionGate } from '../components/layout/WorkspaceGate'
import { AppShell } from '../components/layout/AppShell'
import {
  CopyButton, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader, WorkspaceSection,
} from '../components/layout/WorkspacePrimitives'
import { checkKey, currentResult, type BoundResult } from '../components/connections/connectionCheck'
import { getLastSyncSummary } from '../components/connections/connectionPresentation'
import { contextSearch } from '../components/layout/workspaceNavigation'
import { confirm, endpointId, hostTrust, initialConfirmations, type EndpointKey } from '../components/connections/hostTrust'
import type { ConnectionResponse, SshAuthenticationType } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionFormPage() {
  const { t } = useI18n()
  const { organizationId, connectionId } = useParams()
  const location = useLocation()
  if (!organizationId) return <InvalidRoutePage />
  const context = contextSearch(new URLSearchParams(location.search))
  const list = `/organizations/${encodeURIComponent(organizationId)}/connections`
  const back = { label: t.connections.form.back, to: connectionId ? `${list}/${encodeURIComponent(connectionId)}${context}` : `${list}${context}` }
  return <PermissionGate organizationId={organizationId} permission="manageConnections"
    title={connectionId ? t.connections.form.titleEdit : t.connections.form.titleNew} back={back} texts={t.connections.form}>
    <ConnectionFormLoader organizationId={organizationId} connectionId={connectionId} back={back} />
  </PermissionGate>
}

/** An edit starts from the saved connection; a new one starts empty. */
function ConnectionFormLoader({ organizationId, connectionId, back }: {
  organizationId: string
  connectionId: string | undefined
  back: { label: string; to: string }
}) {
  const { t } = useI18n()
  const existing = useConnection(organizationId, connectionId)
  if (connectionId && existing.isPending) return <AppShell><PageLoading title={t.connections.form.titleEdit} back={back} label={t.connections.form.loading} /></AppShell>
  if (connectionId && (existing.isError || !existing.data)) {
    return <AppShell><PageUnavailable back={back} onRetry={() => existing.refetch()} error={existing.error}
      notFound={existing.error instanceof ApiError && existing.error.code === 'CONNECTION_NOT_FOUND'}
      notFoundTitle={t.connections.form.notFound} errorTitle={t.connections.page.loadError} /></AppShell>
  }
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
  // Grows with every edit of a secret, so a check can tell it ran with other credentials without keeping them.
  const [credentialRevision, setCredentialRevision] = useState(0)
  // The endpoint the server trusts now, and the confirmations made in this draft, per endpoint.
  const storedEndpoint: EndpointKey | null = existing?.ssh?.hostKeyFingerprint
    ? { host: existing.ssh.host, port: existing.ssh.port, fingerprint: existing.ssh.hostKeyFingerprint } : null
  const [confirmations, setConfirmations] = useState(() => initialConfirmations(storedEndpoint))
  // Key reads and connection checks, each labelled with what it was made for (see connectionCheck).
  const [probed, setProbed] = useState<EndpointKey | null>(null)
  const [probeFailure, setProbeFailure] = useState<BoundResult<unknown> | null>(null)
  const [check, setCheck] = useState<BoundResult<{ ok: true } | { ok: false; error: unknown }> | null>(null)
  const [intervalSeconds, setIntervalSeconds] = useState(String(existing?.schedule?.intervalSeconds ?? 600))
  const [validationError, setValidationError] = useState<string | null>(null)
  const validationRef = useRef<HTMLDivElement>(null)
  const [scheduleEnabled, setScheduleEnabled] = useState(existing?.schedule?.enabled ?? true)
  const environments = useEnvironments(organizationId, projectId || null)
  useEffect(() => {
    if (!existing && !scopeTouched && !projectId && projects.data?.length) setProjectId(projects.data[0].id)
  }, [existing, scopeTouched, projectId, projects.data])
  useEffect(() => {
    if (!existing && !environmentTouched && projectId && !environmentId && environments.data?.length) {
      setEnvironmentId(environments.data[0].id)
    }
  }, [existing, environmentTouched, projectId, environmentId, environments.data])
  // A refused save puts the reason in front of the keyboard and the screen reader.
  useEffect(() => { if (validationError) validationRef.current?.focus() }, [validationError])
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
  const draftKey = checkKey({ ...currentEndpoint, username, authenticationType, fingerprint: hostKeyFingerprint, credentialRevision })
  const currentCheck = currentResult(check, draftKey)
  const currentProbeFailure = currentResult(probeFailure, endpointId(currentEndpoint))
  // What the form looks like when an answer arrives, as opposed to when its request was sent.
  const endpointKey = endpointId(currentEndpoint)
  const latestDraft = useRef({ draftKey, endpoint: endpointKey })
  // Any change to what was checked retires the result; going back to the same values does not revive it.
  useEffect(() => {
    latestDraft.current = { draftKey, endpoint: endpointKey }
    setCheck(previous => previous !== null && previous.key !== draftKey ? null : previous)
  }, [draftKey, endpointKey])
  const save = existing ? update : create
  const back = existing ? `/organizations/${organizationId}/connections/${existing.id}${context}` : `/organizations/${organizationId}/connections${context}`
  const editSecret = (setter: (value: string) => void) => (value: string) => {
    setter(value)
    setCredentialRevision(revision => revision + 1)
  }

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

  function readHostKey() {
    const endpoint = currentEndpoint
    setProbed(null)
    setProbeFailure(null)
    probe.submit(
      { ...endpoint, username: username.trim() },
      // The key is bound to the address it was read from, not to whatever the form shows later.
      result => setProbed({ ...endpoint, fingerprint: result.hostKeyFingerprint }),
      error => { if (latestDraft.current.endpoint === endpointId(endpoint)) setProbeFailure({ key: endpointId(endpoint), value: error }) },
    )
  }

  function testConnection() {
    // The result answers for this draft only; later edits make it stale, and a late answer stays unseen.
    const key = draftKey
    setCheck(null)
    test.submit({
      host: currentEndpoint.host,
      port: currentEndpoint.port,
      username: username.trim(),
      hostKeyFingerprint: hostKeyFingerprint.trim(),
      credentials: authenticationType === 'PRIVATE_KEY'
        ? { type: 'PRIVATE_KEY', privateKey, ...(passphrase === '' ? {} : { passphrase }) }
        : { type: 'PASSWORD', password },
    }, () => { if (latestDraft.current.draftKey === key) setCheck({ key, value: { ok: true } }) },
    error => { if (latestDraft.current.draftKey === key) setCheck({ key, value: { ok: false, error } }) })
  }

  const trust = hostKey.state
  const storedCredential = existing?.ssh?.credentialConfigured === true && !methodChanged
  const storedType = existing?.ssh?.authenticationType
  const identityBadge = trust === 'trusted' ? <StatusIndicator label={t.statusTrusted} tone="success" icon={ShieldCheck} />
    : trust === 'mismatch' ? <StatusIndicator label={t.fingerprintMismatch} tone="danger" icon={ShieldAlert} />
      : probedFingerprint !== null ? <StatusIndicator label={t.requiresConfirmation} tone="warning" icon={ShieldQuestion} />
        : <StatusIndicator label={t.notVerified} tone="neutral" icon={ShieldQuestion} />

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={existing ? t.titleEdit : t.titleNew}
      subtitle={t.subtitle} back={{ label: t.back, to: back }} />
    <form className="workspace-form" onSubmit={submit}>
      {existing?.ssh ? <ExistingConnectionState connection={existing} /> : null}
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
        {/* What is stored is named, never shown: the backend returns no secret, so there is nothing to mask. */}
        {storedCredential ? <div className="credential-state">
          <StatusIndicator label={storedType === 'PASSWORD' ? t.passwordConfigured : t.privateKeyConfigured} tone="success" icon={ShieldCheck} />
          <span className="field-hint">{t.keepStored}</span>
        </div> : null}
        {methodChanged && existing?.ssh?.credentialConfigured ? <InlineAlert tone="info" title={t.newCredentialsRequired} /> : null}
        {authenticationType === 'PASSWORD' ? (
          <div className="field-grid field-grid-spaced">
            <label>{t.password}
              <input type="password" required={credentialRequired} autoComplete="new-password"
                value={password} onChange={e => editSecret(setPassword)(e.target.value)} />
            </label>
          </div>
        ) : (
          <div className="field-grid field-grid-spaced">
            <label className="field-span">{t.privateKey}
              <textarea rows={6} required={credentialRequired} spellCheck={false} className="monospace-input"
                autoComplete="off" value={privateKey}
                onChange={e => editSecret(setPrivateKey)(e.target.value)} />
              <small>{t.privateKeyHint}</small>
            </label>
            <label>{t.passphrase}
              <input type="password" autoComplete="new-password" value={passphrase}
                onChange={e => editSecret(setPassphrase)(e.target.value)} />
            </label>
          </div>
        )}
      </div></WorkspaceSection>

      <WorkspaceSection title={t.trust} description={t.trustHint}><div className="section-body trust-body">
        <div className={`trust-state trust-${trust}`} role={trust === 'mismatch' || trust === 'changed' ? 'alert' : 'status'}>
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
        <dl className="property-grid identity-grid">
          <div className="property-row"><dt>{t.address}</dt>
            <dd className="property-technical">{currentEndpoint.host ? endpointId(currentEndpoint) : '—'}</dd></div>
          <div className="property-row"><dt>{t.identityStatus}</dt><dd>{identityBadge}</dd></div>
          <div className="property-row"><dt id="fingerprint-label">{t.fingerprint}</dt>
            <dd className="fingerprint-value">
              <output className={`fingerprint ${hostKeyFingerprint ? '' : 'empty'}`} aria-label={t.fingerprintLabel}>{hostKeyFingerprint || t.notVerified}</output>
              {hostKeyFingerprint ? <CopyButton value={hostKeyFingerprint} label={`${i18n.t.common.copy}: ${t.fingerprint}`} /> : null}
            </dd></div>
          {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? <div className="property-row"><dt>{t.presented}</dt>
            <dd className="fingerprint-value">
              <StatusIndicator label={t.fingerprintReceived} tone="info" />
              <output className={`fingerprint ${hostKeyChanged ? 'fingerprint-changed' : ''}`} aria-label={t.presented}>{probedFingerprint}</output>
              <CopyButton value={probedFingerprint} label={`${i18n.t.common.copy}: ${t.presented}`} />
            </dd></div> : null}
        </dl>
        <div className="form-toolbar form-toolbar-start">
          <button className="secondary-button" type="button" aria-busy={probe.isPending}
            disabled={!host.trim() || probe.isPending || save.isPending} onClick={readHostKey}>
            <ScanSearch aria-hidden size={16} />{probe.isPending ? t.reading : t.readHostKey}</button>
          {probedFingerprint !== null && probedFingerprint !== hostKeyFingerprint ? (
            <button className={hostKeyChanged ? 'danger-button' : 'primary-button'} type="button"
              onClick={() => setConfirmations(previous => confirm(previous, { ...currentEndpoint, fingerprint: probedFingerprint }))}>
              {hostKeyChanged ? t.replaceWith : t.trustThis}
            </button>
          ) : null}
        </div>
        {currentProbeFailure !== null ? <InlineAlert tone="danger" title={describeError(currentProbeFailure, i18n)} /> : null}
      </div></WorkspaceSection>

      <WorkspaceSection title={t.synchronization} description={t.synchronizationHint}><div className="section-body"><div className="field-grid">
        <label>{t.interval} <input required type="number" min={MIN_SSH_SYNC_INTERVAL_SECONDS} value={intervalSeconds} onChange={e => setIntervalSeconds(e.target.value)} /></label>
        <label className="checkbox-field checkbox-aligned"><input type="checkbox" checked={scheduleEnabled} onChange={e => setScheduleEnabled(e.target.checked)} /> {t.scheduleEnabled}</label>
      </div></div></WorkspaceSection>

      {validationError ? <div ref={validationRef} tabIndex={-1} className="focus-target"><InlineAlert tone="danger" title={validationError} /></div> : null}
      {/* A check result is shown only while the form still has the settings it was made with. */}
      {currentCheck?.ok === true ? <InlineAlert tone="success" title={t.checkSucceeded}>{t.checkSucceededDetail}</InlineAlert> : null}
      {currentCheck?.ok === false ? <InlineAlert tone="danger" title={t.checkFailed}>{describeError(currentCheck.error, i18n)}</InlineAlert> : null}
      {save.isError ? <InlineAlert tone="danger" title={describeError(save.error, i18n)} /> : null}
      <div className="form-toolbar form-footer">
        <button className="secondary-button" type="button" aria-busy={test.isPending}
          disabled={!credentialEntered || !hostConfirmed || test.isPending || save.isPending} onClick={testConnection}>
          {test.isPending ? t.testing : t.test}</button>
        <Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={save.isPending}>{save.isPending ? i18n.t.common.saving : t.submit}</button>
      </div>
    </form>
  </div></AppShell>
}

/**
 * What the saved connection is today, from the response already loaded: how it signs in, whether
 * a credential is stored, whom it trusts and how its last synchronization went.
 */
function ExistingConnectionState({ connection }: { connection: ConnectionResponse }) {
  const i18n = useI18n()
  const t = i18n.t.connections.form
  const page = i18n.t.connections.page
  const ssh = connection.ssh
  if (!ssh) return null
  const mismatch = connection.lastSync?.errorCode === 'SSH_HOST_KEY_MISMATCH'
  return <WorkspaceSection title={t.currentState}><PropertyGrid columns={2} items={[
    { label: page.authentication, value: i18n.t.connections.authTypes[ssh.authenticationType] ?? ssh.authenticationType },
    { label: page.credentials, value: ssh.credentialConfigured
      ? <StatusIndicator label={ssh.authenticationType === 'PASSWORD' ? t.passwordConfigured : t.privateKeyConfigured} tone="success" />
      : page.credentialsMissing },
    { label: t.identity, value: mismatch ? <StatusIndicator label={t.fingerprintMismatch} tone="danger" icon={ShieldAlert} />
      : ssh.hostTrusted ? <StatusIndicator label={t.statusTrusted} tone="success" icon={ShieldCheck} />
        : <StatusIndicator label={t.requiresConfirmation} tone="warning" icon={ShieldQuestion} /> },
    { label: page.latest, value: connection.lastSync ? getLastSyncSummary(connection.lastSync, i18n) : page.neverSynced },
    { label: page.hostKey, value: ssh.hostKeyFingerprint
      ? <span className="fingerprint-value"><code className="fingerprint">{ssh.hostKeyFingerprint}</code><CopyButton value={ssh.hostKeyFingerprint} /></span>
      : page.notPinned, technical: false },
  ]} /></WorkspaceSection>
}
