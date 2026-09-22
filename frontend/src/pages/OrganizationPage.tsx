import { useParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useOrganization, useProjects } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
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

  return (
    <AppShell>
      <div className="organization-page">
        {organizationQuery.isPending ? (
          <div className="organization-skeleton" aria-label="Loading organization">
            <span /><span />
          </div>
        ) : null}
        {organizationQuery.isError ? (
          <section className="content-panel organization-state" role="alert">
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
        {organizationQuery.data !== undefined ? (
          <>
            <header className="page-header organization-header">
              <div>
                <p className="eyebrow">{organizationQuery.data.code}</p>
                <h1>{organizationQuery.data.name}</h1>
                <p className="page-subtitle">Infrastructure workspace</p>
              </div>
            </header>
            <ContextSelector organizationId={organizationId} projectsQuery={projectsQuery} />
          </>
        ) : null}
      </div>
    </AppShell>
  )
}
