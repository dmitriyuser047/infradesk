import { useQueries } from '@tanstack/react-query'
import { Building2, ChevronRight, FolderKanban, Layers, Plus, Server } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { getEnvironments, useOrganization, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextQuery } from '../components/layout/workspaceNavigation'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { ProjectResponse } from '../types/navigation'
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
  const organizationQuery = useOrganization(organizationId)
  const projectsQuery = useProjects(organizationId)
  const isOwner = useOrganizationPermissions(organizationId).can('manageWorkspace')
  const base = `/organizations/${encodeURIComponent(organizationId)}`

  if (organizationQuery.isError) {
    const notFound = organizationQuery.error instanceof ApiError && organizationQuery.error.code === 'ORGANIZATION_NOT_FOUND'
    return <InlineAlert tone="danger" title={notFound ? t.organizationNotFound : t.unableToLoadOrganization}
      action={notFound ? undefined : <button className="secondary-button" type="button" onClick={() => organizationQuery.refetch()}>{i18n.t.common.retry}</button>} />
  }

  return <div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={organizationQuery.data ? `${organizationQuery.data.name} · ${t.subtitle}` : t.subtitle}
      actions={isOwner ? <Link className="primary-button" to={`${base}/projects/new`}><Plus aria-hidden size={16} />{t.newProject}</Link> : null} />
    <ol className="hierarchy" aria-label={t.title}>
      <li><Building2 aria-hidden size={18} /><div><strong>{t.hierarchy.organization}</strong><span>{t.hierarchy.organizationDetail}</span></div></li>
      <li><FolderKanban aria-hidden size={18} /><div><strong>{t.hierarchy.project}</strong><span>{t.hierarchy.projectDetail}</span></div></li>
      <li><Layers aria-hidden size={18} /><div><strong>{t.hierarchy.environment}</strong><span>{t.hierarchy.environmentDetail}</span></div></li>
    </ol>
    {projectsQuery.isPending || organizationQuery.isPending
      ? <div className="workspace-section"><div className="row-skeleton" aria-label={t.loadingProjects}><span /><span /><span /></div></div> : null}
    {projectsQuery.isError ? <InlineAlert tone="danger" title={t.unableToLoadProjects}
      action={<button type="button" className="secondary-button" onClick={() => projectsQuery.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(projectsQuery.error, i18n)}</InlineAlert> : null}
    {projectsQuery.isSuccess && projectsQuery.data.length === 0 ? <EmptyWorkspaceState icon={FolderKanban}
      title={t.noProjects} detail={isOwner ? t.noProjectsDetail : t.noProjectsMember}
      action={isOwner ? <Link className="primary-button" to={`${base}/projects/new`}><Plus aria-hidden size={16} />{t.newProject}</Link> : undefined} /> : null}
    {projectsQuery.isSuccess && projectsQuery.data.length > 0
      ? <ProjectCards organizationId={organizationId} projects={projectsQuery.data} isOwner={isOwner} /> : null}
  </div>
}

function ProjectCards({ organizationId, projects, isOwner }: {
  organizationId: string
  projects: readonly ProjectResponse[]
  isOwner: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.workspace
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  // Cached per project, shared with the context switcher and the resources index.
  const environments = useQueries({ queries: projects.map(project => ({
    queryKey: ['environments', organizationId, project.id],
    queryFn: () => getEnvironments(organizationId, project.id),
  })) })

  return <div className="project-grid">
    {projects.map((project, index) => {
      const query = environments[index]
      return <WorkspaceSection key={project.id} title={project.name} className="project-card"
        description={project.description ?? undefined}
        actions={query?.data ? <span className="section-meta">{t.environmentCount(query.data.length)}</span> : null}>
        {query?.isPending ? <div className="row-skeleton" aria-label={t.loadingEnvironments}><span /><span /></div> : null}
        {query?.isError ? <InlineAlert tone="danger" title={t.unableToLoadEnvironments} /> : null}
        {query?.isSuccess && query.data.length === 0
          ? <EmptyWorkspaceState title={t.noEnvironments} detail={isOwner ? t.noEnvironmentsDetail : undefined} /> : null}
        {query?.data && query.data.length > 0 ? <ul className="link-list">
          {query.data.map(environment => {
            const scope = contextQuery({ projectId: project.id, environmentId: environment.id })
            return <li key={environment.id} className="environment-row">
              <Link className="link-row" to={`${base}/environments/${encodeURIComponent(environment.id)}${contextQuery({ projectId: project.id })}`}>
                <Server aria-hidden size={18} className="link-row-icon" />
                <span className="link-row-text"><strong>{environment.name}</strong><small>{environment.code}</small></span>
                <StatusIndicator label={getEnvironmentKindLabel(environment.kind, i18n)}
                  tone={environment.kind === 'PROD' ? 'info' : 'neutral'} />
                <ChevronRight aria-hidden size={18} className="link-row-chevron" />
              </Link>
              <Link className="text-link environment-overview" to={`${base}/overview${scope}`}>{t.openOverview}</Link>
            </li>
          })}
        </ul> : null}
        {isOwner ? <div className="project-card-footer">
          <Link className="text-button" to={`${base}/projects/${encodeURIComponent(project.id)}/environments/new`}
            aria-label={t.addEnvironmentTo(project.name)}><Plus aria-hidden size={16} />{t.newEnvironment}</Link>
        </div> : null}
      </WorkspaceSection>
    })}
  </div>
}
