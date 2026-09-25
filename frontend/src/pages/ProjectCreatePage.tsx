import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useCreateProject } from '../api/navigation'
import { canOrganization } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { buildCreateProjectRequest } from '../components/navigation/buildWorkspaceRequests'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ProjectCreatePage() {
  const { t } = useI18n()
  const { organizationId } = useParams()
  const membership = useMyOrganizations()

  if (!organizationId) return <InvalidRoutePage />
  if (membership.isPending) return <AppShell><p className="compact-state">{t.projectForm.loading}</p></AppShell>
  if (membership.isError) return <AppShell><p className="compact-state" role="alert">{t.projectForm.accessCheckFailed}</p></AppShell>
  if (!canOrganization(membership.data?.find(value => value.id === organizationId)?.role, 'manageWorkspace')) {
    return <AppShell><p className="compact-state">{t.projectForm.ownersOnly}</p></AppShell>
  }
  return <ProjectCreateForm organizationId={organizationId} />
}

function ProjectCreateForm({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.projectForm
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
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} back={{ label: t.back, to: organizationPath }} />
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title={t.details}>
        <div className="field-grid">
          <label>{i18n.t.common.name} <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
          <label>{i18n.t.common.code} <input required maxLength={64} value={code} onChange={event => setCode(event.target.value)} /></label>
          <label className="field-span">{i18n.t.common.description} <textarea value={description} onChange={event => setDescription(event.target.value)} /></label>
        </div>
      </WorkspaceSection>
      {create.isError ? <p className="inline-error" role="alert">{describeError(create.error, i18n)}</p> : null}
      <div className="form-toolbar"><Link className="secondary-button" to={organizationPath}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={create.isPending}>
          {create.isPending ? i18n.t.common.creating : t.submit}
        </button></div>
    </form>
  </div></AppShell>
}
