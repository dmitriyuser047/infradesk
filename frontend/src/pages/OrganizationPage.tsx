import { useQueries, type UseQueryResult } from '@tanstack/react-query'
import { Building2, Check, ChevronRight, FolderKanban, Layers, Plus, Server } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { getEnvironments, useOrganization, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextQuery } from '../components/layout/workspaceNavigation'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { EnvironmentResponse, ProjectResponse } from '../types/navigation'
import { InvalidRoutePage } from './InvalidRoutePage'

/** Projects and environments: the hierarchy everything else is organized by. */
export function OrganizationPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <AppShell><OrganizationContent key={organizationId} organizationId={organizationId} /></AppShell>
}

function OrganizationContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.workspace
  const [searchParams] = useSearchParams()
  // The URL is the selection: the same `project` and `environment` the context switcher writes.
  const selectedProjectId = searchParams.get('project')
  const selectedEnvironmentId = selectedProjectId ? searchParams.get('environment') : null
  const organizationQuery = useOrganization(organizationId)
  const projectsQuery = useProjects(organizationId)
  const isOwner = useOrganizationPermissions(organizationId).can('manageWorkspace')
  const base = `/organizations/${encodeURIComponent(organizationId)}`

  if (organizationQuery.isError) {
    const notFound = organizationQuery.error instanceof ApiError && organizationQuery.error.code === 'ORGANIZATION_NOT_FOUND'
    return <InlineAlert tone="danger" title={notFound ? t.organizationNotFound : t.unableToLoadOrganization}
      action={notFound ? undefined : <button className="secondary-button" type="button" onClick={() => organizationQuery.refetch()}>{i18n.t.common.retry}</button>} />
  }
  const hasProjects = projectsQuery.isSuccess && projectsQuery.data.length > 0

  return <div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={organizationQuery.data ? `${organizationQuery.data.name} · ${t.subtitle}` : t.subtitle}
      actions={isOwner ? <Link className="primary-button" to={`${base}/projects/new`}><Plus aria-hidden size={16} />{t.newProject}</Link> : null} />
    {/* Full for a first look; once projects exist, one quiet line with the details kept for screen readers. */}
    <ol className={hasProjects ? 'hierarchy hierarchy-compact' : 'hierarchy'} aria-label={t.title}>
      <li><Building2 aria-hidden size={18} /><div><strong>{t.hierarchy.organization}</strong><span>{t.hierarchy.organizationDetail}</span></div></li>
      <li><FolderKanban aria-hidden size={18} /><div><strong>{t.hierarchy.project}</strong><span>{t.hierarchy.projectDetail}</span></div></li>
      <li><Layers aria-hidden size={18} /><div><strong>{t.hierarchy.environment}</strong><span>{t.hierarchy.environmentDetail}</span></div></li>
    </ol>
    {projectsQuery.isPending || organizationQuery.isPending
      ? <div className="workspace-section"><div className="section-content">
        <div className="row-skeleton" aria-label={t.loadingProjects}><span /><span /><span /></div></div></div> : null}
    {projectsQuery.isError ? <InlineAlert tone="danger" title={t.unableToLoadProjects}
      action={<button type="button" className="secondary-button" onClick={() => projectsQuery.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(projectsQuery.error, i18n)}</InlineAlert> : null}
    {projectsQuery.isSuccess && projectsQuery.data.length === 0 ? <EmptyWorkspaceState icon={FolderKanban}
      title={t.noProjects} detail={isOwner ? t.noProjectsDetail : t.noProjectsMember}
      action={isOwner ? <Link className="primary-button" to={`${base}/projects/new`}><Plus aria-hidden size={16} />{t.newProject}</Link> : undefined} /> : null}
    {hasProjects ? <ProjectCards organizationId={organizationId} projects={projectsQuery.data} isOwner={isOwner}
      selectedProjectId={selectedProjectId} selectedEnvironmentId={selectedEnvironmentId} /> : null}
  </div>
}

function ProjectCards({ organizationId, projects, isOwner, selectedProjectId, selectedEnvironmentId }: {
  organizationId: string
  projects: readonly ProjectResponse[]
  isOwner: boolean
  selectedProjectId: string | null
  selectedEnvironmentId: string | null
}) {
  // One environments read per project, cached and shared with the context switcher and the
  // resources index; the count on each card is the length of that same answer, not another read.
  const environments = useQueries({ queries: projects.map(project => ({
    queryKey: ['environments', organizationId, project.id],
    queryFn: () => getEnvironments(organizationId, project.id),
  })) })

  return <div className="project-grid">
    {projects.map((project, index) => <ProjectCard key={project.id} organizationId={organizationId} project={project}
      environments={environments[index]} isOwner={isOwner} selected={project.id === selectedProjectId}
      selectedEnvironmentId={project.id === selectedProjectId ? selectedEnvironmentId : null} />)}
  </div>
}

