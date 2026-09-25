import { useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useOrganization, useProjects } from '../api/navigation'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader } from '../components/layout/WorkspacePrimitives'
import { ContextSelector } from '../components/navigation/ContextSelector'
import { useI18n } from '../i18n'
import { InvalidRoutePage } from './InvalidRoutePage'

export function OrganizationPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <OrganizationContent key={organizationId} organizationId={organizationId} />
}

function OrganizationContent({ organizationId }: { organizationId: string }) {
  const { t } = useI18n()
  const organizationQuery = useOrganization(organizationId)
  const projectsQuery = useProjects(organizationId)
  const permissions = useOrganizationPermissions(organizationId)
  const isOwner = permissions.can('manageWorkspace')

  return (
    <AppShell>
      <div className="workspace-page">
        {organizationQuery.isPending ? (
          <div className="organization-skeleton" aria-label={t.workspace.loadingOrganization}>
            <span /><span />
          </div>
        ) : null}
        {organizationQuery.isError ? (
          <section className="inline-error" role="alert">
            {organizationQuery.error instanceof ApiError && organizationQuery.error.code === 'ORGANIZATION_NOT_FOUND' ? (
              <h1>{t.workspace.organizationNotFound}</h1>
            ) : (
              <>
                <h1>{t.workspace.unableToLoadOrganization}</h1>
                <button className="retry-button" type="button" onClick={() => organizationQuery.refetch()}>{t.common.retry}</button>
              </>
            )}
          </section>
        ) : null}
        {organizationQuery.isSuccess && organizationQuery.data !== undefined ? (
          <>
            <WorkspaceHeader title={t.workspace.title} subtitle={t.workspace.subtitle} />
            <ContextSelector organizationId={organizationId} projectsQuery={projectsQuery} isOwner={isOwner} />
          </>
        ) : null}
      </div>
    </AppShell>
  )
}
