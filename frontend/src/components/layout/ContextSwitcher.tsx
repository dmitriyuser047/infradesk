import { ChevronDown, Layers } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useMyOrganizations } from '../../api/auth'
import { useEnvironmentContext, useEnvironments, useProjects } from '../../api/navigation'
import { useI18n } from '../../i18n'
import { canOrganization } from '../auth/authorization'
import { getDisplayName, getEnvironmentKindLabel } from '../navigation/navigationPresentation'
import { usePopover } from './usePopover'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'
import { activeWorkspaceModule, modulePath, type WorkspaceScope } from './workspaceNavigation'

/**
 * The one control for "where am I": organization → project → environment.
 *
 * It shows the current context as a single line and edits it in a popover. Every change is a
 * navigation, so the URL stays the source of truth and back/forward keep working. Changing the
 * organization clears project and environment; changing the project clears the environment.
 */
export function ContextSwitcher() {
  const i18n = useI18n()
  const t = i18n.t.context
  const navigate = useNavigate()
  const location = useLocation()
  const popover = usePopover()
  const memberships = useMyOrganizations()
  const { organizationId, projectId, environmentId } = useWorkspaceRouteContext()
  const projects = useProjects(organizationId ?? '')
  // A deep link to an environment may not carry its project; one lookup resolves it.
  const environmentContext = useEnvironmentContext(organizationId ?? '', environmentId ?? null,
    Boolean(organizationId && environmentId && !projectId))
  const resolvedProjectId = projectId ?? environmentContext.data?.project.id ?? null
  const environments = useEnvironments(organizationId ?? '', resolvedProjectId)

  const organizations = memberships.data ?? []
  const organization = organizations.find(item => item.id === organizationId)
  const project = projects.data?.find(item => item.id === resolvedProjectId)
  const environment = environments.data?.find(item => item.id === environmentId)
    ?? environmentContext.data?.environment
  const module = activeWorkspaceModule(location.pathname)
  const canManageWorkspace = canOrganization(organization?.role, 'manageWorkspace')

  function go(scope: WorkspaceScope) {
    const target = modulePath(module ?? 'overview', scope)
    if (target) navigate(target)
  }

  const parts = organizationId === undefined ? [] : [
    getDisplayName(organization, t.currentOrganization),
    resolvedProjectId ? getDisplayName(project, '…') : t.allProjects,
    ...(resolvedProjectId ? [environmentId ? getDisplayName(environment, '…') : t.allEnvironments] : []),
  ]
  const summary = parts.join(' / ')

  return <div className="context-switcher">
    <button ref={popover.triggerRef} type="button" className="context-trigger" onClick={popover.toggle}
      aria-haspopup="dialog" {...popover.triggerProps}
      aria-label={organizationId === undefined ? t.organization : t.current(summary)}>
      <Layers aria-hidden size={16} className="context-trigger-icon" />
      {organizationId === undefined ? <span className="context-trigger-text">{i18n.t.shell.organizations}</span> :
        <span className="context-trigger-text">
          <span className="context-trigger-organization">{parts[0]}</span>
          {parts.slice(1).map((part, index) => <span key={index} className="context-trigger-part">
            <span className="context-separator" aria-hidden>/</span>{part}</span>)}
        </span>}
      <ChevronDown aria-hidden size={16} className="context-trigger-chevron" />
    </button>
    {popover.open ? <div ref={popover.panelRef} id={popover.panelId} className="context-panel" role="dialog"
      aria-label={t.change}>
      <label className="field">
        <span className="field-label">{t.organization}</span>
        <select autoFocus value={organizationId ?? ''} onChange={event => {
          if (!event.target.value) return
          navigate(modulePath(module ?? 'overview', { organizationId: event.target.value })!)
        }}>
          {organizationId === undefined ? <option value="">{t.selectOrganizationHint}</option> : null}
          {organizationId !== undefined && !organization ? <option value={organizationId}>{t.currentOrganization}</option> : null}
          {organizations.map(item => <option key={item.id} value={item.id}>{getDisplayName(item, t.currentOrganization)}</option>)}
        </select>
      </label>
      <Link className="text-link" to="/organizations" onClick={() => popover.close(false)}>
        {i18n.t.shell.organizations}</Link>
      {organizationId !== undefined ? <>
        <label className="field">
          <span className="field-label">{t.project}</span>
          <select value={resolvedProjectId ?? ''} onChange={event => go({ organizationId, projectId: event.target.value || null })}>
            <option value="">{t.allProjects}</option>
            {projects.data?.map(item => <option key={item.id} value={item.id}>{getDisplayName(item, t.project)}</option>)}
          </select>
        </label>
        <label className="field">
          <span className="field-label">{t.environment}</span>
          <select value={environmentId ?? ''} disabled={!resolvedProjectId}
            onChange={event => go({ organizationId, projectId: resolvedProjectId, environmentId: event.target.value || null })}>
            <option value="">{resolvedProjectId ? t.allEnvironments : t.selectProject}</option>
            {environmentId && !environments.data?.some(item => item.id === environmentId)
              ? <option value={environmentId}>{getDisplayName(environment, '…')}</option> : null}
            {environments.data?.map(item => <option key={item.id} value={item.id}>
              {getDisplayName(item, t.environment)} · {getEnvironmentKindLabel(item.kind, i18n)}</option>)}
          </select>
        </label>
        {canManageWorkspace && projects.isSuccess && projects.data.length === 0 ?
          <Link className="text-link" to={`/organizations/${encodeURIComponent(organizationId)}/projects/new`}
            onClick={() => popover.close(false)}>{t.createProject}</Link> : null}
        {canManageWorkspace && resolvedProjectId && environments.isSuccess && environments.data.length === 0 ?
          <Link className="text-link" to={`/organizations/${encodeURIComponent(organizationId)}/projects/${encodeURIComponent(resolvedProjectId)}/environments/new`}
            onClick={() => popover.close(false)}>{t.addEnvironment}</Link> : null}
      </> : null}
      <div className="context-panel-actions">
        <button type="button" className="secondary-button" onClick={() => popover.close()}>{t.apply}</button>
      </div>
    </div> : null}
  </div>
}
