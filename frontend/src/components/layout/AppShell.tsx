import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { Bell, Cable, FileCog, LayoutDashboard, Layers, Menu, Plug, Server, TriangleAlert, X, type LucideIcon } from 'lucide-react'
import { Link, Outlet, useLocation } from 'react-router-dom'

import { useI18n, type Messages } from '../../i18n'
import { useOrganizationPermissions, type OrganizationPermission } from '../auth/authorization'
import { AccountMenu } from './AccountMenu'
import { WorkspaceDock } from '../workspace/WorkspaceDock'
import { useTerminalSessions } from '../workspace/TerminalWorkspaceProvider'
import { ContextSwitcher } from './ContextSwitcher'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'
import { activeWorkspaceModule, modulePath, type WorkspaceModule } from './workspaceNavigation'

interface NavigationItem {
  module: WorkspaceModule
  icon: LucideIcon
  label: (t: Messages) => string
  permission?: OrganizationPermission
}

interface NavigationGroup {
  label: ((t: Messages) => string) | null
  items: NavigationItem[]
}

/**
 * The sections, grouped by what the user is looking for rather than by subsystem. Restricted
 * modules carry the same capability their page and backend route require.
 */
const navigation: NavigationGroup[] = [
  { label: null, items: [{ module: 'overview', icon: LayoutDashboard, label: t => t.shell.nav.overview }] },
  {
    label: t => t.shell.groups.infrastructure,
    items: [
      { module: 'resources', icon: Server, label: t => t.shell.nav.resources },
      { module: 'connections', icon: Cable, label: t => t.shell.nav.connections },
    ],
  },
  { label: t => t.shell.groups.monitoring, items: [{ module: 'incidents', icon: TriangleAlert, label: t => t.shell.nav.incidents }] },
  { label: t => t.shell.groups.management, items: [
    { module: 'notifications', icon: Bell, label: t => t.shell.nav.notifications, permission: 'manageNotifications' },
    { module: 'integrations', icon: Plug, label: t => t.shell.nav.integrations, permission: 'manageIntegrations' },
    { module: 'configurations', icon: FileCog, label: t => t.shell.nav.configurations, permission: 'manageConfigurations' },
    { module: 'workspace', icon: Layers, label: t => t.shell.nav.workspace },
  ] },
]

// Set by the layout route: a page rendered inside it must not draw a second shell.
const ShellContext = createContext(false)

/** The persistent application frame of the signed-in part of the router. */
export function ShellLayout() {
  return <AppShell><Outlet /></AppShell>
}

/**
 * Sidebar, top bar with the context switcher and the account menu, and the content.
 *
 * Pages still wrap themselves in AppShell so each renders completely on its own (tests, error
 * pages); inside the layout route that wrapper is transparent and the frame is not re-created on
 * navigation.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const nested = useContext(ShellContext)
  return nested ? <>{children}</> : <ShellContext.Provider value={true}><ShellFrame>{children}</ShellFrame></ShellContext.Provider>
}

function ShellFrame({ children }: { children: ReactNode }) {
  const { t } = useI18n()
  const location = useLocation()
  const scope = useWorkspaceRouteContext()
  const permissions = useOrganizationPermissions(scope.organizationId)
  const active = activeWorkspaceModule(location.pathname)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const docked = useTerminalSessions().length > 0
  const toggleRef = useRef<HTMLButtonElement>(null)
  const sidebarRef = useRef<HTMLElement>(null)

  // A navigation always closes the drawer on narrow screens.
  useEffect(() => { setDrawerOpen(false) }, [location.pathname, location.search])

  useEffect(() => {
    if (!drawerOpen) return
    sidebarRef.current?.querySelector<HTMLElement>('a, button')?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawerOpen(false)
        toggleRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen])

  return <div className={`app-shell ${drawerOpen ? 'drawer-open' : ''} ${docked ? 'has-dock' : ''}`}>
    <a className="skip-link" href="#main-content">{t.shell.skipToContent}</a>
    <aside ref={sidebarRef} id="app-sidebar" className="sidebar">
      <div className="sidebar-header">
        <Link className="brand" to="/organizations" aria-label={t.shell.brandHome}>
          <span className="brand-mark" aria-hidden>ID</span><span className="brand-name">InfraDesk</span>
        </Link>
        <button type="button" className="icon-button sidebar-close" aria-label={t.shell.closeNavigation}
          onClick={() => { setDrawerOpen(false); toggleRef.current?.focus() }}><X aria-hidden size={18} /></button>
      </div>
      <nav className="sidebar-nav" aria-label={t.shell.primaryNavigation}>
        {navigation.map((group, index) => <div className="nav-group" key={index}>
          {group.label ? <div className="nav-group-label">{group.label(t)}</div> : null}
          <ul>
            {group.items.filter(item => !item.permission || permissions.can(item.permission)).map(item => {
              const path = modulePath(item.module, scope)
              const Icon = item.icon
              const current = active === item.module
              return <li key={item.module}>{path ?
                <Link className={`nav-link ${current ? 'nav-link-active' : ''}`} to={path}
                  aria-current={current ? 'page' : undefined}>
                  <Icon aria-hidden size={18} /><span>{item.label(t)}</span>
                </Link> :
                <span className="nav-link nav-link-disabled" aria-disabled="true">
                  <Icon aria-hidden size={18} /><span>{item.label(t)}</span>
                </span>}</li>
            })}
          </ul>
        </div>)}
        {scope.organizationId === undefined ? <p className="nav-hint">{t.shell.chooseOrganization}</p> : null}
      </nav>
      <div className="sidebar-footer">
        <Link className="nav-link" to="/organizations">{t.shell.organizations}</Link>
      </div>
    </aside>
    {/* Closing by the backdrop returns focus to the menu button, as Escape and the close button do. */}
    {drawerOpen ? <div className="drawer-backdrop" aria-hidden onClick={() => { setDrawerOpen(false); toggleRef.current?.focus() }} /> : null}
    <div className="app-main">
      <header className="topbar">
        <button ref={toggleRef} type="button" className="icon-button menu-toggle" aria-label={t.shell.openNavigation}
          aria-controls="app-sidebar" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}>
          <Menu aria-hidden size={20} />
        </button>
        <ContextSwitcher />
        <AccountMenu />
      </header>
      <main className="content" id="main-content" tabIndex={-1}>{children}</main>
    </div>
    <WorkspaceDock />
  </div>
}
