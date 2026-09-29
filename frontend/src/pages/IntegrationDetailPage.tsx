import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { RefreshCw, Search } from 'lucide-react'
import { ApiError } from '../api/httpClient'
import { useIntegration, useTestIntegration } from '../api/integrations'
import {
  useBindingCandidates, useBindNode, useIntegrationInventory, useIntegrationSummary, useIntegrationSyncSessions,
  useSyncIntegration, useUnbindNode, type InventoryKind, type InventoryParams,
} from '../api/integrationInventory'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader,
  WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { nodeStateTones, sessionTones, useSyncErrorText } from '../components/integrations/integrationPresentation'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type {
  InventoryObject, IntegrationOverview, IntegrationSyncSession, RemnawaveConfigProfileSummary,
  RemnawaveHostSummary, RemnawaveNodeSummary,
} from '../types/integration'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/integrations.css'

const Tabs = ['overview', 'nodes', 'hosts', 'profiles', 'history'] as const
type Tab = typeof Tabs[number]
const PageSize = 50


export function IntegrationDetailPage() {
  const { organizationId, integrationId } = useParams()
  if (!organizationId || !integrationId) return <InvalidRoutePage />
  return <IntegrationDetail organizationId={organizationId} integrationId={integrationId} />
}

function IntegrationDetail({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const permissions = useOrganizationPermissions(organizationId)
  // Nothing about the integration is requested for someone who may not manage integrations.
  const canManage = permissions.can('manageIntegrations')
  const query = useIntegration(organizationId, integrationId, canManage)
  const sync = useSyncIntegration(organizationId, integrationId)
  const test = useTestIntegration(organizationId)
  const errorText = useSyncErrorText()
  const active = Tabs.find(value => value === searchParams.get('tab')) ?? 'overview'
  const selectTab = (next: Tab) => setSearchParams(previous => {
    const updated = new URLSearchParams(previous)
    if (next === 'overview') updated.delete('tab'); else updated.set('tab', next)
    return updated
  }, { replace: true })
  const orgPath = `/organizations/${encodeURIComponent(organizationId)}`
  const back = { label: i18n.t.integrations.title, to: `${orgPath}/integrations` }

  if (permissions.isPending) return <AppShell><PageLoading title={i18n.t.integrations.title} back={back} label={i18n.t.common.loading} /></AppShell>
  if (!canManage) return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={i18n.t.integrations.title} back={back} />
    <InlineAlert tone="danger" title={i18n.t.integrations.accessDenied} /></div></AppShell>
  if (query.isPending) return <AppShell><PageLoading title={i18n.t.integrations.title} back={back} label={i18n.t.integrations.loading} /></AppShell>
  if (query.isError || !query.data) {
    return <AppShell><PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'INTEGRATION_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={i18n.t.integrations.loadError} /></AppShell>
  }
  const integration = query.data
  const syncResult = sync.data
  return <AppShell><div className="workspace-page integration-page">
    <WorkspaceHeader title={integration.name} subtitle={`Remnawave · ${integration.baseUrl}`} back={back}
      status={<StatusIndicator label={integration.enabled ? i18n.t.integrations.enabled : i18n.t.integrations.disabled}
        tone={integration.enabled ? 'success' : 'neutral'} />}
      actions={<>
        <button className="primary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>
          <RefreshCw aria-hidden size={16} />{sync.isPending ? t.syncing : t.syncNow}</button>
        <button className="secondary-button" type="button" disabled={test.isPending} onClick={() => test.mutate(integration.id)}>
          {test.isPending ? i18n.t.integrations.testing : i18n.t.integrations.test}</button>
        <Link className="secondary-button" to={`${orgPath}/integrations/${encodeURIComponent(integration.id)}/edit${location.search}`}>
          {i18n.t.integrations.edit}</Link>
      </>} />
    {!integration.enabled ? <InlineAlert tone="info" title={t.autoDisabled}>{t.autoDisabledDetail}</InlineAlert> : null}
    {syncResult?.status === 'COMPLETED' ? <InlineAlert tone="success" title={t.syncDone}>
      {syncResult.counts ? t.objects(syncResult.counts.nodes, syncResult.counts.hosts, syncResult.counts.configProfiles,
        syncResult.counts.deactivated) : null}</InlineAlert> : null}
    {syncResult?.status === 'FAILED' ? <InlineAlert tone="danger" title={t.syncFailed}>{errorText(syncResult.errorCode)}</InlineAlert> : null}
    {sync.isError ? <InlineAlert tone="danger" title={t.syncFailed}>
      {sync.error instanceof ApiError ? errorText(sync.error.code) : describeError(sync.error, i18n)}</InlineAlert> : null}
    {test.isSuccess ? <InlineAlert tone="success" title={i18n.t.integrations.testSuccess} /> : null}
    {test.isError ? <InlineAlert tone="danger" title={i18n.t.integrations.testError}>
      {test.error instanceof ApiError ? errorText(test.error.code) : describeError(test.error, i18n)}</InlineAlert> : null}
    <WorkspaceTabs tabs={[{ id: 'overview', label: t.tabs.overview }, { id: 'nodes', label: t.tabs.nodes },
      { id: 'hosts', label: t.tabs.hosts }, { id: 'profiles', label: t.tabs.profiles }, { id: 'history', label: t.tabs.history }]}
    active={active} onChange={selectTab} />
    <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
      {active === 'overview' ? <OverviewTab organizationId={organizationId} integrationId={integrationId} enabled={integration.enabled} /> : null}
      {active === 'nodes' ? <NodesTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'hosts' ? <HostsTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'profiles' ? <ProfilesTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'history' ? <HistoryTab organizationId={organizationId} integrationId={integrationId} /> : null}
    </div>
  </div></AppShell>
}

function OverviewTab({ organizationId, integrationId, enabled }: { organizationId: string; integrationId: string; enabled: boolean }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const summary = useIntegrationSummary(organizationId, integrationId, true)
  const errorText = useSyncErrorText()
  if (summary.isPending) return <p role="status">{i18n.t.common.loading}</p>
  if (summary.isError) return <LoadError error={summary.error} retry={() => summary.refetch()} />
  const value: IntegrationOverview = summary.data
  const last = value.lastSync
  return <WorkspaceSection title={t.tabs.overview} description={t.readOnly}>
    <PropertyGrid columns={2} items={[
      { label: t.lastSync, value: last ? <span className="integration-inline">
        <StatusIndicator label={t.status[last.status] ?? last.status} tone={sessionTones[last.status]} />
        <time dateTime={last.startedAt}>{i18n.format.dateTime(last.startedAt)}</time></span> : t.never },
      { label: t.lastSuccess, value: value.lastSuccessfulSyncAt ? i18n.format.dateTime(value.lastSuccessfulSyncAt) : t.never },
      { label: t.nextRun, value: enabled && value.nextRunAt ? i18n.format.dateTime(value.nextRunAt) : t.notScheduled },
      ...(last?.status === 'FAILED' ? [{ label: t.lastError, value: errorText(last.errorCode) }] : []),
      { label: t.tabs.nodes, value: t.counts(value.inventory.nodes.active, value.inventory.nodes.inactive) },
      { label: t.tabs.hosts, value: t.counts(value.inventory.hosts.active, value.inventory.hosts.inactive) },
      { label: t.tabs.profiles, value: t.counts(value.inventory.configProfiles.active, value.inventory.configProfiles.inactive) },
    ]} />
  </WorkspaceSection>
}

function useInventoryParams() {
  const [params, setParams] = useState<InventoryParams>({ search: '', active: '', state: '', offset: 0, limit: PageSize })
  // Any filter change starts again from the first page.
  const update = (patch: Partial<InventoryParams>) => setParams(previous => ({ ...previous, offset: 0, ...patch }))
  return { params, update, page: (offset: number) => setParams(previous => ({ ...previous, offset })) }
}

function InventoryFilters({ params, update, withState }: {
  params: InventoryParams; update: (patch: Partial<InventoryParams>) => void; withState: boolean
}) {
  const { t } = useI18n(); const text = t.integrationInventory
  return <div className="filter-bar integration-filter-bar">
    <div className="search-field">
      <Search className="search-field-icon" aria-hidden size={16} />
      <input type="search" aria-label={text.search} placeholder={text.search} value={params.search} maxLength={128}
        autoComplete="off" spellCheck={false} onChange={event => update({ search: event.target.value })} />
    </div>
    {withState ? <label className="field compact-field">{text.stateFilter}
      <select value={params.state} onChange={event => update({ state: event.target.value })}>
        <option value="">{text.allStates}</option>
        {Object.entries(text.nodeState).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
      </select></label> : null}
    <label className="field compact-field">{text.presence}
      <select value={params.active} onChange={event => update({ active: event.target.value as InventoryParams['active'] })}>
        <option value="">{text.allObjects}</option><option value="true">{text.presentOnly}</option>
        <option value="false">{text.goneOnly}</option>
      </select></label>
  </div>
}

function Pager({ total, params, page }: { total: number; params: InventoryParams; page: (offset: number) => void }) {
  const { t } = useI18n(); const text = t.integrationInventory
  if (total <= params.limit) return null
  return <div className="integration-pager">
    <span>{text.page(params.offset + 1, Math.min(params.offset + params.limit, total), total)}</span>
    <button className="secondary-button" type="button" disabled={params.offset === 0}
      onClick={() => page(Math.max(0, params.offset - params.limit))}>{text.previous}</button>
    <button className="secondary-button" type="button" disabled={params.offset + params.limit >= total}
      onClick={() => page(params.offset + params.limit)}>{text.next}</button>
  </div>
}

function InventoryTable<S>({ kind, organizationId, integrationId, title, emptyTitle, head, row, withState = false }: {
  kind: InventoryKind; organizationId: string; integrationId: string; title: string; emptyTitle: string
  head: ReactNode; row: (item: InventoryObject<S>) => ReactNode; withState?: boolean
}) {
  const { t } = useI18n(); const text = t.integrationInventory
  const { params, update, page } = useInventoryParams()
  const query = useIntegrationInventory(organizationId, integrationId, kind, params, true)
  const filtered = params.search !== '' || params.active !== '' || params.state !== ''
  const data = query.data as { items: InventoryObject<S>[]; total: number } | undefined
  return <WorkspaceSection title={title}>
    <InventoryFilters params={params} update={update} withState={withState} />
    {query.isPending ? <p role="status">{t.common.loading}</p> : null}
    {query.isError ? <LoadError error={query.error} retry={() => query.refetch()} /> : null}
    {data && data.total === 0 ? <EmptyWorkspaceState compact title={filtered ? text.emptyFiltered : emptyTitle}
      detail={filtered ? undefined : text.emptyDetail} /> : null}
    {data && data.items.length > 0 ? <div className="table-scroll"><table className="data-grid integration-grid">
      <thead>{head}</thead><tbody>{data.items.map(item => row(item))}</tbody></table></div> : null}
    {data ? <Pager total={data.total} params={params} page={page} /> : null}
  </WorkspaceSection>
}

function hostPort(address: string, port: number | null) { return port === null ? address : `${address}:${port}` }

function formatBytes(value: number | null, format: (value: number) => string): string {
  if (value === null) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let amount = value; let unit = 0
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit += 1 }
  return `${format(Math.round(amount * 10) / 10)} ${units[unit]}`
}

function NodesTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const [binding, setBinding] = useState<InventoryObject<RemnawaveNodeSummary> | null>(null)
  const unbind = useUnbindNode(organizationId, integrationId)
  const orgPath = `/organizations/${encodeURIComponent(organizationId)}`
  return <>
    {unbind.isError ? <InlineAlert tone="danger" title={t.bindError}>{describeError(unbind.error, i18n)}</InlineAlert> : null}
    <InventoryTable<RemnawaveNodeSummary> kind="nodes" organizationId={organizationId} integrationId={integrationId}
      title={t.tabs.nodes} emptyTitle={t.emptyNodes} withState
      head={<tr><th>{t.name}</th><th>{t.address}</th><th>{t.state}</th><th>{t.version}</th><th>{t.users}</th>
        <th>{t.traffic}</th><th>{t.resource}</th><th>{t.actions}</th></tr>}
      row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
        <td><strong>{item.displayName}</strong>{!item.active ? <> <StatusIndicator label={t.gone} /></> : null}</td>
        <td className="property-technical">{hostPort(item.summary.address, item.summary.port)}</td>
        <td><StatusIndicator label={t.nodeState[item.summary.state] ?? item.summary.state} tone={nodeStateTones[item.summary.state]} /></td>
        <td>{item.summary.xrayVersion ?? '—'}</td>
        <td>{i18n.format.number(item.summary.usersOnline)}</td>
        <td>{formatBytes(item.summary.trafficUsedBytes, i18n.format.number)}</td>
        <td>{item.binding ? <Link to={`${orgPath}/environments/${encodeURIComponent(item.binding.environment.id)}/resources/${encodeURIComponent(item.binding.resource.id)}`}>
          {item.binding.resource.name}</Link> : <span className="muted-copy">{t.notBound}</span>}
          {item.binding ? <div className="muted-copy">{item.binding.project.name} · {item.binding.environment.name}</div> : null}</td>
        <td><div className="integration-row-actions">
          <button className="secondary-button" type="button" onClick={() => setBinding(item)}>{item.binding ? t.change : t.bind}</button>
          {item.binding ? <button className="text-button" type="button" disabled={unbind.isPending}
            onClick={() => unbind.mutate(item.id)}>{t.unbind}</button> : null}
        </div></td>
      </tr>} />
    {binding ? <BindDialog organizationId={organizationId} integrationId={integrationId} node={binding}
      onClose={() => setBinding(null)} /> : null}
  </>
}

