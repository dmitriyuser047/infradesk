import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { Plug, Plus, Search, CirclePause, RefreshCw, TriangleAlert, SearchX } from 'lucide-react'
import { ApiError } from '../api/httpClient'
import { useDeleteIntegration, useIntegration, useIntegrationProviders, useIntegrations,
  useSaveIntegration, useSetIntegrationEnabled, useTestIntegration } from '../api/integrations'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceFormSection, WorkspaceHeader,
  WorkspaceSection, WorkspaceMetrics } from '../components/layout/WorkspacePrimitives'
import { PageActionMenu } from '../components/layout/PageActionMenu'
import { RefreshWarning, isUnavailableError } from '../components/layout/RefreshWarning'
import { IntegrationDialog } from '../components/integrations/IntegrationDialog'
import { connectionHealth, sessionTones } from '../components/integrations/integrationPresentation'
import { StatusIndicator } from '../components/layout/WorkspacePrimitives'
import '../styles/pages/integrations.css'
import { useI18n } from '../i18n'
import { describeIntegrationError } from '../components/integrations/integrationPresentation'
import type { IntegrationResponse } from '../types/integration'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/notifications.css'

function useIntegrationError(error: unknown): string | undefined {
  const { t } = useI18n()
  if (!(error instanceof ApiError)) return undefined
  return t.integrations.errors[error.code as keyof typeof t.integrations.errors]
}

export function IntegrationsPage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <IntegrationList organizationId={organizationId} />
}

