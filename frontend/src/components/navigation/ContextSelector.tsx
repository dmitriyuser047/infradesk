import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/httpClient'
import { useEnvironments, useProjects } from '../../api/navigation'
import { EmptyWorkspaceState, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { getEnvironmentKindLabel, validSelection } from './navigationPresentation'
import { useI18n } from '../../i18n'

interface ContextSelectorProps {
  organizationId: string
  projectsQuery: ReturnType<typeof useProjects>
  isOwner: boolean
}

export function ContextSelector({ organizationId, projectsQuery, isOwner }: ContextSelectorProps) {
  const i18n = useI18n()
  const t = i18n.t.workspace
  const [searchParams, setSearchParams] = useSearchParams()
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const projectId = projectsQuery.data ? validSelection(searchParams.get('project'), projectsQuery.data) : null
  const environmentsQuery = useEnvironments(organizationId, projectId)
  const environmentId = environmentsQuery.data
    ? validSelection(searchParams.get('environment'), environmentsQuery.data) : null
  const selectedProject = projectsQuery.data?.find(project => project.id === projectId)

  useEffect(() => {
    if (!projectId) return
    const next = new URLSearchParams({ project: projectId })
    if (environmentId) next.set('environment', environmentId)
    if (searchParams.get('project') !== projectId || searchParams.get('environment') !== (environmentId ?? null)) {
      setSearchParams(next, { replace: true })
    }
  }, [projectId, environmentId, searchParams, setSearchParams])

  function selectProject(next: string) { setSearchParams({ project: next }) }
  function selectEnvironment(next: string) {
    if (projectId) setSearchParams({ project: projectId, environment: next })
  }

  return <div className="workspace-split workspace-context" aria-label={i18n.t.workspace.title}>
    <WorkspaceSection title={t.projects} actions={isOwner ? <Link className="secondary-button" to={`${base}/projects/new`}>{t.newProject}</Link> : null}>
      {projectsQuery.isPending ? <div className="row-skeleton" aria-label={t.loadingProjects}><span /><span /><span /></div> : null}
      {projectsQuery.isError ? <div className="inline-error" role="alert">
        {projectsQuery.error instanceof ApiError && projectsQuery.error.code === 'ORGANIZATION_NOT_FOUND'
          ? t.organizationNotFound : t.unableToLoadProjects}
        <button type="button" className="text-button" onClick={() => projectsQuery.refetch()}>{i18n.t.common.retry}</button>
      </div> : null}
      {projectsQuery.isSuccess && projectsQuery.data.length === 0 ?
        <EmptyWorkspaceState title={t.noProjects} detail={isOwner ? t.noProjectsDetail : t.noProjectsMember} /> : null}
      {projectsQuery.isSuccess && projectsQuery.data.length > 0 ? <div className="master-list" aria-label={t.projects}>
        {projectsQuery.data.map(project => <button type="button" aria-pressed={project.id === projectId}
          className={`master-row ${project.id === projectId ? 'selected' : ''}`} key={project.id}
          onClick={() => selectProject(project.id)}>
          <span><strong>{project.name}</strong><small>{project.code}</small></span>
        </button>)}
      </div> : null}
    </WorkspaceSection>
    <WorkspaceSection title={selectedProject ? t.environmentsOf(selectedProject.name) : t.environments}
      actions={<span className="context-frame-actions">
        {environmentId && projectId ? <Link className="primary-button" to={`${base}/environments/${encodeURIComponent(environmentId)}?project=${encodeURIComponent(projectId)}`}>{t.openResources}</Link> : null}
        {projectId && isOwner ? <Link className="secondary-button" to={`${base}/projects/${encodeURIComponent(projectId)}/environments/new`}>{t.newEnvironment}</Link> : null}
      </span>}>
      {!projectId ? <EmptyWorkspaceState title={t.selectProject} detail={t.selectProjectDetail} /> : null}
      {environmentsQuery.isPending && projectId ? <div className="row-skeleton" aria-label={t.loadingEnvironments}><span /><span /></div> : null}
      {environmentsQuery.isError ? <div className="inline-error" role="alert">{t.unableToLoadEnvironments}
        <button type="button" className="text-button" onClick={() => environmentsQuery.refetch()}>{i18n.t.common.retry}</button></div> : null}
      {environmentsQuery.isSuccess && environmentsQuery.data.length === 0 ?
        <EmptyWorkspaceState title={t.noEnvironments} detail={t.noEnvironmentsDetail} /> : null}
      {environmentsQuery.isSuccess && environmentsQuery.data.length > 0 ? <div className="master-list" aria-label={t.environments}>
        {environmentsQuery.data.map(environment => <button type="button" aria-pressed={environment.id === environmentId}
          className={`master-row ${environment.id === environmentId ? 'selected' : ''}`}
          key={environment.id} onClick={() => selectEnvironment(environment.id)}>
          <span><strong>{environment.name}</strong><small>{getEnvironmentKindLabel(environment.kind, i18n)} · {environment.code}</small></span>
        </button>)}
      </div> : null}
    </WorkspaceSection>
  </div>
}
