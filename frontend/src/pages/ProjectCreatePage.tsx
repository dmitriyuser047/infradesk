import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useCreateProject } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { PermissionGate } from '../components/layout/WorkspaceGate'
import { InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { buildCreateProjectRequest } from '../components/navigation/buildWorkspaceRequests'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ProjectCreatePage() {
  const { t } = useI18n()
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  const organizationPath = `/organizations/${encodeURIComponent(organizationId)}`
  return <PermissionGate organizationId={organizationId} permission="manageWorkspace" title={t.projectForm.title}
    back={{ label: t.projectForm.back, to: organizationPath }} texts={t.projectForm}>
    <ProjectCreateForm organizationId={organizationId} />
  </PermissionGate>
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
      <WorkspaceSection title={t.details} description={t.detailsHint}>
        <div className="field-grid">
          <label>{i18n.t.common.name} <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
          <label>{i18n.t.common.code} <input required maxLength={64} value={code} spellCheck={false} onChange={event => setCode(event.target.value)} /></label>
          <label className="field-span">{i18n.t.common.description} <textarea value={description} onChange={event => setDescription(event.target.value)} /></label>
        </div>
      </WorkspaceSection>
      {create.isError ? <InlineAlert tone="danger" title={t.createFailed}>{describeError(create.error, i18n)}</InlineAlert> : null}
      <div className="form-toolbar"><Link className="secondary-button" to={organizationPath}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={create.isPending} aria-busy={create.isPending}>
          {create.isPending ? i18n.t.common.creating : t.submit}
        </button></div>
    </form>
  </div></AppShell>
}
