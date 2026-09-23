import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { ApiError } from '../../api/httpClient'
import { useEnvironments, useProjects } from '../../api/navigation'
import { getEnvironmentKindLabel, selectProject, validSelection, type ContextSelection } from './navigationPresentation'

interface ContextSelectorProps {
  organizationId: string
  projectsQuery: ReturnType<typeof useProjects>
  isOwner: boolean
}

export function ContextSelector({ organizationId, projectsQuery, isOwner }: ContextSelectorProps) {
  const navigate = useNavigate()
  const [selection, setSelection] = useState<ContextSelection>({ projectId: null, environmentId: null })

  const projectId = projectsQuery.data === undefined
    ? null
    : validSelection(selection.projectId, projectsQuery.data)
  const environmentsQuery = useEnvironments(organizationId, projectId)
  const environmentId = environmentsQuery.isSuccess && environmentsQuery.data !== undefined
    ? validSelection(selection.projectId === projectId ? selection.environmentId : null, environmentsQuery.data)
    : null

  function handleProjectChange(nextProjectId: string) {
    setSelection(selectProject(nextProjectId))
  }

  function openInfrastructure() {
    if (projectId !== null && environmentId !== null) {
      navigate(`/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}`)
    }
  }

  return (
    <section className="content-panel context-panel" aria-labelledby="context-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Select where to work</p>
          <h2 id="context-heading">Workspace context</h2>
        </div>
      </div>
      {projectsQuery.isPending ? (
        <div className="context-skeleton" aria-label="Loading projects"><span /><span /></div>
      ) : null}
      {projectsQuery.isError ? (
        <div className="context-area-error" role="alert">
          <p>{projectsQuery.error instanceof ApiError && projectsQuery.error.code === 'ORGANIZATION_NOT_FOUND'
            ? 'Organization not found'
            : 'Unable to load projects'}</p>
          <button className="retry-button" type="button" onClick={() => projectsQuery.refetch()}>Retry</button>
        </div>
      ) : null}
      {projectsQuery.isSuccess && projectsQuery.data.length === 0 ? (
        <div className="context-empty">
          <h3>No projects configured</h3>
          <p>Projects will appear here once they are configured.</p>
          {isOwner ? <Link to={`/organizations/${encodeURIComponent(organizationId)}/projects/new`}>Create project</Link> : null}
        </div>
      ) : null}
      {projectsQuery.isSuccess && projectId !== null ? (
        <>
          <div className="context-fields">
            <label className="context-field">
              <span>Project</span>
              <select value={projectId} onChange={(event) => handleProjectChange(event.target.value)}>
                {projectsQuery.data.map((project) => (
                  <option key={project.id} value={project.id}>{project.name}</option>
                ))}
              </select>
              <small>{projectsQuery.data.find((project) => project.id === projectId)?.code}</small>
            </label>
            <div className="context-field">
              <label htmlFor="context-environment">Environment</label>
              {environmentsQuery.isError ? (
                <div className="context-area-error" role="alert">
                  <p>{environmentsQuery.error instanceof ApiError && environmentsQuery.error.code === 'PROJECT_NOT_FOUND'
                    ? 'Project is no longer available'
                    : 'Unable to load environments'}</p>
                  <button className="retry-button" type="button" onClick={() => environmentsQuery.refetch()}>Retry</button>
                </div>
              ) : (
                <>
                  <select
                    id="context-environment"
                    value={environmentId ?? ''}
                    disabled={!environmentsQuery.isSuccess || environmentsQuery.data?.length === 0}
                    onChange={(event) => setSelection({ projectId, environmentId: event.target.value })}
                  >
                    {!environmentsQuery.isSuccess ? <option value="">Loading environments…</option> : null}
                    {environmentsQuery.isSuccess && environmentsQuery.data.length === 0 ? <option value="">No environments</option> : null}
                    {environmentsQuery.data?.map((environment) => (
                      <option key={environment.id} value={environment.id}>
                        {environment.name} · {environment.kind}
                      </option>
                    ))}
                  </select>
                  {environmentsQuery.isSuccess && environmentId !== null ? (
                    <small>{getEnvironmentKindLabel(environmentsQuery.data.find((environment) => environment.id === environmentId)?.kind ?? '')}</small>
                  ) : null}
                </>
              )}
            </div>
          </div>
          {environmentsQuery.isSuccess && environmentsQuery.data.length === 0 ? (
            <p className="context-empty-inline">No environments configured for this project</p>
          ) : null}
          <div className="context-actions">
            <button className="primary-button" type="button" disabled={environmentId === null} onClick={openInfrastructure}>
              Open infrastructure
            </button>
            {isOwner ? <Link to={`/organizations/${encodeURIComponent(organizationId)}/projects/${encodeURIComponent(projectId)}/environments/new`}>Add environment</Link> : null}
          </div>
        </>
      ) : null}
    </section>
  )
}
