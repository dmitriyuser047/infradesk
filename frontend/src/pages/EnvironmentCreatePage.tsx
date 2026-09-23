import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { useCreateEnvironment, useProjects } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { buildCreateEnvironmentRequest } from '../components/navigation/buildWorkspaceRequests'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { EnvironmentKind, type EnvironmentKindCode } from '../types/navigation'
import { InvalidRoutePage } from './InvalidRoutePage'

export function EnvironmentCreatePage() {
  const { organizationId, projectId } = useParams()
  const membership = useMyOrganizations()

  if (!organizationId || !projectId) return <InvalidRoutePage />
  if (membership.isPending) return <AppShell><p>Loading organization…</p></AppShell>
  if (membership.isError) return <AppShell><p role="alert">Unable to check organization access.</p></AppShell>
  if (membership.data?.find(value => value.id === organizationId)?.role !== 'OWNER') {
    return <AppShell><p>Only organization owners can create environments.</p></AppShell>
  }
  return <EnvironmentCreateForm organizationId={organizationId} projectId={projectId} />
}

function EnvironmentCreateForm({ organizationId, projectId }: { organizationId: string; projectId: string }) {
  const navigate = useNavigate()
  const projects = useProjects(organizationId)
  const create = useCreateEnvironment(organizationId, projectId)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [kind, setKind] = useState<EnvironmentKindCode>(EnvironmentKind.prod)
  const organizationPath = `/organizations/${encodeURIComponent(organizationId)}`
  const project = projects.data?.find(value => value.id === projectId)

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate(buildCreateEnvironmentRequest(code, name, kind), {
      onSuccess: () => navigate(organizationPath),
    })
  }

  return <AppShell><div className="detail-page">
    <Link className="back-link" to={organizationPath}>← Back to organization</Link>
    <header className="page-header"><div><p className="eyebrow">{project?.name ?? 'Workspace setup'}</p>
      <h1>Create environment</h1></div></header>
    {projects.isError ? <p role="alert">Unable to load project.</p> : null}
    {projects.isSuccess && !project ? <p role="alert">Project not found.</p> : null}
    <form className="content-panel connection-form" onSubmit={submit}>
      <label>Name <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
      <label>Code <input required maxLength={64} value={code} onChange={event => setCode(event.target.value)} /></label>
      <label>Kind <select value={kind} onChange={event => setKind(event.target.value as EnvironmentKindCode)}>
        {Object.values(EnvironmentKind).map(value => <option key={value} value={value}>{getEnvironmentKindLabel(value)}</option>)}
      </select></label>
      {create.isError ? <p role="alert">{create.error instanceof ApiError ? create.error.message : 'Unable to create environment. Please try again.'}</p> : null}
      <div className="state-actions"><button type="submit" disabled={create.isPending || !project}>
        {create.isPending ? 'Creating…' : 'Create environment'}
      </button></div>
    </form>
  </div></AppShell>
}
