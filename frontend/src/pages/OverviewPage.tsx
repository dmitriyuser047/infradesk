import { useQueries } from '@tanstack/react-query'
import {
  Box, Cable, CheckCircle2, Circle, CircleAlert, CircleHelp, History, Server, TriangleAlert, WifiOff, Wrench,
  type LucideIcon,
} from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { useConnections } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { getEnvironments, useEnvironments, useProjects } from '../api/navigation'
import { useOperationsOverview } from '../api/overview'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { OverviewActivity, OverviewActivityLimit } from '../components/overview/OverviewActivity'
import { OverviewHealth } from '../components/overview/OverviewHealth'
import { getOverviewActivityEntries } from '../components/overview/overviewActivityPresentation'
import { withWorkspaceContext } from '../components/layout/workspaceNavigation'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState,
  InlineAlert,
  StatusIndicator,
  WorkspaceHeader,
  WorkspaceSection,
} from '../components/layout/WorkspacePrimitives'
import { needsOnboarding, onboardingState, type OnboardingStep } from '../components/overview/onboarding'
import {
  getAttentionPresentation,
  getSummaryCards,
  getOverviewHealth,
  type SummaryCard,
} from '../components/overview/overviewPresentation'
import { useI18n } from '../i18n'
import type { AttentionItemResponse, OperationsOverviewResponse } from '../types/overview'
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
  const { t } = useI18n()
  const overview = useOperationsOverview(organizationId, { projectId, environmentId })
  const projects = useProjects(organizationId)
  const environments = useEnvironments(organizationId, projectId)
  const permissions = useOrganizationPermissions(organizationId)
  const project = projects.data?.find(item => item.id === projectId)
  const environment = environments.data?.find(item => item.id === environmentId)
  const subtitle = environmentId ? t.overview.scopeEnvironment(environment?.name ?? '…')
    : projectId ? t.overview.scopeProject(project?.name ?? '…') : t.overview.scopeOrganization

  return <div className="workspace-page">
    <WorkspaceHeader title={t.overview.title} subtitle={subtitle} />
    <OverviewBody organizationId={organizationId} environmentId={environmentId} projectId={projectId}
      state={overview} canAddConnection={permissions.can('manageConnections')} />
  </div>
}

export interface OverviewState {
  isPending: boolean
  isError: boolean
  error: unknown
  data: OperationsOverviewResponse | undefined
  /** When the shown data arrived; a failed refresh keeps it on screen and says how old it is. */
  dataUpdatedAt?: number
  refetch: () => unknown
}

const cardIcons: Record<SummaryCard['id'], LucideIcon> = {
  nodes: Server, containers: Box, incidents: TriangleAlert, connections: Cable, operations: Wrench,
}

