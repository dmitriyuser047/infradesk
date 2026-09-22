import type { ReactNode } from 'react'
import { Boxes, Cable, House, LogOut, ShieldAlert } from 'lucide-react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'

import { useLogout } from '../../api/auth'

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const logout = useLogout()
  const { organizationId, environmentId } = useParams()
  const incidentsActive = location.pathname.includes('/incidents')
  const connectionsActive = location.pathname.includes('/connections')
  const workspacePath = organizationId !== undefined
    ? `/organizations/${organizationId}`
    : undefined
  const workspaceActive = location.pathname === workspacePath
  const infrastructureActive = location.pathname.includes('/environments/') && !incidentsActive && !connectionsActive
  const infrastructurePath = organizationId !== undefined && environmentId !== undefined
    ? `/organizations/${organizationId}/environments/${environmentId}`
    : undefined
  const incidentsPath = organizationId !== undefined
    ? `/organizations/${organizationId}/incidents`
    : undefined
  const connectionsPath = organizationId !== undefined
    ? `/organizations/${organizationId}/connections`
    : undefined

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden>⌁</span>
          <span>InfraDesk</span>
        </div>
        <nav className="primary-nav" aria-label="Primary navigation">
          <Link className="nav-item" to="/organizations"><House aria-hidden size={17} />Organizations</Link>
          {workspacePath !== undefined ? (
            <Link className={`nav-item ${workspaceActive ? 'nav-item-active' : ''}`} to={workspacePath}>
              <House aria-hidden size={17} />Workspace
            </Link>
          ) : null}
          {infrastructurePath !== undefined ? (
            <Link className={`nav-item ${infrastructureActive ? 'nav-item-active' : ''}`} to={infrastructurePath}>
              <Boxes aria-hidden size={17} />Infrastructure
            </Link>
          ) : (
            <span className="nav-item nav-item-disabled"><Boxes aria-hidden size={17} />Infrastructure</span>
          )}
          {incidentsPath !== undefined ? (
            <Link className={`nav-item ${incidentsActive ? 'nav-item-active' : ''}`} to={incidentsPath}>
              <ShieldAlert aria-hidden size={17} />Incidents
            </Link>
          ) : (
            <span className="nav-item nav-item-disabled"><ShieldAlert aria-hidden size={17} />Incidents</span>
          )}
          {connectionsPath !== undefined ? (
            <Link className={`nav-item ${connectionsActive ? 'nav-item-active' : ''}`} to={connectionsPath}>
              <Cable aria-hidden size={17} />Connections
            </Link>
          ) : (
            <span className="nav-item nav-item-disabled"><Cable aria-hidden size={17} />Connections</span>
          )}
        </nav>
        <button className="nav-item logout-button" type="button" disabled={logout.isPending} onClick={() => {
          logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })
        }}><LogOut aria-hidden size={17} />Sign out</button>
        {logout.isError ? <p className="logout-error" role="alert">Unable to sign out</p> : null}
      </aside>
      <main className="content">{children}</main>
    </div>
  )
}
