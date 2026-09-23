import { AppShell } from '../components/layout/AppShell'
import { Link } from 'react-router-dom'
import { EmptyWorkspaceState, WorkspaceHeader } from '../components/layout/WorkspacePrimitives'

export function InvalidRoutePage() {
  return (
    <AppShell>
      <div className="workspace-page"><WorkspaceHeader title="Page unavailable" />
        <EmptyWorkspaceState title="This route or workspace context is unavailable"
          action={<Link to="/organizations">Choose an organization</Link>} />
      </div>
    </AppShell>
  )
}