/** The overview for one state of its query: loading, failed, a new organization, or loaded. */
export function OverviewBody({ organizationId, projectId, environmentId, state, canAddConnection = false }: {
  organizationId: string
  projectId: string | null
  environmentId: string | null
  state: OverviewState
  canAddConnection?: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.overview

  if (state.isPending) {
    // The same frame the loaded overview fills, so nothing moves when the data arrives.
    return <div className="overview-loading" aria-label={t.loading} aria-busy="true">
      <div className="overview-health overview-health-skeleton" aria-hidden><span /><span /></div>
      <div className="summary-grid" aria-hidden>
        {Object.keys(cardIcons).map(id => <div key={id} className="summary-card summary-card-skeleton"><span /><span /><span /></div>)}
      </div>
      <div className="overview-columns">
        <WorkspaceSection title={t.attention} className="attention-section">
          <div className="row-skeleton" aria-hidden><span /><span /><span /></div>
        </WorkspaceSection>
        <WorkspaceSection title={t.activity} className="activity-section">
          <div className="row-skeleton" aria-hidden><span /><span /><span /><span /></div>
        </WorkspaceSection>
      </div>
    </div>
  }

  const code = state.isError && state.error instanceof ApiError ? state.error.code : null
  // A scope that no longer exists makes the last data wrong, not merely old.
  const scopeGone = code === 'PROJECT_NOT_FOUND' || code === 'ENVIRONMENT_NOT_FOUND'
  if (state.data === undefined || scopeGone) {
    return <InlineAlert tone="danger"
      title={code === 'PROJECT_NOT_FOUND' ? t.projectNotFound : code === 'ENVIRONMENT_NOT_FOUND' ? t.environmentNotFound : t.loadError}
      action={<button className="secondary-button" type="button" onClick={() => state.refetch()}>{i18n.t.common.retry}</button>} />
  }

  const overview = state.data
  // A failed poll after a successful load: keep what is known on screen and say how old it is.
  const stale = state.isError ? <InlineAlert tone="warning" title={t.refreshError}
    action={<button className="secondary-button" type="button" onClick={() => state.refetch()}>{i18n.t.common.retry}</button>}>
    {state.dataUpdatedAt ? t.staleSince(i18n.format.dateTime(new Date(state.dataUpdatedAt).toISOString())) : null}
  </InlineAlert> : null
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const context = projectId ? `?project=${encodeURIComponent(projectId)}` +
    (environmentId ? `&environment=${encodeURIComponent(environmentId)}` : '') : ''
  const resources = overview.summary.nodes.total + overview.summary.containers.total
  const onboarding = needsOnboarding(projectId === null, { resources })
  const health = getOverviewHealth(overview, i18n)
  const activityCount = getOverviewActivityEntries(overview.recentActivity).length
  const hasAttention = overview.attention.items.length > 0
  const healthBlock = <OverviewHealth health={health} updatedAt={state.dataUpdatedAt} stale={state.isError}
    hasAttention={hasAttention} connectionPath={canAddConnection ? `${base}/connections/new${context}` : undefined} />

  if (onboarding) {
    return <>
      {stale}
      {healthBlock}
      {overview.attention.items.length > 0 ? <AttentionSection organizationId={organizationId} overview={overview} context={context} /> : null}
      <OnboardingGuide organizationId={organizationId} overview={overview} />
    </>
  }

  const cards = getSummaryCards(overview.summary, {
    infrastructure: environmentId ? `${base}/environments/${encodeURIComponent(environmentId)}${context}` : `${base}/resources${context}`,
    incidents: `${base}/incidents${context}`,
    connections: `${base}/connections${context}`,
  }, overview.operationsHorizonHours, i18n)

  return <>
    {stale}
    {healthBlock}
    <section aria-label={t.summary} className="summary-grid">
      {cards.map(card => <SummaryCardView key={card.id} card={card} />)}
    </section>
    <div className="overview-columns">
      {hasAttention ? <AttentionSection organizationId={organizationId} overview={overview} context={context} /> : null}
      <WorkspaceSection title={t.activity} className="activity-section"
        actions={activityCount > OverviewActivityLimit ? <span className="section-meta">
          {t.activityShown(OverviewActivityLimit, activityCount)}</span> : null}>
        {overview.recentActivity.length === 0
          ? <EmptyWorkspaceState compact icon={History} title={t.noActivity} detail={t.noActivityDetail} />
          // It scrolls only beside three or more attention items (see overview.css), and only then needs a focus stop.
          : <div className="activity-scroll" tabIndex={overview.attention.items.length >= 3 ? 0 : undefined} role="region" aria-label={t.activity}>
            <OverviewActivity events={overview.recentActivity} organizationId={organizationId} context={context} />
          </div>}
      </WorkspaceSection>
    </div>
  </>
}

function SummaryCardView({ card }: { card: SummaryCard }) {
  const Icon = cardIcons[card.id]
  const content = <>
    <span className="summary-card-top">
      <span className={`summary-card-icon tone-${card.tone}`} aria-hidden><Icon size={15} /></span>
      <span className="summary-card-label">{card.label}</span>
    </span>
    <strong className="summary-card-value">{card.value}</strong>
    <span className={`summary-card-detail ${card.tone === 'neutral' ? '' : `text-${card.tone}`}`}>{card.detail}</span>
  </>
  const className = `summary-card ${card.id === 'incidents' && card.tone !== 'neutral' ? 'summary-card-priority' : ''} ${card.tone === 'danger' || card.tone === 'warning' ? `summary-card-${card.tone}` : ''}`
  return card.to === null
    ? <div className={className} data-card={card.id}>{content}</div>
    : <Link className={`${className} summary-card-link`} data-card={card.id} to={card.to}>{content}</Link>
}

const attentionIcons: Record<string, LucideIcon> = {
  OPERATION_UNKNOWN: CircleHelp,
  INCIDENT: TriangleAlert,
  NODE_OFFLINE: WifiOff,
  SYNC_FAILED: CircleAlert,
  OPERATION_FAILED: CircleAlert,
}

function AttentionSection({ organizationId, overview, context }: { organizationId: string; overview: OperationsOverviewResponse; context: string }) {
  const i18n = useI18n()
  const t = i18n.t.overview
  const { items, total } = overview.attention
  if (items.length === 0) return null
  return <div id="overview-attention" tabIndex={-1}><WorkspaceSection title={t.attention} className="attention-section"
    actions={total > items.length ? <span className="section-meta">{t.showing(items.length, total)}</span> : null}>
    <ol className="attention-list" aria-label={t.attention}>
        {items.map(item => <AttentionRow key={`${item.kind}-${item.id}`} organizationId={organizationId} item={item} context={context} />)}
      </ol>
  </WorkspaceSection></div>
}

function AttentionRow({ organizationId, item, context }: { organizationId: string; item: AttentionItemResponse; context: string }) {
  const i18n = useI18n()
  const presentation = getAttentionPresentation(organizationId, item, i18n)
  const Icon = attentionIcons[item.kind] ?? CircleAlert
  return <li className={`attention-item tone-${presentation.tone}`} data-kind={item.kind}>
    <span className="attention-icon" aria-hidden><Icon size={16} /></span>
    <div className="attention-body">
      <div className="attention-heading">
        <span className="attention-title">{presentation.title}</span>
        <StatusIndicator label={presentation.status} tone={presentation.tone} />
      </div>
      <div className="attention-secondary">
        <span className="attention-subject">{presentation.subject}</span>
        {presentation.detail !== null ? <span className="attention-detail">{presentation.detail}</span> : null}
      </div>
    </div>
    <time className="attention-time" dateTime={item.occurredAt} title={i18n.format.dateTime(item.occurredAt)}>
      {i18n.format.relative(item.occurredAt)}</time>
    {presentation.to !== null ? <Link className="secondary-button attention-open" to={withWorkspaceContext(presentation.to, new URLSearchParams(context))}
      aria-label={i18n.t.overview.openItem(presentation.subject)}>{i18n.t.overview.open}</Link> : null}
  </li>
}

/**
 * First-run setup. Every step is derived from what exists — projects, environments, connections,
 * discovered resources — so there is no onboarding state to keep. Only the next step offers an
 * action, and only to someone allowed to take it.
 */
function OnboardingGuide({ organizationId, overview }: { organizationId: string; overview: OperationsOverviewResponse }) {
  const i18n = useI18n()
  const t = i18n.t.overview.onboarding
  const permissions = useOrganizationPermissions(organizationId)
  const projects = useProjects(organizationId)
  const connections = useConnections(organizationId)
  // The same cache entries the context switcher and the projects page use.
  const environmentQueries = useQueries({ queries: (projects.data ?? []).map(project => ({
    queryKey: ['environments', organizationId, project.id],
    queryFn: () => getEnvironments(organizationId, project.id),
  })) })
  const environmentsLoaded = projects.isSuccess && environmentQueries.every(query => query.isSuccess)
  const firstProject = projects.data?.[0]
  const firstConnection = connections.data?.find(connection => connection.active)

  const state = onboardingState({
    projects: projects.data?.length ?? 0,
    environments: environmentsLoaded ? environmentQueries.reduce((sum, query) => sum + (query.data?.length ?? 0), 0) : null,
    connections: overview.summary.connections.total,
    synchronized: overview.summary.connections.healthy > 0,
    resources: overview.summary.nodes.total + overview.summary.containers.total,
  })
  const base = `/organizations/${encodeURIComponent(organizationId)}`

  function actionPath(step: OnboardingStep): string | null {
    switch (step.id) {
      case 'project': return `${base}/projects/new`
      case 'environment': return firstProject ? `${base}/projects/${encodeURIComponent(firstProject.id)}/environments/new` : null
      case 'connection': return `${base}/connections/new`
      case 'sync': return firstConnection ? `${base}/connections/${encodeURIComponent(firstConnection.id)}` : `${base}/connections`
      default: return null
    }
  }

  const loading = projects.isPending || (projects.isSuccess && !environmentsLoaded)
  return <section className="onboarding" aria-labelledby="onboarding-title">
    <div className="onboarding-heading">
      <h2 id="onboarding-title">{t.title}</h2>
      <p>{t.detail}</p>
      <span className="section-meta">{t.progress(state.completed, state.steps.length)}</span>
    </div>
    {loading ? <div className="row-skeleton" aria-label={i18n.t.common.loading}><span /><span /><span /></div> :
      <ol className="onboarding-steps">
        {state.steps.map(step => {
          const current = state.next?.id === step.id
          const allowed = step.permission === null || permissions.can(step.permission)
          const path = current && allowed ? actionPath(step) : null
          return <li key={step.id} className={`onboarding-step ${step.done ? 'done' : ''} ${current ? 'current' : ''}`}
            aria-current={current ? 'step' : undefined}>
            <span className="onboarding-mark" aria-hidden>{step.done ? <CheckCircle2 size={20} /> : <Circle size={20} />}</span>
            <div className="onboarding-text">
              <strong>{t.steps[step.id].title}</strong>
              <span>{t.steps[step.id].detail}</span>
              {current && !allowed ? <span className="onboarding-note">{t.ownerOnly}</span> : null}
            </div>
            {path ? <Link className="primary-button" to={path}>{t.actions[step.id]}</Link> : null}
          </li>
        })}
      </ol>}
  </section>
}
