import { Link, useParams, useSearchParams } from 'react-router-dom'

import { ApiError } from '../api/httpClient'
import { useEnvironments, useProjects } from '../api/navigation'
import { useOperationsOverview } from '../api/overview'
import { ActivityTimeline } from '../components/history/ActivityTimeline'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState,
  StatusIndicator,
  WorkspaceHeader,
  WorkspaceSection,
} from '../components/layout/WorkspacePrimitives'
import {
  getAttentionPresentation,
  getSummaryCards,
  isEmptyInfrastructure,
} from '../components/overview/overviewPresentation'
import type { OperationsOverviewResponse } from '../types/overview'
import { InvalidRoutePage } from './InvalidRoutePage'

export function OverviewPage() {
  const { organizationId } = useParams()
  const [searchParams] = useSearchParams()

  if (organizationId === undefined) {
    return <InvalidRoutePage />
  }

  const projectId = searchParams.get('project')
  // An environment is only selected inside its project, as everywhere else in the workspace.
  const environmentId = projectId ? searchParams.get('environment') : null

  return <AppShell>
    <OverviewContent key={organizationId} organizationId={organizationId}
      projectId={projectId} environmentId={environmentId} />
  </AppShell>
}

function OverviewContent({ organizationId, projectId, environmentId }: {
  organizationId: string
  projectId: string | null
  environmentId: string | null
}) {
  const overview = useOperationsOverview(organizationId, { projectId, environmentId })
  const projects = useProjects(organizationId)
  const environments = useEnvironments(organizationId, projectId)
  const project = projects.data?.find(item => item.id === projectId)
  const environment = environments.data?.find(item => item.id === environmentId)
  const subtitle = environmentId ? `Environment · ${environment?.name ?? '…'}`
    : projectId ? `Project · ${project?.name ?? '…'}` : 'Organization · all projects'

  return <div className="workspace-page">
    <WorkspaceHeader title="Overview" subtitle={subtitle} />
    <OverviewBody organizationId={organizationId} environmentId={environmentId} projectId={projectId}
      state={overview} />
  </div>
}

export interface OverviewState {
  isPending: boolean
  isError: boolean
  error: unknown
  data: OperationsOverviewResponse | undefined
  refetch: () => unknown
}

/** The three sections for one state of the overview query: loading, failed or loaded. */
export function OverviewBody({ organizationId, projectId, environmentId, state }: {
  organizationId: string
  projectId: string | null
  environmentId: string | null
  state: OverviewState
}) {
  if (state.isPending) {
    return <div className="row-skeleton" aria-label="Loading overview"><span /><span /><span /></div>
  }

  if (state.isError || state.data === undefined) {
    const code = state.error instanceof ApiError ? state.error.code : null
    return <section className="inline-error" role="alert">
      {code === 'PROJECT_NOT_FOUND' ? 'Project not found'
        : code === 'ENVIRONMENT_NOT_FOUND' ? 'Environment not found' : 'Unable to load overview'}
      <button className="text-button" type="button" onClick={() => state.refetch()}>Retry</button>
    </section>
  }

  const overview = state.data
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const context = projectId ? `?project=${encodeURIComponent(projectId)}` +
    (environmentId ? `&environment=${encodeURIComponent(environmentId)}` : '') : ''
  const cards = getSummaryCards(overview.summary, {
    infrastructure: environmentId ? `${base}/environments/${encodeURIComponent(environmentId)}${context}` : null,
    incidents: `${base}/incidents${context}`,
    connections: `${base}/connections${context}`,
  }, overview.operationsHorizonHours)

  return <>
    <WorkspaceSection title="Fleet summary">
      {isEmptyInfrastructure(overview.summary) ? <EmptyWorkspaceState title="No infrastructure discovered yet"
        detail="Add an SSH connection and synchronize it to discover nodes and containers."
        action={<Link className="secondary-button" to={`${base}/connections${context}`}>Open connections</Link>} />
        : null}
      <div className="metric-strip overview-summary" aria-label="Fleet summary">
        {cards.map(card => {
          const content = <>
            <span>{card.label}</span>
            <strong className={card.tone === 'neutral' ? undefined : `status-${card.tone}`}>{card.value}</strong>
            <small>{card.detail}</small>
          </>
          return card.to === null
            ? <div key={card.id} data-card={card.id}>{content}</div>
            : <Link key={card.id} data-card={card.id} to={card.to}>{content}</Link>
        })}
      </div>
    </WorkspaceSection>

    <WorkspaceSection title="Needs attention" actions={overview.attention.total > overview.attention.items.length
      ? <span className="muted-cell">Showing {overview.attention.items.length} of {overview.attention.total}</span> : null}>
      {overview.attention.items.length === 0 ? <EmptyWorkspaceState title="No items need attention" /> :
        <ol className="activity-timeline" aria-label="Needs attention">
          {overview.attention.items.map(item => {
            const presentation = getAttentionPresentation(organizationId, item)
            return <li key={`${item.kind}-${item.id}`} className="activity-entry" data-kind={item.kind}>
              <span className="activity-time">{new Date(item.occurredAt).toLocaleString()}</span>
              <span className="activity-title">{presentation.title}</span>
              <span className="activity-detail">
                {presentation.to !== null
                  ? <Link className="grid-link" to={presentation.to}>{presentation.subject}</Link>
                  : presentation.subject}
                {presentation.detail !== null ? ` · ${presentation.detail}` : null}
              </span>
              <StatusIndicator label={presentation.status} tone={presentation.tone} />
            </li>
          })}
        </ol>}
    </WorkspaceSection>

    <WorkspaceSection title="Recent activity">
      {overview.recentActivity.length === 0 ? <EmptyWorkspaceState title="No recent activity" />
        : <ActivityTimeline events={overview.recentActivity} organizationId={organizationId} />}
    </WorkspaceSection>
  </>
}