function BindDialog({ organizationId, integrationId, node, onClose }: {
  organizationId: string; integrationId: string; node: InventoryObject<RemnawaveNodeSummary>; onClose: () => void
}) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState(node.binding?.resource.id ?? '')
  const candidates = useBindingCandidates(organizationId, integrationId, search, true)
  const bind = useBindNode(organizationId, integrationId)
  const errorText = useSyncErrorText()
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape' && !bind.isPending) onClose() }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [bind.isPending, onClose])
  return <div className="dialog-backdrop" role="presentation" onMouseDown={event => {
    if (event.target === event.currentTarget && !bind.isPending) onClose()
  }}><section className="monitor-rule-dialog integration-bind-dialog" role="dialog" aria-modal="true" aria-labelledby="bind-dialog-title">
    <div className="dialog-heading"><h2 id="bind-dialog-title">{t.bindTitle(node.displayName)}</h2>
      <button className="dialog-close" type="button" aria-label={i18n.t.common.close} onClick={onClose} disabled={bind.isPending}>×</button></div>
    <div className="dialog-body">
      <p className="muted-copy">{t.bindDetail}</p>
      <div className="search-field"><Search className="search-field-icon" aria-hidden size={16} />
        <input type="search" aria-label={t.search} placeholder={t.search} value={search} maxLength={128}
          autoComplete="off" onChange={event => setSearch(event.target.value)} /></div>
      {candidates.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
      {candidates.isError ? <LoadError error={candidates.error} retry={() => candidates.refetch()} /> : null}
      {candidates.data?.length === 0 ? <p className="muted-copy">{t.candidatesEmpty}</p> : null}
      {candidates.data?.length ? <fieldset className="integration-candidates"><legend className="visually-hidden">{t.resource}</legend>
        {candidates.data.map(candidate => <label key={candidate.id} className="integration-candidate">
          <input type="radio" name="binding-candidate" value={candidate.id} checked={selected === candidate.id}
            onChange={() => setSelected(candidate.id)} />
          <span><strong>{candidate.name}</strong><span className="muted-copy"> {candidate.code} · {candidate.project.name} · {candidate.environment.name}</span></span>
        </label>)}</fieldset> : null}
      {bind.isError ? <InlineAlert tone="danger" title={t.bindError}>
        {bind.error instanceof ApiError ? errorText(bind.error.code) : describeError(bind.error, i18n)}</InlineAlert> : null}
    </div>
    <div className="dialog-actions">
      <button className="secondary-button" type="button" disabled={bind.isPending} onClick={onClose}>{i18n.t.common.cancel}</button>
      <button className="primary-button" type="button" disabled={bind.isPending || selected === ''}
        onClick={() => bind.mutate({ objectId: node.id, resourceId: selected }, { onSuccess: onClose })}>{t.bind}</button>
    </div>
  </section></div>
}

function HostsTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const { t } = useI18n(); const text = t.integrationInventory
  return <InventoryTable<RemnawaveHostSummary> kind="hosts" organizationId={organizationId} integrationId={integrationId}
    title={text.tabs.hosts} emptyTitle={text.emptyHosts}
    head={<tr><th>{text.name}</th><th>{text.address}</th><th>{text.security}</th><th>{text.flags}</th><th>{text.nodes}</th></tr>}
    row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
      <td><strong>{item.displayName}</strong>{!item.active ? <> <StatusIndicator label={text.gone} /></> : null}</td>
      <td className="property-technical">{hostPort(item.summary.address, item.summary.port)}</td>
      <td>{item.summary.securityLayer}</td>
      <td>{[item.summary.isDisabled ? text.disabledFlag : null, item.summary.isHidden ? text.hidden : null]
        .filter(Boolean).join(' · ') || '—'}</td>
      <td>{item.summary.nodeUuids.length}</td>
    </tr>} />
}

function ProfilesTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const text = i18n.t.integrationInventory
  return <InventoryTable<RemnawaveConfigProfileSummary> kind="config-profiles" organizationId={organizationId}
    integrationId={integrationId} title={text.tabs.profiles} emptyTitle={text.emptyProfiles}
    head={<tr><th>{text.name}</th><th>{text.inbounds}</th><th>{text.nodes}</th><th>{text.updated}</th></tr>}
    row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
      <td><strong>{item.displayName}</strong>{!item.active ? <> <StatusIndicator label={text.gone} /></> : null}</td>
      <td>{item.summary.inbounds.length === 0 ? '—' : item.summary.inbounds.map(inbound =>
        [inbound.tag, inbound.type, inbound.port].filter(value => value !== null).join(' ')).join(', ')}</td>
      <td>{item.summary.nodeUuids.length}</td>
      <td>{i18n.format.dateTime(item.summary.updatedAt)}</td>
    </tr>} />
}

function HistoryTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const sessions = useIntegrationSyncSessions(organizationId, integrationId, true)
  const errorText = useSyncErrorText()
  const took = (session: IntegrationSyncSession) => session.finishedAt === null ? null
    : Math.max(0, Math.round((Date.parse(session.finishedAt) - Date.parse(session.startedAt)) / 1000))
  return <WorkspaceSection title={t.tabs.history}>
    {sessions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {sessions.isError ? <LoadError error={sessions.error} retry={() => sessions.refetch()} /> : null}
    {sessions.data?.length === 0 ? <EmptyWorkspaceState compact title={t.emptyHistory} detail={t.emptyDetail} /> : null}
    {sessions.data?.length ? <div className="table-scroll"><table className="data-grid integration-grid">
      <thead><tr><th>{t.started}</th><th>{t.trigger_}</th><th>{t.state}</th><th>{t.duration}</th><th>{t.result}</th></tr></thead>
      <tbody>{sessions.data.map(session => <tr key={session.id}>
        <td><time dateTime={session.startedAt}>{i18n.format.dateTime(session.startedAt)}</time></td>
        <td>{t.trigger[session.trigger] ?? session.trigger}</td>
        <td><StatusIndicator label={t.status[session.status] ?? session.status} tone={sessionTones[session.status]} /></td>
        <td>{i18n.format.duration(took(session))}</td>
        <td>{session.status === 'FAILED' ? errorText(session.errorCode)
          : session.counts ? t.objects(session.counts.nodes, session.counts.hosts, session.counts.configProfiles,
            session.counts.deactivated) : '—'}</td>
      </tr>)}</tbody></table></div> : null}
  </WorkspaceSection>
}

function LoadError({ error, retry }: { error: unknown; retry: () => void }) {
  const i18n = useI18n()
  return <InlineAlert tone="danger" title={i18n.t.integrationInventory.loadError}
    action={<button className="secondary-button" type="button" onClick={retry}>{i18n.t.common.retry}</button>}>
    {describeError(error, i18n)}</InlineAlert>
}