/** One project: its name, code and environments, and where each of them leads. */
function ProjectCard({ organizationId, project, environments, isOwner, selected, selectedEnvironmentId }: {
  organizationId: string
  project: ProjectResponse
  environments: UseQueryResult<EnvironmentResponse[]> | undefined
  isOwner: boolean
  selected: boolean
  selectedEnvironmentId: string | null
}) {
  const i18n = useI18n()
  const t = i18n.t.workspace
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const headingId = `project-${project.id}`
  const list = environments?.data
  const newEnvironmentPath = `${base}/projects/${encodeURIComponent(project.id)}/environments/new`

  return <section className={selected ? 'workspace-section project-card project-card-selected' : 'workspace-section project-card'}
    aria-labelledby={headingId} aria-current={selected ? 'true' : undefined}>
    <div className="project-card-heading">
      <div className="project-card-title">
        {/* Choosing a project is choosing the context: the link writes it into the URL, like the switcher. */}
        <h2 id={headingId}><Link className="project-link" to={`${base}${contextQuery({ projectId: project.id })}`}
          aria-current={selected ? 'page' : undefined}>{project.name}</Link></h2>
        <span className="project-code">{project.code}</span>
      </div>
      <div className="project-card-meta">
        {selected ? <StatusIndicator label={t.currentProject} tone="info" icon={Check} /> : null}
        {list ? <span className="section-meta">{t.environmentCount(list.length)}</span> : null}
      </div>
    </div>
    {project.description ? <p className="section-description">{project.description}</p> : null}
    <div className="section-content">
      {environments?.isPending ? <div className="row-skeleton" aria-label={t.loadingEnvironments}><span /><span /></div> : null}
      {environments?.isError ? <InlineAlert tone="danger" title={t.unableToLoadEnvironments}
        action={<button className="secondary-button" type="button" onClick={() => environments.refetch()}>{i18n.t.common.retry}</button>} /> : null}
      {list && list.length === 0 ? <EmptyWorkspaceState icon={Layers} title={t.noEnvironments}
        detail={isOwner ? t.noEnvironmentsDetail : t.noEnvironmentsMember}
        action={isOwner ? <Link className="secondary-button" to={newEnvironmentPath} aria-label={t.addEnvironmentTo(project.name)}>
          <Plus aria-hidden size={16} />{i18n.t.environmentForm.submit}</Link> : undefined} /> : null}
      {list && list.length > 0 ? <ul className="link-list">
        {list.map(environment => {
          const current = environment.id === selectedEnvironmentId
          return <li key={environment.id} className={current ? 'environment-row environment-row-selected' : 'environment-row'}>
            <Link className="link-row" aria-label={t.openResourcesOf(environment.name)} aria-current={current ? 'true' : undefined}
              to={`${base}/environments/${encodeURIComponent(environment.id)}${contextQuery({ projectId: project.id })}`}>
              <Server aria-hidden size={18} className="link-row-icon" />
              <span className="link-row-text"><strong>{environment.name}</strong>
                <span className="environment-meta"><small>{environment.code}</small>
                  <StatusIndicator label={getEnvironmentKindLabel(environment.kind, i18n)}
                    tone={environment.kind === 'PROD' ? 'info' : 'neutral'} /></span></span>
              <ChevronRight aria-hidden size={18} className="link-row-chevron" />
            </Link>
            <Link className="text-link environment-overview" aria-label={t.openOverviewOf(environment.name)}
              to={`${base}/overview${contextQuery({ projectId: project.id, environmentId: environment.id })}`}>{t.openOverview}</Link>
          </li>
        })}
      </ul> : null}
    </div>
    <div className="project-card-footer">
      <Link className="text-button" to={`${base}/overview${contextQuery({ projectId: project.id })}`}>{t.projectOverview}</Link>
      {isOwner && list && list.length > 0 ? <Link className="text-button" to={newEnvironmentPath}
        aria-label={t.addEnvironmentTo(project.name)}><Plus aria-hidden size={16} />{t.newEnvironment}</Link> : null}
    </div>
  </section>
}
