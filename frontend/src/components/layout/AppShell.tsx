import type { ReactNode } from 'react'
import { LogOut } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useLogout, useMe } from '../../api/auth'
import { ContextBar } from './ContextBar'
import { activeWorkspaceModule, contextSearch, workspaceModulePaths, type WorkspaceModule } from './workspaceNavigation'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'

interface AppShellProps { children: ReactNode }

const modules: { id: WorkspaceModule; label: string }[] = [
  { id: 'workspace', label: 'Workspace' },
  { id: 'infrastructure', label: 'Infrastructure' },
  { id: 'incidents', label: 'Incidents' },
  { id: 'connections', label: 'Connections' },
]

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const logout = useLogout()
  const me = useMe()
  const { organizationId, projectId, environmentId } = useWorkspaceRouteContext()
  const paths = workspaceModulePaths(organizationId, environmentId)
  const active = activeWorkspaceModule(location.pathname)
  const contextParams = new URLSearchParams()
  if (projectId) contextParams.set('project', projectId)
  if (environmentId) contextParams.set('environment', environmentId)
  const context = contextSearch(contextParams)

  return <div className="app-shell">
    <header className="module-bar">
      <Link className="brand" to="/organizations" aria-label="InfraDesk home">InfraDesk</Link>
      <nav className="primary-nav" aria-label="Primary navigation">
        {modules.map(module => paths[module.id] ?
          <Link key={module.id} className={`nav-item ${active === module.id ? 'nav-item-active' : ''}`}
            aria-current={active === module.id ? 'page' : undefined} to={`${paths[module.id]}${context}`}>{module.label}</Link> :
          <span key={module.id} className="nav-item nav-item-disabled" aria-disabled="true">{module.label}</span>)}
      </nav>
      <div className="account-control"><span className="account-name">{me.data?.displayName ?? 'Account'}</span>
        <button type="button" className="signout-button" title="Sign out" disabled={logout.isPending} onClick={() =>
          logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          <LogOut aria-hidden size={14} /> Sign out
        </button></div>
    </header>
    <ContextBar />
    {logout.isError ? <p className="shell-error" role="alert">Unable to sign out</p> : null}
    <main className="content" id="main-content">{children}</main>
  </div>
}
