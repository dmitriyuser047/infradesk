import type { ReactNode } from 'react'
import { Boxes, Cable, House, ShieldAlert } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
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
      </aside>
      <main className="content">{children}</main>
    </div>
  )
}
