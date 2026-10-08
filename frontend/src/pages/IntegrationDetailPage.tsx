import { useState, type ReactNode } from 'react'
import { Link, useLocation, useParams, useSearchParams, useNavigate } from 'react-router-dom'
import { RefreshCw, Search } from 'lucide-react'
import { ApiError } from '../api/httpClient'
import { useDeleteIntegration, useSetIntegrationEnabled, useIntegration, useTestIntegration } from '../api/integrations'
import { useIntegrationActions } from '../api/integrationActions'
import { useAdoptConfigProfile } from '../api/integrationConfigProfiles'
import {
  useBindingCandidates, useBindNode, useIntegrationInventory, useIntegrationSummary, useIntegrationSyncSessions,
  useSyncIntegration, useUnbindNode, type InventoryKind, type InventoryParams,
} from '../api/integrationInventory'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { contextSearch } from '../components/layout/workspaceNavigation'
import {
  EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceHeader,
  WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { connectionHealth, nodeStateTones, sessionTones, useSyncErrorText } from '../components/integrations/integrationPresentation'
import { NodeActionControls } from '../components/integrations/NodeActionControls'
import { InventoryArchiveControl } from '../components/integrations/InventoryArchiveControl'
import { DesiredStateControl, ManagementSection, useDesiredStateCopy } from '../components/integrations/DesiredStateControls'
import { PageActionMenu } from '../components/layout/PageActionMenu'
import { RefreshWarning, isUnavailableError } from '../components/layout/RefreshWarning'
import { IntegrationDialog } from '../components/integrations/IntegrationDialog'
import { useI18n } from '../i18n'
import { describeIntegrationError } from '../components/integrations/integrationPresentation'
import { NodeOnboarding } from '../components/integrations/NodeOnboarding'
import { FleetSection } from '../components/integrations/FleetSection'
import type {
  InventoryObject, IntegrationManagementMode, IntegrationOverview, IntegrationResponse, IntegrationSyncSession,
  RemnawaveConfigProfileSummary, RemnawaveHostSummary, RemnawaveNodeSummary,
} from '../types/integration'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/integrations.css'

const Tabs = ['overview', 'nodes', 'hosts', 'profiles', 'fleet', 'history', 'actions'] as const
type Tab = typeof Tabs[number]
const PageSize = 50


export function IntegrationDetailPage() {
  const { organizationId, integrationId } = useParams()
  if (!organizationId || !integrationId) return <InvalidRoutePage />
  return <IntegrationDetail organizationId={organizationId} integrationId={integrationId} />
}

function IntegrationDetail({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const location = useLocation(); const navigate = useNavigate()
  const lifecycle = useSetIntegrationEnabled(organizationId)
  const remove = useDeleteIntegration(organizationId)
  const [confirmation, setConfirmation] = useState<'disable' | 'delete' | 'abandon' | null>(null)
  const [checkedAt, setCheckedAt] = useState<string | null>(null)
  const ui = i18n.t.integrationUi
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
  const back = { label: i18n.t.integrations.title,
    to: `${orgPath}/integrations${contextSearch(new URLSearchParams(location.search))}` }

  if (permissions.isPending) return <AppShell><PageLoading title={i18n.t.integrations.title} back={back} label={i18n.t.common.loading} /></AppShell>
  if (!canManage) return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={i18n.t.integrations.title} back={back} />
    <InlineAlert tone="danger" title={i18n.t.integrations.accessDenied} /></div></AppShell>
  if (query.isPending) return <AppShell><PageLoading title={i18n.t.integrations.title} back={back} label={i18n.t.integrations.loading} /></AppShell>
  if (!query.data || isUnavailableError(query.error)) {
    return <AppShell><PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'INTEGRATION_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={i18n.t.integrations.loadError} /></AppShell>
  }
  const integration = query.data
  const syncResult = sync.data
  return <AppShell><div className="workspace-page work-page detail-page integration-page">
    <WorkspaceHeader title={integration.name} subtitle={`Remnawave · ${integration.baseUrl}`} back={back}
      status={<StatusIndicator label={integration.enabled ? i18n.t.integrations.enabled : i18n.t.integrations.disabled}
        tone="neutral" />}
      actions={<>
        <button className="primary-button" type="button" disabled={sync.isPending} onClick={() => sync.mutate()}>
          <RefreshCw aria-hidden size={16} />{sync.isPending ? t.syncing : t.syncNow}</button>
        <button className="secondary-button" type="button" disabled={test.isPending} onClick={() => test.mutate(integration.id, { onSettled: () => setCheckedAt(new Date().toISOString()) })}>
          {test.isPending ? i18n.t.integrations.testing : i18n.t.integrations.test}</button>
        <PageActionMenu actions={[
          { label: i18n.t.integrations.edit, to: `${orgPath}/integrations/${encodeURIComponent(integration.id)}/edit${location.search}` },
          { label: integration.enabled ? i18n.t.integrations.disable : i18n.t.integrations.enable, disabled: lifecycle.isPending,
            onSelect: () => integration.enabled ? setConfirmation('disable') : lifecycle.mutate({ id: integration.id, enabled: true }) },
          { label: i18n.t.integrations.delete, danger: true, onSelect: () => setConfirmation('delete') },
        ]} />
      </>} />
    <p className="muted-copy">{ui.purpose}</p>
    {query.isError ? <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => query.refetch()} /> : null}
    {lifecycle.isError ? <InlineAlert tone="danger" title={describeIntegrationError(lifecycle.error, i18n)} /> : null}
    {confirmation ? <IntegrationDialog title={confirmation === 'abandon' ? ui.abandonTitle : confirmation === 'delete' ? ui.deleteTitle : ui.disableTitle}
      onClose={() => setConfirmation(null)} busy={remove.isPending || lifecycle.isPending} actions={<>
        <button className="secondary-button" onClick={() => setConfirmation(null)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" disabled={remove.isPending || lifecycle.isPending} onClick={() => {
          if (confirmation === 'abandon') remove.mutate({ id: integration.id, abandonRecovery: true }, { onSuccess: () => navigate(back.to) })
          else if (confirmation === 'delete') remove.mutate(integration.id, { onSuccess: () => navigate(back.to) })
          else lifecycle.mutate({ id: integration.id, enabled: false }, { onSuccess: () => setConfirmation(null) })
        }}>{confirmation === 'abandon' ? ui.abandonTitle : confirmation === 'delete' ? i18n.t.integrations.confirmDelete : i18n.t.integrations.disable}</button></>}>
      <strong>{integration.name}</strong><p>{confirmation === 'abandon' ? ui.abandonDetail : confirmation === 'delete' ? ui.deleteDetail : ui.disableDetail}</p>
      {remove.isError ? <InlineAlert tone="danger" title={describeIntegrationError(remove.error, i18n)} /> : null}
      {confirmation === 'delete' && remove.error instanceof ApiError && remove.error.code === 'INTEGRATION_RECOVERY_REQUIRED' ?
        <button className="secondary-button" onClick={() => { remove.reset(); setConfirmation('abandon') }}>{ui.abandonTitle}</button> : null}
    </IntegrationDialog> : null}
    {!integration.enabled ? <InlineAlert tone="info" title={t.autoDisabled}>{t.autoDisabledDetail}</InlineAlert> : null}
    {syncResult?.status === 'COMPLETED' ? <InlineAlert tone="success" title={t.syncDone}>
      {syncResult.counts ? t.objects(syncResult.counts.nodes, syncResult.counts.hosts, syncResult.counts.configProfiles,
        syncResult.counts.deactivated) : null}</InlineAlert> : null}
    {syncResult?.status === 'FAILED' ? <InlineAlert tone="danger" title={t.syncFailed}>{errorText(syncResult.errorCode)}</InlineAlert> : null}
    {sync.isError ? <InlineAlert tone="danger" title={t.syncFailed}>
      {sync.error instanceof ApiError ? errorText(sync.error.code) : describeIntegrationError(sync.error, i18n)}</InlineAlert> : null}
    {test.isSuccess ? <InlineAlert tone={test.data.ok ? "success" : "danger"} title={test.data.ok ? i18n.t.integrations.testSuccess : i18n.t.integrations.testError}>{test.data.ok ? ui.testDetail : null}</InlineAlert> : null}
    {test.isError ? <InlineAlert tone="danger" title={i18n.t.integrations.testError}>
      {test.error instanceof ApiError ? errorText(test.error.code) : describeIntegrationError(test.error, i18n)}</InlineAlert> : null}
    <WorkspaceTabs tabs={[{ id: 'overview', label: t.tabs.overview }, { id: 'nodes', label: t.tabs.nodes, count: integration.overview?.inventory.nodes.active },
      { id: 'hosts', label: t.tabs.hosts, count: integration.overview?.inventory.hosts.active }, { id: 'profiles', label: t.tabs.profiles, count: integration.overview?.inventory.configProfiles.active }, { id: 'history', label: t.tabs.history },
      { id: 'fleet', label: t.tabs.fleet },
      { id: 'actions', label: t.actionTab.title }]}
    active={active} onChange={selectTab} />
    <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
      {active === 'overview' ? <OverviewTab organizationId={organizationId} integration={integration} checkedAt={checkedAt}
        health={connectionHealth(test.isSuccess ? test.data.ok : undefined, test.error instanceof ApiError ? test.error.code : undefined)} /> : null}
      {active === 'nodes' ? <NodesTab organizationId={organizationId} integrationId={integrationId}
        managementMode={integration.managementMode} /> : null}
      {active === 'hosts' ? <HostsTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'profiles' ? <ProfilesTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'fleet' ? <FleetSection organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'history' ? <HistoryTab organizationId={organizationId} integrationId={integrationId} /> : null}
      {active === 'actions' ? <ActionsTab organizationId={organizationId} integrationId={integrationId} /> : null}
    </div>
  </div></AppShell>
}

