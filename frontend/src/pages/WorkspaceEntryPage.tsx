import { Navigate } from 'react-router-dom'
import { useMyOrganizations } from '../api/auth'
import { useRememberedWorkspace } from '../components/layout/useRememberedWorkspace'
import { modulePath } from '../components/layout/workspaceNavigation'
import { OrganizationsPage } from './OrganizationsPage'
import { useI18n } from '../i18n'

/** Only the application entry restores a preference; explicit links keep their destination. */
export function WorkspaceEntryPage() {
  const memberships = useMyOrganizations()
  const workspace = useRememberedWorkspace()
  const { t } = useI18n()
  if (memberships.isPending) return <div className="auth-loading" role="status">{t.organizations.loading}</div>
  const destination = workspace ? modulePath('overview', workspace) : undefined
  return destination ? <Navigate to={destination} replace /> : <OrganizationsPage />
}
