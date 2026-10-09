import { useLocation, useNavigate } from 'react-router-dom'
import { useEnvironments, useProjects } from '../../api/navigation'
import { useI18n } from '../../i18n'
import { getDisplayName, getEnvironmentKindLabel } from '../navigation/navigationPresentation'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'
import { activeWorkspaceModule, modulePath } from './workspaceNavigation'

/** Page filters use the existing URL scope; organization selection lives on its own page. */
export function WorkspaceScopeFilters() {
  const i18n = useI18n(); const t = i18n.t.context
  const scope = useWorkspaceRouteContext()
  const location = useLocation(); const navigate = useNavigate()
  const projects = useProjects(scope.organizationId ?? '')
  const environments = useEnvironments(scope.organizationId ?? '', scope.projectId ?? null)
  const module = activeWorkspaceModule(location.pathname)
  if (!scope.organizationId || !module || module === 'workspace' || module === 'members') return null
  // Scope filters belong to lists and dashboards, not an object's editor or a running operation.
  if (modulePath(module, scope)?.split('?')[0] !== location.pathname) return null
  function go(projectId?: string, environmentId?: string) {
    const target = modulePath(module!, { organizationId: scope.organizationId, projectId, environmentId })
    if (target) navigate(target)
  }
  return <div className="workspace-scope-filters" role="group" aria-label={t.change}>
    <label>{t.project}<select value={scope.projectId ?? ''} disabled={projects.isPending || projects.isError}
      onChange={event => go(event.target.value || undefined)}>
      <option value="">{t.allProjects}</option>
      {projects.data?.map(item => <option key={item.id} value={item.id}>{getDisplayName(item, t.project)}</option>)}
    </select></label>
    <label>{t.environment}<select value={scope.environmentId ?? ''} disabled={!scope.projectId || environments.isPending || environments.isError}
      onChange={event => go(scope.projectId ?? undefined, event.target.value || undefined)}>
      <option value="">{scope.projectId ? t.allEnvironments : t.selectProject}</option>
      {environments.data?.map(item => <option key={item.id} value={item.id}>{getDisplayName(item, t.environment)} · {getEnvironmentKindLabel(item.kind, i18n)}</option>)}
    </select></label>
  </div>
}
