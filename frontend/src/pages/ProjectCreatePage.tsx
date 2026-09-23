import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { useCreateProject } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
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

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title="Create project" subtitle="Add a project to this organization"
      back={{ label: 'Organization', to: organizationPath }} />
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title="Project details">
        <div className="field-grid">
          <label>Name <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
          <label>Code <input required maxLength={64} value={code} onChange={event => setCode(event.target.value)} /></label>
          <label className="field-span">Description <textarea value={description} onChange={event => setDescription(event.target.value)} /></label>
        </div>
      </WorkspaceSection>
      {create.isError ? <p className="inline-error" role="alert">{create.error instanceof ApiError ? create.error.message : 'Unable to create project. Please try again.'}</p> : null}
      <div className="form-toolbar"><Link className="secondary-button" to={organizationPath}>Cancel</Link>
        <button className="primary-button" type="submit" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Create project'}
        </button></div>
    </form>
  </div></AppShell>
}
