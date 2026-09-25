import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { useCreateEnvironment, useProjects } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { PermissionGate } from '../components/layout/WorkspaceGate'
import { InlineAlert, PropertyGrid, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { buildCreateEnvironmentRequest } from '../components/navigation/buildWorkspaceRequests'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { EnvironmentKind, type EnvironmentKindCode } from '../types/navigation'
import { InvalidRoutePage } from './InvalidRoutePage'

export function EnvironmentCreatePage() {
  const { t } = useI18n()
  const { organizationId, projectId } = useParams()
  if (!organizationId || !projectId) return <InvalidRoutePage />
  const backPath = `/organizations/${encodeURIComponent(organizationId)}?project=${encodeURIComponent(projectId)}`
  return <PermissionGate organizationId={organizationId} permission="manageWorkspace" title={t.environmentForm.title}
    back={{ label: t.environmentForm.back, to: backPath }} texts={t.environmentForm}>
    <EnvironmentCreateForm organizationId={organizationId} projectId={projectId} />
  </PermissionGate>
}

/** The project comes from the route; the form names it and never offers to pick another one. */
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
    {projects.isError ? <InlineAlert tone="danger" title={t.loadProjectFailed}
      action={<button className="secondary-button" type="button" onClick={() => projects.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {projects.isSuccess && !project ? <InlineAlert tone="danger" title={t.projectNotFound} /> : null}
    <form className="workspace-form" onSubmit={submit}>
      {project ? <WorkspaceSection title={t.target}><PropertyGrid items={[
        { label: i18n.t.context.project, value: project.name },
        { label: i18n.t.common.code, value: project.code, technical: true },
      ]} /></WorkspaceSection> : null}
      <WorkspaceSection title={t.details} description={t.detailsHint}><div className="field-grid">
        <label>{i18n.t.common.name} <input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
        <label>{i18n.t.common.code} <input required maxLength={64} value={code} spellCheck={false} onChange={event => setCode(event.target.value)} /></label>
        <label>{t.kind} <select value={kind} onChange={event => setKind(event.target.value as EnvironmentKindCode)}>
          {Object.values(EnvironmentKind).map(value => <option key={value} value={value}>{getEnvironmentKindLabel(value, i18n)}</option>)}
        </select></label>
      </div></WorkspaceSection>
      {create.isError ? <InlineAlert tone="danger" title={t.createFailed}>{describeError(create.error, i18n)}</InlineAlert> : null}
      <div className="form-toolbar"><Link className="secondary-button" to={backPath}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={create.isPending || !project} aria-busy={create.isPending}>
          {create.isPending ? i18n.t.common.creating : t.submit}
        </button></div>
    </form>
  </div></AppShell>
}
