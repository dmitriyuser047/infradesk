import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { useCreateProject } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { buildCreateProjectRequest } from '../components/navigation/buildWorkspaceRequests'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ProjectCreatePage() {
  const { organizationId } = useParams()
  const membership = useMyOrganizations()

  if (!organizationId) return <InvalidRoutePage />
  if (membership.isPending) return <AppShell><p>Loading organization…</p></AppShell>
  if (membership.isError) return <AppShell><p role="alert">Unable to check organization access.</p></AppShell>
  if (membership.data?.find(value => value.id === organizationId)?.role !== 'OWNER') {
    return <AppShell><p>Only organization owners can create projects.</p></AppShell>
  }
  return <ProjectCreateForm organizationId={organizationId} />
}

function ProjectCreateForm({ organizationId }: { organizationId: string }) {
  const navigate = useNavigate()
  const create = useCreateProject(organizationId)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const organizationPath = `/organizations/${encodeURIComponent(organizationId)}`

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate(buildCreateProjectRequest(code, name, description), {
      onSuccess: project => navigate(`${organizationPath}/projects/${encodeURIComponent(project.id)}/environments/new`),
    })
  }

  return <AppShell><div className="detail-page">
    <Link className="back-link" to={organizationPath}>← Back to organization</Link>
    <header className="page-header"><div><p className="eyebrow">Workspace setup</p><h1>Create project</h1></div></header>
    <form className="content-panel connection-form" onSubmit={submit}>
      <label>Name <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
      <label>Code <input required maxLength={64} value={code} onChange={event => setCode(event.target.value)} /></label>
      <label>Description <textarea value={description} onChange={event => setDescription(event.target.value)} /></label>
      {create.isError ? <p role="alert">{create.error instanceof ApiError ? create.error.message : 'Unable to create project. Please try again.'}</p> : null}
      <div className="state-actions"><button type="submit" disabled={create.isPending}>
        {create.isPending ? 'Creating…' : 'Create project'}
      </button></div>
    </form>
  </div></AppShell>
}
