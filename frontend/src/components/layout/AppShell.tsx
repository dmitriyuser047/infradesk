import { useContext, useEffect, useState, type ReactNode } from 'react'
import { Building2, Bell, Cable, FileCog, LayoutDashboard, Layers, Plug, Server, ShieldCheck, Users, TriangleAlert, type LucideIcon } from 'lucide-react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'

import { useI18n, type Messages } from '../../i18n'
import { useMe } from '../../api/auth'
import { useOrganizationPermissions, type OrganizationPermission } from '../auth/authorization'
import { AccountMenu } from './AccountMenu'
import { ShellContext } from './ShellContext'
import { InfraDeskMark } from './InfraDeskMark'
import { WorkspaceDock } from '../workspace/WorkspaceDock'
import { useTerminalSessions } from '../workspace/TerminalWorkspaceProvider'
import { WorkspaceScopeFilters } from './WorkspaceScopeFilters'
import { useMyOrganizations } from '../../api/auth'
import { useWorkspaceRouteContext } from './useWorkspaceRouteContext'
import { rememberWorkspace, useRememberedWorkspace } from './useRememberedWorkspace'
import { activeWorkspaceModule, modulePath, type WorkspaceModule } from './workspaceNavigation'

interface NavigationItem {
  module: WorkspaceModule
  icon: LucideIcon
  label: (t: Messages) => string
  permission?: OrganizationPermission
}

interface NavigationGroup {
  id: string
  icon: LucideIcon
  label: ((t: Messages) => string) | null
  items: NavigationItem[]
}

/**
 * The sections, grouped by what the user is looking for rather than by subsystem. Restricted
 * modules carry the same capability their page and backend route require.
 */
const navigation: NavigationGroup[] = [
  { id: 'overview', icon: LayoutDashboard, label: t => t.shell.nav.overview, items: [{ module: 'overview', icon: LayoutDashboard, label: t => t.shell.nav.overview }] },
  {
    id: 'infrastructure', icon: Server, label: t => t.shell.groups.infrastructure,
    items: [
      { module: 'resources', icon: Server, label: t => t.shell.nav.resources },
      { module: 'connections', icon: Cable, label: t => t.shell.nav.connections },
    ],
  },
  { id: 'monitoring', icon: TriangleAlert, label: t => t.shell.groups.monitoring, items: [
    { module: 'incidents', icon: TriangleAlert, label: t => t.shell.nav.incidents },
    { module: 'notifications', icon: Bell, label: t => t.shell.nav.notifications, permission: 'manageNotifications' },
  ] },
  { id: 'automation', icon: Plug, label: t => t.shell.groups.automation, items: [
    { module: 'integrations', icon: Plug, label: t => t.shell.nav.integrations, permission: 'manageIntegrations' },
    { module: 'configurations', icon: FileCog, label: t => t.shell.nav.configurations, permission: 'readOrganization' },
  ] },
  { id: 'structure', icon: Layers, label: t => t.shell.groups.structure, items: [
    { module: 'workspace', icon: Layers, label: t => t.shell.nav.workspace },
    { module: 'members', icon: Users, label: t => t.administration.members, permission: 'manageMembers' },
  ] },
]

/** The persistent application frame of the signed-in part of the router. */
export function ShellLayout() {
  return <AppShell showEntrySplash><Outlet /></AppShell>
}

/**
 * Navigation rail, page tabs with the account menu, and the content.
 *
 * Pages still wrap themselves in AppShell so each renders completely on its own (tests, error
 * pages); inside the layout route that wrapper is transparent and the frame is not re-created on
 * navigation.
 */
export function AppShell({ children, showEntrySplash = false }: { children: ReactNode; showEntrySplash?: boolean }) {
  const nested = useContext(ShellContext)
  return nested ? <>{children}</> : <ShellContext.Provider value={true}><ShellFrame showEntrySplash={showEntrySplash}>{children}</ShellFrame></ShellContext.Provider>
}

