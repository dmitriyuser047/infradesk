import { useQueries } from '@tanstack/react-query'
import { ChevronRight, Server } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { getEnvironments, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { contextQuery } from '../components/layout/workspaceNavigation'
import { getDisplayName, getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'

/**
 * Where "Resources" leads before an environment is chosen: servers and containers are discovered
 * per environment, so this page lists the environments to open, grouped by project.
 */
export function ResourcesIndexPage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><ResourcesIndexContent organizationId={organizationId} /></AppShell>
}

function ResourcesIndexContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t
  const [searchParams] = useSearchParams()
  const selectedProject = searchParams.get('project')
  const projects = useProjects(organizationId)
  const visible = (projects.data ?? []).filter(project => !selectedProject || project.id === selectedProject)
  // The same cache entries as the context switcher and the projects page: no extra requests.
  const environments = useQueries({ queries: visible.map(project => ({
    queryKey: ['environments', organizationId, project.id],
    queryFn: () => getEnvironments(organizationId, project.id),
  })) })
  const canManage = useOrganizationPermissions(organizationId).can('manageWorkspace')
  const base = `/organizations/${encodeURIComponent(organizationId)}`

  return <div className="workspace-page">
    <WorkspaceHeader title={t.resources.title} subtitle={t.resources.chooseEnvironmentDetail} />
    {projects.isPending ? <div className="row-skeleton" aria-label={t.workspace.loadingProjects}><span /><span /><span /></div> : null}
    {projects.isError ? <InlineAlert tone="danger" title={t.workspace.unableToLoadProjects}
      action={<button type="button" className="secondary-button" onClick={() => projects.refetch()}>{t.common.retry}</button>}>
      {describeError(projects.error, i18n)}</InlineAlert> : null}
    {projects.isSuccess && visible.length === 0 ? <EmptyWorkspaceState title={t.workspace.noProjects}
      detail={canManage ? t.workspace.noProjectsDetail : t.workspace.noProjectsMember}
      action={canManage ? <Link className="primary-button" to={`${base}/projects/new`}>{t.workspace.newProject}</Link> : undefined} /> : null}
    {visible.map((project, index) => {
      const query = environments[index]
      return <WorkspaceSection key={project.id} title={getDisplayName(project)}>
        {query?.isPending ? <div className="row-skeleton" aria-label={t.workspace.loadingEnvironments}><span /><span /></div> : null}
        {query?.isError ? <InlineAlert tone="danger" title={t.workspace.unableToLoadEnvironments}
          action={<button type="button" className="secondary-button" onClick={() => query.refetch()}>{t.common.retry}</button>} /> : null}
        {query?.isSuccess && query.data.length === 0 ? <EmptyWorkspaceState title={t.workspace.noEnvironments} /> : null}
        {query?.data && query.data.length > 0 ? <ul className="link-list">
          {query.data.map(environment => <li key={environment.id}>
            <Link className="link-row" to={`${base}/environments/${encodeURIComponent(environment.id)}${contextQuery({ projectId: project.id })}`}>
              <Server aria-hidden size={18} className="link-row-icon" />
              <span className="link-row-text"><strong>{getDisplayName(environment)}</strong>
                <small>{getEnvironmentKindLabel(environment.kind, i18n)} · {environment.code}</small></span>
              <ChevronRight aria-hidden size={18} className="link-row-chevron" />
            </Link>
          </li>)}
        </ul> : null}
      </WorkspaceSection>
    })}
  </div>
}