function OverviewTab({ organizationId, integration, checkedAt, health }: {
  organizationId: string; integration: IntegrationResponse; checkedAt: string | null; health: 'available' | 'unavailable' | 'unchecked'
}) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const integrationId = integration.id; const enabled = integration.enabled
  const summary = useIntegrationSummary(organizationId, integrationId, true)
  const errorText = useSyncErrorText()
  if (summary.isPending) return <p role="status">{i18n.t.common.loading}</p>
  if (!summary.data || isUnavailableError(summary.error)) return <LoadError error={summary.error} retry={() => summary.refetch()} />
  const value: IntegrationOverview = summary.data
  const last = value.lastSync
  const ui = i18n.t.integrationUi
  return <>
  {summary.isError ? <RefreshWarning updatedAt={summary.dataUpdatedAt} retry={() => summary.refetch()} /> : null}
  <WorkspaceSection title={ui.connection}><StatusIndicator label={ui[health]}
    tone={health === 'available' ? 'success' : health === 'unavailable' ? 'danger' : 'neutral'} />
    {checkedAt && health !== 'unchecked' ? <p className="muted-copy">{ui.lastChecked}: <time dateTime={checkedAt}>{i18n.format.dateTime(checkedAt)}</time></p> : null}
  </WorkspaceSection>
  <WorkspaceSection title={ui.sync}>
    <PropertyGrid columns={2} items={[
      { label: t.lastSync, value: last ? <span className="integration-inline">
        <StatusIndicator label={t.status[last.status] ?? last.status} tone={sessionTones[last.status]} />
        <time dateTime={last.startedAt}>{i18n.format.dateTime(last.startedAt)}</time></span> : t.never },
      { label: t.lastSuccess, value: value.lastSuccessfulSyncAt ? i18n.format.dateTime(value.lastSuccessfulSyncAt) : t.never },
      { label: t.nextRun, value: enabled && value.nextRunAt ? i18n.format.dateTime(value.nextRunAt) : t.notScheduled },
      ...(last?.status === 'FAILED' ? [{ label: t.lastError, value: errorText(last.errorCode) }] : []),
    ]} />
  </WorkspaceSection>
  <WorkspaceSection title={ui.inventory}><dl className="integration-counters">{([
    ['nodes', value.inventory.nodes], ['hosts', value.inventory.hosts], ['profiles', value.inventory.configProfiles],
  ] as const).map(([key, count]) => <div key={key}><dt>{t.tabs[key]}</dt>
    <dd>{t.counts(count.active, count.inactive)}</dd></div>)}</dl></WorkspaceSection>
  <ManagementSection organizationId={organizationId} integration={integration} counts={value.desiredState} />
  {last?.status === 'FAILED' || value.desiredState.drifted > 0 || value.desiredState.needsAttention > 0 ?
    <WorkspaceSection title={ui.attention}>
      {last?.status === 'FAILED' ? <InlineAlert tone="danger" title={t.syncFailed}>{errorText(last.errorCode)}</InlineAlert> : null}
      {value.desiredState.drifted > 0 ? <p>{i18n.t.integrationDesiredState.counters.drifted}: {value.desiredState.drifted}</p> : null}
      {value.desiredState.needsAttention > 0 ? <p>{i18n.t.integrationDesiredState.counters.attention}: {value.desiredState.needsAttention}</p> : null}
    </WorkspaceSection> : null}
  </>
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
  const integration = useIntegration(organizationId, integrationId, true)
  const synchronized = Boolean(integration.data?.overview?.lastSuccessfulSyncAt)
  const filtered = params.search !== '' || params.active !== '' || params.state !== ''
  const data = (isUnavailableError(query.error) ? undefined : query.data) as { items: InventoryObject<S>[]; total: number } | undefined
  return <WorkspaceSection title={title}>
    <InventoryFilters params={params} update={update} withState={withState} />
    {query.isPending ? <div className="row-skeleton" aria-label={t.common.loading}><span /><span /><span /></div> : null}
    {query.isError ? data ? <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => query.refetch()} /> :
      <LoadError error={query.error} retry={() => query.refetch()} /> : null}
    {data && data.total === 0 ? <EmptyWorkspaceState compact title={filtered ? text.emptyFiltered : emptyTitle}
      detail={filtered ? undefined : synchronized ? t.integrationUi.emptyAfterSync : kind === 'nodes' ? text.emptyNodesDetail : text.emptyDetail}
      action={filtered ? <button className="secondary-button" onClick={() => update({ search: '', active: '', state: '' })}>{t.integrationUi.resetFilters}</button> : undefined} /> : null}
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

