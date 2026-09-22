import type { ReactNode } from 'react'
import { Boxes, Cable, ShieldAlert } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const { organizationId, environmentId } = useParams()
  const incidentsActive = location.pathname.includes('/incidents')
  const infrastructureActive = !incidentsActive
  const infrastructurePath = organizationId !== undefined && environmentId !== undefined
    ? `/organizations/${organizationId}/environments/${environmentId}`
    : undefined
  const incidentsPath = organizationId !== undefined
    ? `/organizations/${organizationId}/incidents`
    : undefined

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden>⌁</span>
          <span>InfraDesk</span>
        </div>
        <nav className="primary-nav" aria-label="Primary navigation">
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
          <span className="nav-item nav-item-disabled"><Cable aria-hidden size={17} />Connections</span>
        </nav>
      </aside>
      <main className="content">{children}</main>
    </div>
  )
}
