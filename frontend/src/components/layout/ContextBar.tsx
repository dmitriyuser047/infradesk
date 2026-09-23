import { Link, useNavigate } from 'react-router-dom'
import { useQueries } from '@tanstack/react-query'

import { useMyOrganizations } from '../../api/auth'
import { getEnvironments, useEnvironments, useProjects } from '../../api/navigation'
import { getEnvironmentKindLabel } from '../navigation/navigationPresentation'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'

export function ContextBar() {
  const { organizationId, projectId, environmentId } = useWorkspaceRouteContext()
  const navigate = useNavigate()
  const memberships = useMyOrganizations()

  if (!organizationId) {
    return <div className="context-bar"><span className="context-caption">Workspace</span>
      <span className="context-hint">Select an organization to begin.</span></div>
  }

  return <OrganizationContext key={organizationId} organizationId={organizationId}
    projectId={projectId}
    environmentId={environmentId}
    organizations={memberships.data ?? []} navigate={navigate} />
}

function OrganizationContext({ organizationId, projectId, environmentId, organizations, navigate }: {
  organizationId: string
  projectId?: string
  environmentId?: string
  organizations: NonNullable<ReturnType<typeof useMyOrganizations>['data']>
  navigate: ReturnType<typeof useNavigate>
}) {
  const projects = useProjects(organizationId)
  const candidates = useQueries({ queries: (projects.data ?? []).map(project => ({
    queryKey: ['environments', organizationId, project.id],
    queryFn: () => getEnvironments(organizationId, project.id),
    enabled: Boolean(environmentId && !projectId),
  })) })
  const resolvedProjectId = projectId ?? projects.data?.find((_, index) =>
    candidates[index]?.data?.some(environment => environment.id === environmentId))?.id
  const environments = useEnvironments(organizationId, resolvedProjectId ?? null)
  const base = `/organizations/${encodeURIComponent(organizationId)}`

  return <div className="context-bar" aria-label="Workspace context">
    <label>Organization<select value={organizationId} onChange={event => navigate(`/organizations/${encodeURIComponent(event.target.value)}`)}>
      {!organizations.some(item => item.id === organizationId) ? <option value={organizationId}>Current organization</option> : null}
      {organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <label>Project<select value={resolvedProjectId ?? ''} onChange={event => navigate(event.target.value
      ? `${base}?project=${encodeURIComponent(event.target.value)}` : base)}>
      <option value="">Select project</option>
      {projects.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <label>Environment<select value={environmentId ?? ''} disabled={!resolvedProjectId}
      onChange={event => navigate(event.target.value
        ? `${base}/environments/${encodeURIComponent(event.target.value)}?project=${encodeURIComponent(resolvedProjectId ?? '')}`
        : `${base}?project=${encodeURIComponent(resolvedProjectId ?? '')}`)}>
      <option value="">Select environment</option>
      {environmentId && !environments.data?.some(item => item.id === environmentId) ?
        <option value={environmentId}>{environmentId.slice(0, 8)}</option> : null}
      {environments.data?.map(item => <option key={item.id} value={item.id}>
        {item.name} · {getEnvironmentKindLabel(item.kind)}</option>)}
    </select></label>
    {projects.isSuccess && projects.data.length === 0 && organizations.some(item => item.id === organizationId && item.role === 'OWNER')
      ? <Link className="context-create" to={`${base}/projects/new`}>Create project</Link> : null}
    {resolvedProjectId && environments.isSuccess && environments.data.length === 0 &&
      organizations.some(item => item.id === organizationId && item.role === 'OWNER')
      ? <Link className="context-create" to={`${base}/projects/${encodeURIComponent(resolvedProjectId)}/environments/new`}>Add environment</Link> : null}
  </div>
}