function ShellFrame({ children, showEntrySplash }: { children: ReactNode; showEntrySplash: boolean }) {
  const { t } = useI18n()
  const location = useLocation()
  const navigate = useNavigate()
  const remembered = useRememberedWorkspace()
  const globalPage = location.pathname.startsWith('/administration') || location.pathname.startsWith('/settings')
  const scope = useWorkspaceRouteContext(globalPage ? remembered : undefined)
  const permissions = useOrganizationPermissions(scope.organizationId)
  const me = useMe()
  const memberships = useMyOrganizations()
  const active = activeWorkspaceModule(location.pathname)
  const availableGroups = (scope.organizationId ? navigation : []).map(group => ({ ...group,
    items: group.items.filter(item => !item.permission || permissions.can(item.permission)),
  })).filter(group => group.items.length > 0)
  const administrator = me.isSuccess && me.data.isAdministrator
  const selectedGroup = location.pathname.startsWith('/administration') && administrator ? 'administration'
    : location.pathname.startsWith('/settings') ? 'account'
    : scope.organizationId ? availableGroups.find(group => group.items.some(item => item.module === active))?.id ?? 'overview'
      : 'organizations'
  const groupTitle = selectedGroup === 'administration' ? t.administration.title
    : selectedGroup === 'account' ? t.shell.accountSettings
    : selectedGroup === 'organizations' ? t.shell.organizations
      : availableGroups.find(group => group.id === selectedGroup)?.label?.(t) ?? t.shell.nav.overview
  const administrationOrganizations = location.pathname === '/administration' && new URLSearchParams(location.search).get('tab') === 'organizations'
  const selectGroup = (group: string) => {
    const firstPage = availableGroups.find(item => item.id === group)?.items[0]
    const destination = group === 'administration' && administrator ? '/administration'
      : firstPage ? modulePath(firstPage.module, scope) : undefined
    if (destination) navigate(destination)
  }
  const docked = useTerminalSessions().length > 0
  const enteringOrganization = globalPage || !showEntrySplash ? undefined : scope.organizationId
  const [readyOrganization, setReadyOrganization] = useState<string | undefined>()
  useEffect(() => {
    setReadyOrganization(undefined)
    if (!enteringOrganization) return
    const timer = window.setTimeout(() => setReadyOrganization(enteringOrganization), 1000)
    return () => window.clearTimeout(timer)
  }, [enteringOrganization])
  const entering = Boolean(enteringOrganization && (readyOrganization !== enteringOrganization || permissions.isPending))
  useEffect(() => {
    if (!globalPage && scope.organizationId && me.isSuccess && permissions.role) rememberWorkspace(me.data.id, scope)
  }, [globalPage, scope.organizationId, scope.projectId, scope.environmentId, me.isSuccess, me.data?.id, permissions.role])

  return <><div className={`app-shell ${docked ? 'has-dock' : ''}`} inert={entering ? true : undefined}>
    <a className="skip-link" href="#main-content">{t.shell.skipToContent}</a>
    <aside id="app-sidebar" className="sidebar">
      <nav className="navigation-rail" aria-label={t.shell.navigationSections}>
        <Link className="rail-organizations" to="/organizations" aria-label={t.shell.organizations} title={t.shell.organizations}>
          <Building2 size={21} aria-hidden />
        </Link>
        <div className="rail-sections">
          {scope.organizationId ? availableGroups.map(group => {
            const Icon = group.icon
            const label = group.label?.(t) ?? t.shell.nav.overview
            return <button key={group.id} type="button" className={`rail-button ${selectedGroup === group.id ? 'rail-button-selected' : ''}`}
              aria-label={label} title={label} aria-pressed={selectedGroup === group.id} aria-controls="navigation-panel"
              onClick={() => selectGroup(group.id)}><Icon size={21} aria-hidden /><span className="rail-tooltip" aria-hidden>{label}</span></button>
          }) : null}
          {administrator ? <button type="button" className={`rail-button ${selectedGroup === 'administration' ? 'rail-button-selected' : ''}`}
            aria-label={t.administration.title} title={t.administration.title} aria-pressed={selectedGroup === 'administration'}
            aria-controls="navigation-panel" onClick={() => selectGroup('administration')}><ShieldCheck size={21} aria-hidden />
            <span className="rail-tooltip" aria-hidden>{t.administration.title}</span></button> : null}
        </div>
        <Link className="rail-brand" to="/" aria-label={t.shell.brandHome} title="InfraDesk"><InfraDeskMark /></Link>
      </nav>
    </aside>
    <div className="app-main">
      <section className="navigation-panel" id="navigation-panel" aria-label={t.shell.primaryNavigation}>
      <div className="navigation-panel-heading"><strong>{groupTitle}</strong></div>
      <div className="navigation-account"><AccountMenu /></div>
      {globalPage ? <Link className="workspace-return" to={modulePath('overview', scope) ?? '/organizations'}>
        <LayoutDashboard size={16} aria-hidden />{t.shell.returnToWorkspace}</Link> : null}
      <nav className="sidebar-nav" aria-label={t.shell.primaryNavigation}>
        {availableGroups.map(group => {
          const items = group.items
          return items.length > 0 ? <div className="nav-group" key={group.id} hidden={group.id !== selectedGroup}>
          <ul>
            {items.map(item => {
              const path = modulePath(item.module, scope)
              const Icon = item.icon
              const current = active === item.module
              return <li key={item.module}>{path ?
                <Link className={`nav-link ${current ? 'nav-link-active' : ''}`} to={path}
                  aria-current={current ? 'page' : undefined}>
                  <span className="nav-icon"><Icon aria-hidden size={18} /></span><span>{item.label(t)}</span>
                </Link> :
                <span className="nav-link nav-link-disabled" aria-disabled="true" title={t.shell.selectOrganizationFirst}>
                  <Icon aria-hidden size={18} /><span>{item.label(t)}</span>
                </span>}</li>
            })}
          </ul>
        </div> : null})}
        {administrator ? <div className="nav-group" hidden={selectedGroup !== 'administration'}>
          <ul><li>
          <Link className={`nav-link ${selectedGroup === 'administration' && !administrationOrganizations ? 'nav-link-active' : ''}`} to="/administration"
            aria-current={selectedGroup === 'administration' && !administrationOrganizations ? 'page' : undefined}>
            <span className="nav-icon"><ShieldCheck size={18} aria-hidden /></span><span>{t.administration.users}</span>
          </Link></li><li><Link className={`nav-link ${administrationOrganizations ? 'nav-link-active' : ''}`} to="/administration?tab=organizations"
            aria-current={administrationOrganizations ? 'page' : undefined}>
            <span className="nav-icon"><Building2 size={18} aria-hidden /></span><span>{t.administration.organizations}</span>
          </Link></li></ul></div> : null}
        <div className="nav-group" hidden={selectedGroup !== 'account'}><ul><li>
          <Link className={`nav-link ${selectedGroup === 'account' ? 'nav-link-active' : ''}`} to="/settings/account" aria-current={selectedGroup === 'account' ? 'page' : undefined}>
            <span>{t.shell.accountSettings}</span></Link></li></ul></div>
      </nav>
      </section>
      <main className="content" id="main-content" tabIndex={-1}><WorkspaceScopeFilters />{children}</main>
    </div>
    <WorkspaceDock />
  </div>{entering ? <div className="workspace-splash" role="status" aria-live="polite">
    <InfraDeskMark /><strong>{memberships.data?.find(item => item.id === enteringOrganization)?.name ?? t.context.currentOrganization}</strong>
    <span>{t.shell.loadingWorkspace}</span><div className="workspace-splash-progress" aria-hidden />
  </div> : null}</>
}