function NodesTab({ organizationId, integrationId, managementMode }: {
  organizationId: string; integrationId: string; managementMode: IntegrationManagementMode
}) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const desiredCopy = useDesiredStateCopy()
  const [binding, setBinding] = useState<InventoryObject<RemnawaveNodeSummary> | null>(null)
  const [unlink, setUnlink] = useState<InventoryObject<RemnawaveNodeSummary> | null>(null)
  const scope = contextSearch(new URLSearchParams(useLocation().search))
  const unbind = useUnbindNode(organizationId, integrationId)
  const orgPath = `/organizations/${encodeURIComponent(organizationId)}`
  return <>
    <NodeOnboarding organizationId={organizationId} integrationId={integrationId} />
    {unbind.isError ? <InlineAlert tone="danger" title={t.bindError}>{describeIntegrationError(unbind.error, i18n)}</InlineAlert> : null}
    <InventoryTable<RemnawaveNodeSummary> kind="nodes" organizationId={organizationId} integrationId={integrationId}
      title={t.tabs.nodes} emptyTitle={t.emptyNodes} withState
      head={<tr><th>{t.name} / {t.address}</th><th>{t.state}</th><th>{t.users} / {t.traffic}</th>
        <th>{desiredCopy.desired}</th><th title={i18n.t.integrationUi.bindingHelp}>{t.resource}</th><th>{t.actions}</th></tr>}
      row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
        <td><strong>{item.displayName}</strong><div className="property-technical integration-node-address">{hostPort(item.summary.address, item.summary.port)}</div>
          {!item.active ? <small className="integration-last-seen">{t.lastSeen}: {i18n.format.dateTime(item.lastSeenAt)}</small> : null}</td>
        <td><StatusIndicator label={item.active ? t.nodeState[item.summary.state] : t.gone} tone={item.active ? nodeStateTones[item.summary.state] : "neutral"} />
          <div className="muted-copy integration-node-version">Xray {item.summary.xrayVersion ?? '—'}</div></td>
        <td><span>{t.users}: {i18n.format.number(item.summary.usersOnline)}</span>
          <div className="muted-copy">{formatBytes(item.summary.trafficUsedBytes, i18n.format.number)}</div></td>
        <td><DesiredStateControl organizationId={organizationId} integrationId={integrationId}
          managementMode={managementMode} node={item} /></td>
        <td>{item.binding ? <Link to={`${orgPath}/environments/${encodeURIComponent(item.binding.environment.id)}/resources/${encodeURIComponent(item.binding.resource.id)}${scope}`}>
          {item.binding.resource.name}</Link> : <span className="muted-copy">{t.notBound}</span>}
          {item.binding ? <div className="muted-copy">{item.binding.project.name} · {item.binding.environment.name}</div> : null}</td>
        <td><div className="integration-row-actions">
          <InventoryArchiveControl organizationId={organizationId} integrationId={integrationId} item={item} />
          <NodeActionControls organizationId={organizationId} integrationId={integrationId} node={item} compact
            extraActions={[
              ...(item.active ? [{ label: item.binding ? t.change : t.bind, onSelect: () => setBinding(item) }] : []),
              ...(item.binding ? [{ label: t.unbind, danger: true, disabled: unbind.isPending, onSelect: () => setUnlink(item) }] : []),
            ]} />
        </div></td>
      </tr>} />
    {unlink ? <IntegrationDialog title={i18n.t.integrationUi.unlinkTitle} onClose={() => setUnlink(null)} busy={unbind.isPending}
      actions={<><button className="secondary-button" disabled={unbind.isPending} onClick={() => setUnlink(null)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" disabled={unbind.isPending} onClick={() => unbind.mutate(unlink.id, { onSuccess: () => setUnlink(null) })}>{t.unbind}</button></>}>
      <strong>{unlink.displayName} · {unlink.binding?.resource.name}</strong><p>{i18n.t.integrationUi.unlinkDetail}</p>
      {unbind.isError ? <InlineAlert tone="danger" title={describeIntegrationError(unbind.error, i18n)} /> : null}
    </IntegrationDialog> : null}
    {binding ? <BindDialog organizationId={organizationId} integrationId={integrationId} node={binding}
      onClose={() => setBinding(null)} /> : null}
  </>
}

function ActionsTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.integrationInventory.actionTab
  const errorText = useSyncErrorText()
  const actions = useIntegrationActions(organizationId, integrationId, true)
  return <WorkspaceSection title={i18n.t.integrationUi.actionHistory}>
    {actions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {actions.isError ? actions.data && !isUnavailableError(actions.error) ? <RefreshWarning updatedAt={actions.dataUpdatedAt} retry={() => actions.refetch()} /> : <LoadError error={actions.error} retry={() => actions.refetch()} /> : null}
    {!isUnavailableError(actions.error) && actions.data?.length === 0 ? <EmptyWorkspaceState compact title={t.empty} /> : null}
    {!isUnavailableError(actions.error) && actions.data?.length ? <div className="table-scroll"><table className="data-grid integration-grid">
      <thead><tr><th>{t.time}</th><th>{t.node}</th><th>{t.action}</th><th>{t.source}</th>
        <th>{t.requestedBy}</th><th>{t.status}</th><th>{t.duration}</th><th>{t.error}</th></tr></thead>
      <tbody>{actions.data.map(value => <tr key={value.id}>
        <td>{i18n.format.dateTime(value.createdAt)}</td><td>{value.displayName}</td><td>{t.codes[value.action]}</td>
        {/* Why the action exists: a person's request, or a desired state and the version it executed. */}
        <td>{value.source === 'DESIRED_STATE'
          ? `${t.automatic}${value.desiredStateVersion ? ` v${value.desiredStateVersion}` : ''}`
          : t.manual}</td>
        <td>{value.requestedByName ?? value.requestedByUserId}</td>
        <td><StatusIndicator label={t.statuses[value.status]} tone={value.status === "FAILED" ? "danger" : value.status === "UNKNOWN" ? "warning" : value.status === "SUCCEEDED" ? "success" : "info"} /></td>
        <td>{value.startedAt && value.finishedAt ? i18n.format.duration(Math.max(0,
          Math.round((Date.parse(value.finishedAt) - Date.parse(value.startedAt)) / 1000))) : '—'}</td>
        <td>{errorText(value.errorCode) ?? '—'}</td>
      </tr>)}</tbody></table></div> : null}
  </WorkspaceSection>
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
  return <IntegrationDialog title={t.bindTitle(node.displayName)} onClose={onClose} busy={bind.isPending}
    actions={<>
      <button className="secondary-button" type="button" disabled={bind.isPending} onClick={onClose}>{i18n.t.common.cancel}</button>
      <button className="primary-button" type="button" disabled={bind.isPending || selected === ''}
        onClick={() => bind.mutate({ objectId: node.id, resourceId: selected }, { onSuccess: onClose })}>{t.bind}</button>
</>}>
    <div>
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
        {bind.error instanceof ApiError ? errorText(bind.error.code) : describeIntegrationError(bind.error, i18n)}</InlineAlert> : null}
    </div>
  </IntegrationDialog>
}

function HostsTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const text = i18n.t.integrationInventory
  return <InventoryTable<RemnawaveHostSummary> kind="hosts" organizationId={organizationId} integrationId={integrationId}
    title={text.tabs.hosts} emptyTitle={text.emptyHosts}
    head={<tr><th>{text.name}</th><th>{text.address}</th><th>{text.security}</th><th>{text.flags}</th><th>{text.nodes}</th><th>{text.actions}</th></tr>}
    row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
      <td><strong>{item.displayName}</strong>{!item.active ? <><StatusIndicator label={text.gone} /><small className="integration-last-seen">{text.lastSeen}: {i18n.format.dateTime(item.lastSeenAt)}</small></> : null}</td>
      <td className="property-technical">{hostPort(item.summary.address, item.summary.port)}</td>
      <td>{item.summary.securityLayer}</td>
      <td>{[item.summary.isDisabled ? text.disabledFlag : null, item.summary.isHidden ? text.hidden : null]
        .filter(Boolean).join(' · ') || '—'}</td>
      <td>{item.summary.nodeUuids.length}</td>
      <td><InventoryArchiveControl organizationId={organizationId} integrationId={integrationId} item={item} /></td>
    </tr>} />
}

function ProfilesTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const text = i18n.t.integrationInventory; const c = i18n.t.integrationConfig
  const permissions = useOrganizationPermissions(organizationId)
  const canManageConfig = permissions.can('manageIntegrations') && permissions.can('manageConfigurations')
  return <InventoryTable<RemnawaveConfigProfileSummary> kind="config-profiles" organizationId={organizationId}
    integrationId={integrationId} title={text.tabs.profiles} emptyTitle={text.emptyProfiles}
    head={<tr><th>{text.name}</th><th>{text.inbounds}</th><th>{text.nodes}</th><th>{text.updated}</th>
      <th>{c.management}</th><th>{c.status}</th><th>{text.actions}</th></tr>}
    row={item => <tr key={item.id} className={item.active ? undefined : 'row-quiet'}>
      <td><strong>{item.displayName}</strong>{!item.active ? <><StatusIndicator label={text.gone} /><small className="integration-last-seen">{text.lastSeen}: {i18n.format.dateTime(item.lastSeenAt)}</small></> : null}</td>
      <td>{item.summary.inbounds.length === 0 ? '—' : item.summary.inbounds.map(inbound =>
        [inbound.tag, inbound.type, inbound.port].filter(value => value !== null).join(' ')).join(', ')}</td>
      <td>{item.summary.nodeUuids.length}</td>
      <td>{i18n.format.dateTime(item.summary.updatedAt)}</td>
      <ProfileManagementCell organizationId={organizationId} integrationId={integrationId} item={item}
        enabled={canManageConfig} />
      <td><InventoryArchiveControl organizationId={organizationId} integrationId={integrationId} item={item} /></td>
    </tr>} />
}

