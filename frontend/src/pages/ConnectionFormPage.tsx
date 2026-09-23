import { FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useConnection, useCreateConnection, useTestSshConnection, useUpdateConnection } from '../api/connections'
import { useEnvironments, useProjects } from '../api/navigation'
import { ApiError } from '../api/httpClient'
import { AppShell } from '../components/layout/AppShell'
import { buildSshConnectionRequest, MIN_SSH_SYNC_INTERVAL_SECONDS } from '../components/connections/buildSshConnectionRequest'
import type { ConnectionResponse } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionFormPage() {
  const { organizationId, connectionId } = useParams()
  const membership = useMyOrganizations()
  const existing = useConnection(organizationId, connectionId)

  if (!organizationId) return <InvalidRoutePage />
  if (membership.isPending || (connectionId && existing.isPending)) return <AppShell><p>Loading connection…</p></AppShell>
  if (membership.data?.find(value => value.id === organizationId)?.role !== 'OWNER') {
    return <AppShell><p>Only organization owners can manage connections.</p></AppShell>
  }
  if (connectionId && (existing.isError || !existing.data)) {
    return <AppShell><p>Connection not found.</p></AppShell>
  }
  if (existing.data && (existing.data.connectorType !== 'SSH' || !existing.data.active)) {
    return <Navigate to={`/organizations/${organizationId}/connections/${connectionId}`} replace />
  }

  return <ConnectionForm key={connectionId ?? 'new'} organizationId={organizationId} existing={existing.data} />
}

function ConnectionForm({ organizationId, existing }: { organizationId: string; existing?: ConnectionResponse }) {
  const navigate = useNavigate()
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
  const [password, setPassword] = useState('')
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
  const save = existing ? update : create
  const back = existing
    ? `/organizations/${organizationId}/connections/${existing.id}`
    : `/organizations/${organizationId}/connections`

  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const body = buildSshConnectionRequest({ code, name, projectId, environmentId, host, port,
        username, password, scheduleEnabled, intervalSeconds })
      setValidationError(null)
      save.submit(body, connection => navigate(`/organizations/${organizationId}/connections/${connection.id}`))
    } catch (error) {
      setValidationError(error instanceof Error ? error.message : 'Invalid sync interval')
    }
  }

  return <AppShell><div className="detail-page connection-detail-page">
    <Link className="back-link" to={back}>← Back to connections</Link>
    <header className="page-header"><div><p className="eyebrow">SSH onboarding</p>
      <h1>{existing ? 'Edit connection' : 'Add SSH connection'}</h1>
      <p className="page-subtitle">A working SSH login is verified before saving.</p></div></header>
    <form className="content-panel connection-form" onSubmit={submit}>
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
      <label>Host <input required value={host} onChange={e => setHost(e.target.value)} /></label>
      <label>Port <input required type="number" min="1" max="65535" value={port} onChange={e => setPort(e.target.value)} /></label>
      <label>Username <input required value={username} onChange={e => setUsername(e.target.value)} /></label>
      <label>Password {existing ? '(leave blank to keep current)' : ''}
        <input type="password" required={!existing} autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} />
      </label>
      <label>Sync interval (seconds) <input required type="number" min={MIN_SSH_SYNC_INTERVAL_SECONDS} value={intervalSeconds} onChange={e => setIntervalSeconds(e.target.value)} /></label>
      <label><input type="checkbox" checked={scheduleEnabled} onChange={e => setScheduleEnabled(e.target.checked)} /> Enable scheduled sync</label>
      {validationError ? <p role="alert">{validationError}</p> : null}
      {testedFingerprint ? <p role="status">SSH verified · {testedFingerprint}</p> : null}
      {test.isError ? <p role="alert">{errorText(test.error)}</p> : null}
      {save.isError ? <p role="alert">{errorText(save.error)}</p> : null}
      <div className="state-actions">
        <button type="button" disabled={!password || test.isPending || save.isPending} onClick={() => {
          setTestedFingerprint(null)
          test.submit({ host: host.trim(), port: Number(port), username: username.trim(), credentials: { type: 'PASSWORD', password } },
            result => setTestedFingerprint(result.hostKeyFingerprint))
        }}>{test.isPending ? 'Testing…' : 'Test connection'}</button>
        <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save connection'}</button>
      </div>
    </form>
  </div></AppShell>
}

function errorText(error: Error): string {
  return error instanceof ApiError ? error.message : 'Unable to complete request. Please try again.'
}
