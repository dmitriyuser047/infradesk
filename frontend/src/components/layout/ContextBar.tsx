import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useMyOrganizations } from '../../api/auth'
import { useEnvironmentContext, useEnvironments, useProjects } from '../../api/navigation'
import { canOrganization } from '../auth/authorization'
import { getEnvironmentKindLabel } from '../navigation/navigationPresentation'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'
import { activeWorkspaceModule } from './workspaceNavigation'
import { useI18n } from '../../i18n'

export function ContextBar() {
  const { t } = useI18n()
  const { organizationId, projectId, environmentId } = useWorkspaceRouteContext()
  const navigate = useNavigate()
  const location = useLocation()
  const memberships = useMyOrganizations()

  if (!organizationId) {
    return <div className="context-bar"><span className="context-caption">{t.context.label}</span>
      <span className="context-hint">{t.context.selectOrganizationHint}</span></div>
  }

  return <OrganizationContext key={organizationId} organizationId={organizationId}
    projectId={projectId}
    environmentId={environmentId}
    organizations={memberships.data ?? []} navigate={navigate}
    onOverview={activeWorkspaceModule(location.pathname) === 'overview'} />
}

function OrganizationContext({ organizationId, projectId, environmentId, organizations, navigate, onOverview }: {
  organizationId: string
  projectId?: string
  environmentId?: string
  organizations: NonNullable<ReturnType<typeof useMyOrganizations>['data']>
  navigate: ReturnType<typeof useNavigate>
  /** The overview is scoped by the same selection, so changing it keeps the overview open. */
  onOverview: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.context
  const projects = useProjects(organizationId)
  const environmentContext = useEnvironmentContext(
    organizationId,
    environmentId ?? null,
    Boolean(environmentId && !projectId),
  )
  const resolvedProjectId = projectId ?? environmentContext.data?.project.id
  const environments = useEnvironments(organizationId, resolvedProjectId ?? null)
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const scopeBase = onOverview ? `${base}/overview` : base
  const environmentPath = (id: string) => onOverview
    ? `${scopeBase}?project=${encodeURIComponent(resolvedProjectId ?? '')}&environment=${encodeURIComponent(id)}`
    : `${base}/environments/${encodeURIComponent(id)}?project=${encodeURIComponent(resolvedProjectId ?? '')}`

  const canManageWorkspace = canOrganization(
    organizations.find(item => item.id === organizationId)?.role, 'manageWorkspace')

  return <div className="context-bar" aria-label={t.label}>
    <label>{t.organization}<select value={organizationId} onChange={event => navigate(`/organizations/${encodeURIComponent(event.target.value)}${onOverview ? '/overview' : ''}`)}>
      {!organizations.some(item => item.id === organizationId) ? <option value={organizationId}>{t.currentOrganization}</option> : null}
      {organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <label>{t.project}<select value={resolvedProjectId ?? ''} onChange={event => navigate(event.target.value
      ? `${scopeBase}?project=${encodeURIComponent(event.target.value)}` : scopeBase)}>
      <option value="">{t.allProjects}</option>
      {projects.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <label>{t.environment}<select value={environmentId ?? ''} disabled={!resolvedProjectId}
      onChange={event => navigate(event.target.value
        ? environmentPath(event.target.value)
        : `${scopeBase}?project=${encodeURIComponent(resolvedProjectId ?? '')}`)}>
      <option value="">{t.allEnvironments}</option>
      {environmentId && !environments.data?.some(item => item.id === environmentId) ?
        <option value={environmentId}>{environmentId.slice(0, 8)}</option> : null}
      {environments.data?.map(item => <option key={item.id} value={item.id}>
        {item.name} · {getEnvironmentKindLabel(item.kind, i18n)}</option>)}
    </select></label>
    {projects.isSuccess && projects.data.length === 0 && canManageWorkspace
      ? <Link className="context-create" to={`${base}/projects/new`}>{t.createProject}</Link> : null}
    {resolvedProjectId && environments.isSuccess && environments.data.length === 0 && canManageWorkspace
      ? <Link className="context-create" to={`${base}/projects/${encodeURIComponent(resolvedProjectId)}/environments/new`}>{t.addEnvironment}</Link> : null}
  </div>
}