function ProfileManagementCell({ organizationId, integrationId, item, enabled }: {
  organizationId: string; integrationId: string; item: InventoryObject<RemnawaveConfigProfileSummary>; enabled: boolean
}) {
  const i18n = useI18n(); const c = i18n.t.integrationConfig
  const location = useLocation()
  const managed = item.configManagement
  const errorText = useSyncErrorText()
  const adopt = useAdoptConfigProfile(organizationId, integrationId, item.id)
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const page = `/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(integrationId)}` +
    `/config-profiles/${encodeURIComponent(item.id)}${contextSearch(new URLSearchParams(location.search))}`
  const start = () => {
    setCode(item.displayName.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 64))
    setName(item.displayName); setDescription(''); setOpen(true)
  }
  return <><td>{!enabled ? '—' : managed ?
    <><span>{managed.name} · {c.revision} {managed.revisionNumber}</span>{' '}
      <Link to={page}>{c.open}</Link></> :
    <><span>{c.notManaged}</span>{' '}
      {item.active ? <button className="secondary-button" type="button" onClick={start}>
        {c.adopt}</button> : null}</>}
    </td>
    <td>{managed ? <StatusIndicator label={c.statuses[managed.status]} tone={managed.status === 'REMOTE_DRIFT' ? 'warning' : managed.status === 'DEPLOYMENT_FAILED' ? 'danger' : managed.status === 'IN_SYNC' ? 'success' : 'neutral'} /> : '—'}</td>
    {open ? <IntegrationDialog title={c.adoptRemnawaveProfile} onClose={() => setOpen(false)} busy={adopt.isPending}
      actions={<><button className="secondary-button" type="button" onClick={() => setOpen(false)}>
        {i18n.t.common.cancel}</button><button className="primary-button" type="button"
        disabled={adopt.isPending || !code.trim() || !name.trim()}
        onClick={() => adopt.mutate({ code, name, description: description || null }, { onSuccess: () => setOpen(false) })}>
        {c.adopt}</button></>}><div><p>{item.displayName}</p>
        <label className="field">{c.infradeskCode}<input value={code} onChange={event => setCode(event.target.value)} /></label>
        <label className="field">{c.name}<input value={name} onChange={event => setName(event.target.value)} /></label>
        <label className="field">{c.description}<input value={description}
          onChange={event => setDescription(event.target.value)} /></label>
        <p className="muted-copy">{c.adoptDetail}</p>
        {adopt.isError ? <InlineAlert tone="danger" title={(adopt.error instanceof ApiError ? errorText(adopt.error.code) : describeIntegrationError(adopt.error, i18n)) ?? undefined} /> : null}</div>
</IntegrationDialog> : null}
  </>
}

function HistoryTab({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const sessions = useIntegrationSyncSessions(organizationId, integrationId, true)
  const errorText = useSyncErrorText()
  const took = (session: IntegrationSyncSession) => session.finishedAt === null ? null
    : Math.max(0, Math.round((Date.parse(session.finishedAt) - Date.parse(session.startedAt)) / 1000))
  return <WorkspaceSection title={t.tabs.history}>
    {sessions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {sessions.isError ? sessions.data && !isUnavailableError(sessions.error) ? <RefreshWarning updatedAt={sessions.dataUpdatedAt} retry={() => sessions.refetch()} /> : <LoadError error={sessions.error} retry={() => sessions.refetch()} /> : null}
    {!isUnavailableError(sessions.error) && sessions.data?.length === 0 ? <EmptyWorkspaceState compact title={t.emptyHistory} detail={t.emptyDetail} /> : null}
    {!isUnavailableError(sessions.error) && sessions.data?.length ? <div className="table-scroll"><table className="data-grid integration-grid">
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
    {describeIntegrationError(error, i18n)}</InlineAlert>
}