function IntegrationList({ organizationId }: { organizationId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrations; const inventory = i18n.t.integrationInventory
  const location = useLocation()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageIntegrations')
  const query = useIntegrations(organizationId, canManage)
  const lifecycle = useSetIntegrationEnabled(organizationId)
  const test = useTestIntegration(organizationId)
  const remove = useDeleteIntegration(organizationId)
  const [confirmation, setConfirmation] = useState<{ item: IntegrationResponse; kind: 'delete' | 'disable' } | null>(null)
  const ui = i18n.t.integrationUi
  const createPath = `/organizations/${encodeURIComponent(organizationId)}/integrations/new${location.search}`
  const testError = useIntegrationError(test.error)
  const [search, setSearch] = useState('')
  const [presence, setPresence] = useState('ALL')
  const items = query.data?.filter(item => (presence === 'ALL' || item.enabled === (presence === 'ENABLED')) &&
    `${item.name} ${item.baseUrl}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? []
  return <AppShell><div className="workspace-page work-page integration-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} actions={canManage ?
      <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.add}</Link> : undefined} />
    {canManage && query.data && !isUnavailableError(query.error) ? <WorkspaceMetrics items={[
      { label: i18n.t.design.configuredIntegrations, value: query.data.length, icon: Plug, detail: i18n.t.design.listSnapshot },
      { label: i18n.t.design.syncEnabled, value: query.data.filter(item => item.enabled).length, icon: RefreshCw,
        onSelect: () => { setSearch(''); setPresence('ENABLED') } },
      { label: i18n.t.design.syncPaused, value: query.data.filter(item => !item.enabled).length, icon: CirclePause,
        onSelect: () => { setSearch(''); setPresence('DISABLED') } },
      { label: i18n.t.design.knownSyncFailures, value: query.data.filter(item => item.overview?.lastSync?.status === 'FAILED').length,
        icon: TriangleAlert, detail: i18n.t.design.latestAttempts },
    ]} /> : null}
    {permissions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {!permissions.isPending && !canManage ? <InlineAlert tone="danger" title={t.accessDenied} /> : null}
    {canManage && query.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
    {query.isError && (!query.data || isUnavailableError(query.error)) ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeIntegrationError(query.error, i18n)}</InlineAlert> : null}
    {query.isError && query.data && !isUnavailableError(query.error) ? <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => query.refetch()} /> : null}
    {canManage && !isUnavailableError(query.error) && query.data?.length === 0 ? <EmptyWorkspaceState icon={Plug} title={t.empty} detail={t.emptyDetail}
      action={<Link className="primary-button" to={createPath}>{t.add}</Link>} /> : null}
    {canManage && !isUnavailableError(query.error) && query.data?.length ? <WorkspaceSection title={t.section}>
      <div className="filter-bar list-filter-bar">
        <div className="search-field"><Search size={16} aria-hidden className="search-field-icon" />
          <input type="search" aria-label={i18n.t.design.integrationSearch} placeholder={i18n.t.design.integrationSearch}
            value={search} onChange={event => setSearch(event.target.value)} /></div>
        <label>{i18n.t.common.status}<select value={presence} onChange={event => setPresence(event.target.value)}>
          <option value="ALL">{i18n.t.common.all}</option><option value="ENABLED">{i18n.t.design.onlyEnabled}</option>
          <option value="DISABLED">{i18n.t.design.onlyDisabled}</option></select></label>
        {search || presence !== 'ALL' ? <button className="text-button" type="button" onClick={() => { setSearch(''); setPresence('ALL') }}>{ui.resetFilters}</button> : null}
        <span className="resource-count" role="status">{i18n.t.common.shown(items.length, query.data.length)}</span>
      </div>
      {!items.length ? <EmptyWorkspaceState compact icon={SearchX} title={i18n.t.resources.filter.noResults}
        detail={i18n.t.resources.filter.noResultsDetail} /> : null}
      <div className="notification-list integration-list integration-card-grid">{items.map(item => {
        const health = connectionHealth(test.variables === item.id && test.isSuccess ? test.data.ok : undefined,
          test.variables === item.id && test.error instanceof ApiError ? test.error.code : undefined)
        const counts = item.overview?.inventory
        const inventorySummary = inventory.inventorySummary(counts?.nodes?.active, counts?.hosts?.active, counts?.configProfiles?.active)
        return <article className="notification-card integration-card" key={item.id}>
        <div className="notification-card-heading"><div className="integration-card-identity"><span className="integration-provider-mark"><Plug size={20} aria-hidden /></span><h3><Link to={`/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(item.id)}${location.search}`}>
          {item.name}</Link></h3></div>
          <StatusIndicator label={item.enabled ? t.enabled : t.disabled} /></div>
        <p className="integration-provider-url"><span>Remnawave</span> · <span className="break-anywhere">{item.baseUrl}</span></p>
        <div className="integration-card-statuses">
          <span>{ui.connection}: <StatusIndicator label={ui[health]} tone={health === "available" ? "success" : health === "unavailable" ? "danger" : "neutral"} /></span>
          <span>{inventory.lastSync}: {item.overview?.lastSync ? <>
            <StatusIndicator label={inventory.status[item.overview.lastSync.status]} tone={sessionTones[item.overview.lastSync.status]} />
            <span className="integration-sync-when">{i18n.format.relative(item.overview.lastSync.startedAt)}</span>
          </> : inventory.never}</span>
        </div>
        {inventorySummary ? <p className="integration-overview-line">{inventorySummary}</p> : null}
        <div className="notification-actions">
          <Link className="primary-button" to={`/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(item.id)}${location.search}`}>{inventory.open}</Link>
          <PageActionMenu actions={[
            { label: t.test, disabled: test.isPending, onSelect: () => test.mutate(item.id) },
            { label: t.edit, to: `/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(item.id)}/edit${location.search}` },
            { label: item.enabled ? t.disable : t.enable, disabled: lifecycle.isPending,
              onSelect: () => item.enabled ? setConfirmation({ item, kind: 'disable' }) : lifecycle.mutate({ id: item.id, enabled: true }) },
            { label: t.delete, danger: true, disabled: remove.isPending, onSelect: () => setConfirmation({ item, kind: 'delete' }) },
          ]} />
        </div>
        {test.variables === item.id && test.isSuccess ? <InlineAlert tone={test.data.ok ? "success" : "danger"} title={test.data.ok ? t.testSuccess : t.testError}>{test.data.ok ? ui.testDetail : null}</InlineAlert> : null}
        {test.variables === item.id && test.isError ? <InlineAlert tone="danger" title={t.testError}>
          {testError ?? describeIntegrationError(test.error, i18n)}</InlineAlert> : null}
        {lifecycle.isError && lifecycle.variables?.id === item.id ? <InlineAlert tone="danger" title={t.actionError}>
          {describeIntegrationError(lifecycle.error, i18n)}</InlineAlert> : null}
        {remove.isError && remove.variables === item.id ? <InlineAlert tone="danger" title={t.deleteError}>
          {describeIntegrationError(remove.error, i18n)}</InlineAlert> : null}
      </article>})}</div>
    </WorkspaceSection> : null}
    {confirmation ? <IntegrationDialog title={confirmation.kind === 'delete' ? ui.deleteTitle : ui.disableTitle}
      onClose={() => setConfirmation(null)} busy={remove.isPending || lifecycle.isPending}
      actions={<><button className="secondary-button" onClick={() => setConfirmation(null)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" onClick={() => {
          if (confirmation.kind === 'delete') remove.mutate(confirmation.item.id, { onSuccess: () => setConfirmation(null) })
          else lifecycle.mutate({ id: confirmation.item.id, enabled: false }, { onSuccess: () => setConfirmation(null) })
        }} disabled={remove.isPending || lifecycle.isPending}>{confirmation.kind === 'delete' ? t.confirmDelete : t.disable}</button></>}>
      <strong>{confirmation.item.name}</strong><p>{confirmation.kind === 'delete' ? ui.deleteDetail : ui.disableDetail}</p>
      {remove.isError || lifecycle.isError ? <InlineAlert tone="danger" title={describeIntegrationError(remove.error ?? lifecycle.error, i18n)} /> : null}
    </IntegrationDialog> : null}
  </div></AppShell>
}

export function IntegrationFormPage() {
  const { organizationId, integrationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <IntegrationForm organizationId={organizationId} integrationId={integrationId} />
}

function IntegrationForm({ organizationId, integrationId }: { organizationId: string; integrationId?: string }) {
  const i18n = useI18n(); const t = i18n.t.integrations
  const location = useLocation(); const navigate = useNavigate()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageIntegrations')
  const providers = useIntegrationProviders(organizationId, canManage)
  const query = useIntegration(organizationId, integrationId, canManage)
  const save = useSaveIntegration(organizationId, integrationId)
  const back = `/organizations/${encodeURIComponent(organizationId)}/integrations${location.search}`
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiToken, setApiToken] = useState('')
  const [caddyApiKey, setCaddyApiKey] = useState('')
  const [replace, setReplace] = useState(false)
  const [loadedId, setLoadedId] = useState<string | undefined>()
  const stored: IntegrationResponse | undefined = query.data
  if (stored && loadedId !== stored.id) {
    setLoadedId(stored.id); setName(stored.name); setBaseUrl(stored.baseUrl)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const credentials = { apiToken, caddyApiKey: caddyApiKey || null }
    const body = integrationId ? { name, baseUrl, ...(replace ? { credentials } : {}) }
      : { name, providerType: 'REMNAWAVE' as const, baseUrl, credentials }
    save.submit(body, () => navigate(back))
    setApiToken(''); setCaddyApiKey('')
  }
  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={integrationId ? t.editTitle : t.createTitle} back={{ label: t.title, to: back }} />
    {permissions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {!permissions.isPending && !canManage ? <InlineAlert tone="danger" title={t.accessDenied} /> : null}
    {canManage && integrationId && query.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.loadError} action={<button className="secondary-button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {canManage && (!integrationId || stored) ? <WorkspaceFormSection title={integrationId ? t.editTitle : t.createTitle} onSubmit={submit}>
      <label className="field">{t.name}<input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
      <label className="field">{t.provider}<select value="REMNAWAVE" disabled><option value="REMNAWAVE">
        {providers.data?.find(provider => provider.type === 'REMNAWAVE')?.displayName ?? 'Remnawave'}</option></select></label>
      <label className="field">{t.baseUrl}<input required type="url" maxLength={2048} value={baseUrl}
        onChange={event => setBaseUrl(event.target.value)} placeholder="https://panel.example.com" /></label>
      {integrationId ? <><p>{t.configured}{stored?.credential.caddyApiKeyConfigured ? ` · ${t.caddyConfigured}` : ''}</p>
        <button className="secondary-button" type="button" onClick={() => setReplace(!replace)}>{t.replaceCredentials}</button></> :
        <p>{t.createDisabled}</p>}
      {(!integrationId || replace) ? <>
        <label className="field">{t.apiToken}<input type="password" required autoComplete="off" value={apiToken}
          onChange={event => setApiToken(event.target.value)} /></label>
        <label className="field">{t.caddyApiKey}<input type="password" autoComplete="off" value={caddyApiKey}
          onChange={event => setCaddyApiKey(event.target.value)} /></label>
        <p>{t.caddyApiKeyHint}</p>
        <p>{t.credentialHint}</p>
      </> : null}
      {save.isError ? <InlineAlert tone="danger" title={t.saveError}>{describeIntegrationError(save.error, i18n)}</InlineAlert> : null}
      <div className="notification-form-actions"><button className="primary-button" type="submit" disabled={save.isPending || providers.isPending}>
        {save.isPending ? t.saving : t.save}</button><Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link></div>
    </WorkspaceFormSection> : null}
  </div></AppShell>
}
