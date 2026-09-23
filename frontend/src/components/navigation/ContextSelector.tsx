import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { ApiError } from '../../api/httpClient'
import { useEnvironments, useProjects } from '../../api/navigation'
import { EmptyWorkspaceState, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { getEnvironmentKindLabel, validSelection } from './navigationPresentation'

interface ContextSelectorProps {
  organizationId: string
  projectsQuery: ReturnType<typeof useProjects>
  isOwner: boolean
}

export function ContextSelector({ organizationId, projectsQuery, isOwner }: ContextSelectorProps) {
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

  return <div className="workspace-split workspace-context" aria-label="Projects and environments">
    <WorkspaceSection title="Projects" actions={isOwner ? <Link className="secondary-button" to={`${base}/projects/new`}>+ New project</Link> : null}>
      {projectsQuery.isPending ? <div className="row-skeleton" aria-label="Loading projects"><span /><span /><span /></div> : null}
      {projectsQuery.isError ? <div className="inline-error" role="alert">
        {projectsQuery.error instanceof ApiError && projectsQuery.error.code === 'ORGANIZATION_NOT_FOUND'
          ? 'Organization not found' : 'Unable to load projects'}
        <button type="button" className="text-button" onClick={() => projectsQuery.refetch()}>Retry</button>
      </div> : null}
      {projectsQuery.isSuccess && projectsQuery.data.length === 0 ?
        <EmptyWorkspaceState title="No projects configured" action={isOwner ? <Link to={`${base}/projects/new`}>Create project</Link> : undefined} /> : null}
      {projectsQuery.isSuccess && projectsQuery.data.length > 0 ? <div className="master-list" aria-label="Projects">
        {projectsQuery.data.map(project => <button type="button" aria-pressed={project.id === projectId}
          className={`master-row ${project.id === projectId ? 'selected' : ''}`} key={project.id}
          onClick={() => selectProject(project.id)}>
          <span><strong>{project.name}</strong><small>{project.code}</small></span><span aria-hidden>›</span>
        </button>)}
      </div> : null}
    </WorkspaceSection>
    <WorkspaceSection title={selectedProject ? `Environments · ${selectedProject.name}` : 'Environments'}
      actions={projectId && isOwner ? <Link className="secondary-button" to={`${base}/projects/${encodeURIComponent(projectId)}/environments/new`}>+ New environment</Link> : null}>
      {!projectId ? <EmptyWorkspaceState title="Select a project" detail="Its environments will appear here." /> : null}
      {environmentsQuery.isPending && projectId ? <div className="row-skeleton" aria-label="Loading environments"><span /><span /></div> : null}
      {environmentsQuery.isError ? <div className="inline-error" role="alert">Unable to load environments
        <button type="button" className="text-button" onClick={() => environmentsQuery.refetch()}>Retry</button></div> : null}
      {environmentsQuery.isSuccess && environmentsQuery.data.length === 0 ?
        <EmptyWorkspaceState title="No environments configured for this project"
          action={isOwner ? <Link to={`${base}/projects/${encodeURIComponent(projectId ?? '')}/environments/new`}>Add environment</Link> : undefined} /> : null}
      {environmentsQuery.isSuccess && environmentsQuery.data.length > 0 ? <div className="master-list" aria-label="Environments">
        {environmentsQuery.data.map(environment => <button type="button" aria-pressed={environment.id === environmentId}
          className={`master-row ${environment.id === environmentId ? 'selected' : ''}`}
          key={environment.id} onClick={() => selectEnvironment(environment.id)}>
          <span><strong>{environment.name}</strong><small>{getEnvironmentKindLabel(environment.kind)} · {environment.code}</small></span>
          <span aria-hidden>›</span>
        </button>)}
      </div> : null}
      {environmentId && projectId ? <div className="workspace-section-footer"><Link className="primary-button"
        to={`${base}/environments/${encodeURIComponent(environmentId)}?project=${encodeURIComponent(projectId)}`}>
        Open infrastructure</Link></div> : null}
    </WorkspaceSection>
  </div>
}
