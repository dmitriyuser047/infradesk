import { Link, useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useMyOrganizations } from '../api/auth'
import { useOrganization, useProjects } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { WorkspaceHeader } from '../components/layout/WorkspacePrimitives'
import { ContextSelector } from '../components/navigation/ContextSelector'
import { InvalidRoutePage } from './InvalidRoutePage'

export function OrganizationPage() {
  const { organizationId } = useParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  return <OrganizationContent key={organizationId} organizationId={organizationId} />
}

function OrganizationContent({ organizationId }: { organizationId: string }) {
  const organizationQuery = useOrganization(organizationId)
  const projectsQuery = useProjects(organizationId)
  const memberships = useMyOrganizations()
  const isOwner = memberships.data?.some(value => value.id === organizationId && value.role === 'OWNER') ?? false

  return (
    <AppShell>
      <div className="workspace-page">
        {organizationQuery.isPending ? (
          <div className="organization-skeleton" aria-label="Loading organization">
            <span /><span />
          </div>
        ) : null}
        {organizationQuery.isError ? (
          <section className="inline-error" role="alert">
            {organizationQuery.error instanceof ApiError && organizationQuery.error.code === 'ORGANIZATION_NOT_FOUND' ? (
              <h1>Organization not found</h1>
            ) : (
              <>
                <h1>Unable to load organization</h1>
                <button className="retry-button" type="button" onClick={() => organizationQuery.refetch()}>Retry</button>
              </>
            )}
          </section>
        ) : null}
        {organizationQuery.isSuccess && organizationQuery.data !== undefined ? (
          <>
            <WorkspaceHeader title={organizationQuery.data.name} subtitle={`Organization · ${organizationQuery.data.code}`}
              actions={isOwner ? <Link className="primary-button" to={`/organizations/${encodeURIComponent(organizationId)}/projects/new`}>+ New project</Link> : null} />
            <ContextSelector organizationId={organizationId} projectsQuery={projectsQuery} isOwner={isOwner} />
          </>
        ) : null}
      </div>
    </AppShell>
  )
}
