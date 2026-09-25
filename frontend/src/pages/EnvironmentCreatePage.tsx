import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { useCreateEnvironment, useProjects } from '../api/navigation'
import { canOrganization } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { buildCreateEnvironmentRequest } from '../components/navigation/buildWorkspaceRequests'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { EnvironmentKind, type EnvironmentKindCode } from '../types/navigation'
import { InvalidRoutePage } from './InvalidRoutePage'

export function EnvironmentCreatePage() {
  const { t } = useI18n()
  const { organizationId, projectId } = useParams()
  const membership = useMyOrganizations()

  if (!organizationId || !projectId) return <InvalidRoutePage />
  if (membership.isPending) return <AppShell><p className="compact-state">{t.environmentForm.loading}</p></AppShell>
  if (membership.isError) return <AppShell><p className="compact-state" role="alert">{t.environmentForm.accessCheckFailed}</p></AppShell>
  if (!canOrganization(membership.data?.find(value => value.id === organizationId)?.role, 'manageWorkspace')) {
    return <AppShell><p className="compact-state">{t.environmentForm.ownersOnly}</p></AppShell>
  }
  return <EnvironmentCreateForm organizationId={organizationId} projectId={projectId} />
}

function EnvironmentCreateForm({ organizationId, projectId }: { organizationId: string; projectId: string }) {
  const i18n = useI18n()
  const t = i18n.t.environmentForm
  const navigate = useNavigate()
  const projects = useProjects(organizationId)
  const create = useCreateEnvironment(organizationId, projectId)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [kind, setKind] = useState<EnvironmentKindCode>(EnvironmentKind.prod)
  const organizationPath = `/organizations/${encodeURIComponent(organizationId)}`
  const backPath = `${organizationPath}?project=${encodeURIComponent(projectId)}`
  const project = projects.data?.find(value => value.id === projectId)

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate(buildCreateEnvironmentRequest(code, name, kind), {
      onSuccess: environment => navigate(
        `${organizationPath}?project=${encodeURIComponent(projectId)}&environment=${encodeURIComponent(environment.id)}`),
    })
  }

  return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={t.title} subtitle={project ? t.subtitleProject(project.name) : t.subtitleFallback}
      back={{ label: t.back, to: backPath }} />
    {projects.isError ? <p className="inline-error" role="alert">{t.loadProjectFailed}</p> : null}
    {projects.isSuccess && !project ? <p className="inline-error" role="alert">{t.projectNotFound}</p> : null}
    <form className="workspace-form" onSubmit={submit}>
      <WorkspaceSection title={t.details}><div className="field-grid">
        <label>{i18n.t.common.name} <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
        <label>{i18n.t.common.code} <input required maxLength={64} value={code} onChange={event => setCode(event.target.value)} /></label>
        <label>{t.kind} <select value={kind} onChange={event => setKind(event.target.value as EnvironmentKindCode)}>
          {Object.values(EnvironmentKind).map(value => <option key={value} value={value}>{getEnvironmentKindLabel(value, i18n)}</option>)}
        </select></label>
      </div></WorkspaceSection>
      {create.isError ? <p className="inline-error" role="alert">{describeError(create.error, i18n)}</p> : null}
      <div className="form-toolbar"><Link className="secondary-button" to={backPath}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={create.isPending || !project}>
          {create.isPending ? i18n.t.common.creating : t.submit}
        </button></div>
    </form>
  </div></AppShell>
}
