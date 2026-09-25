import type { ReactNode } from 'react'
import { LogOut } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useLogout, useMe } from '../../api/auth'
import { useI18n, Locales } from '../../i18n'
import { ContextBar } from './ContextBar'
import { activeWorkspaceModule, contextSearch, workspaceModulePaths, type WorkspaceModule } from './workspaceNavigation'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'

interface AppShellProps { children: ReactNode }

const modules: WorkspaceModule[] = ['overview', 'workspace', 'infrastructure', 'incidents', 'connections']

export function AppShell({ children }: AppShellProps) {
  const i18n = useI18n()
  const t = i18n.t.shell
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
      <Link className="brand" to="/organizations" aria-label={t.brandHome}>InfraDesk</Link>
      <nav className="primary-nav" aria-label={t.primaryNavigation}>
        {modules.map(module => paths[module] ?
          <Link key={module} className={`nav-item ${active === module ? 'nav-item-active' : ''}`}
            aria-current={active === module ? 'page' : undefined} to={`${paths[module]}${context}`}>{moduleLabel(module, t)}</Link> :
          <span key={module} className="nav-item nav-item-disabled" aria-disabled="true">{moduleLabel(module, t)}</span>)}
      </nav>
      <div className="account-control">
        {Locales.map(locale => <button key={locale} type="button" className="signout-button" aria-pressed={i18n.locale === locale}
          onClick={() => i18n.setLocale(locale)}>{locale.toUpperCase()}</button>)}
        <span className="account-name">{me.data?.displayName ?? t.account}</span>
        <button type="button" className="signout-button" title={t.signOut} disabled={logout.isPending} onClick={() =>
          logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          <LogOut aria-hidden size={14} /> {t.signOut}
        </button></div>
    </header>
    <ContextBar />
    {logout.isError ? <p className="shell-error" role="alert">{t.signOutFailed}</p> : null}
    <main className="content" id="main-content">{children}</main>
  </div>
}

function moduleLabel(module: WorkspaceModule, t: ReturnType<typeof useI18n>['t']['shell']): string {
  switch (module) {
    case 'overview': return t.nav.overview
    case 'workspace': return t.nav.workspace
    case 'infrastructure': return t.nav.resources
    case 'incidents': return t.nav.incidents
    case 'connections': return t.nav.connections
  }
}
