import type { ReactNode } from 'react'
import { Boxes, Cable, ShieldAlert } from 'lucide-react'

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden>⌁</span>
          <span>InfraDesk</span>
        </div>
        <nav className="primary-nav" aria-label="Primary navigation">
          <span className="nav-item nav-item-active"><Boxes aria-hidden size={17} />Infrastructure</span>
          <span className="nav-item nav-item-disabled"><ShieldAlert aria-hidden size={17} />Incidents</span>
          <span className="nav-item nav-item-disabled"><Cable aria-hidden size={17} />Connections</span>
        </nav>
      </aside>
      <main className="content">{children}</main>
    </div>
  )
}
