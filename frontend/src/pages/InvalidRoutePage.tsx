import { AppShell } from '../components/layout/AppShell'
import { Link } from 'react-router-dom'
import { EmptyWorkspaceState, WorkspaceHeader } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'

export function InvalidRoutePage() {
  const { t } = useI18n()
  return (
    <AppShell>
      <div className="workspace-page"><WorkspaceHeader title={t.invalidRoute.title} />
        <EmptyWorkspaceState title={t.invalidRoute.detail}
          action={<Link to="/organizations">{t.invalidRoute.action}</Link>} />
      </div>
    </AppShell>
  )
}
