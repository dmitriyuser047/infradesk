import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { Plug, Plus } from 'lucide-react'
import { ApiError } from '../api/httpClient'
import { useDeleteIntegration, useIntegration, useIntegrationProviders, useIntegrations,
  useSaveIntegration, useSetIntegrationEnabled, useTestIntegration } from '../api/integrations'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceFormSection, WorkspaceHeader,
  WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
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
  const i18n = useI18n(); const t = i18n.t.integrations
  const location = useLocation()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageIntegrations')
  const query = useIntegrations(organizationId, canManage)
  const lifecycle = useSetIntegrationEnabled(organizationId)
  const test = useTestIntegration(organizationId)
  const remove = useDeleteIntegration(organizationId)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const createPath = `/organizations/${encodeURIComponent(organizationId)}/integrations/new${location.search}`
  const testError = useIntegrationError(test.error)
  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} actions={canManage ?
      <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.add}</Link> : undefined} />
    {permissions.isPending ? <p role="status">{i18n.t.common.loading}</p> : null}
    {!permissions.isPending && !canManage ? <InlineAlert tone="danger" title={t.accessDenied} /> : null}
    {canManage && query.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /><span /></div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(query.error, i18n)}</InlineAlert> : null}
    {query.data?.length === 0 ? <EmptyWorkspaceState icon={Plug} title={t.empty} detail={t.emptyDetail}
      action={<Link className="primary-button" to={createPath}>{t.add}</Link>} /> : null}
    {query.data?.length ? <WorkspaceSection title={t.section}>
      <div className="notification-list">{query.data.map(item => <article className="notification-card" key={item.id}>
        <div className="notification-card-heading"><div><h3>{item.name}</h3><span className="notification-type">Remnawave</span></div>
          <span className={`status-indicator ${item.enabled ? 'status-success' : 'status-muted'}`}>{item.enabled ? t.enabled : t.disabled}</span></div>
        <dl className="notification-properties">
          <div><dt>{t.provider}</dt><dd>Remnawave</dd></div>
          <div><dt>{t.baseUrl}</dt><dd className="break-anywhere">{item.baseUrl}</dd></div>
          <div><dt>{t.credential}</dt><dd>{item.credential.apiTokenConfigured ? t.configured : t.missing}
            {item.credential.caddyApiKeyConfigured ? ` · ${t.caddyConfigured}` : ''}</dd></div>
        </dl>
        <div className="notification-actions">
          <Link className="secondary-button" to={`/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(item.id)}/edit${location.search}`}>{t.edit}</Link>
          <button className="secondary-button" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate({ id: item.id, enabled: !item.enabled })}>
            {item.enabled ? t.disable : t.enable}</button>
          <button className="secondary-button" disabled={test.isPending} onClick={() => test.mutate(item.id)}>
            {test.isPending && test.variables === item.id ? t.testing : t.test}</button>
          <button className="secondary-button" disabled={remove.isPending} onClick={() => {
            if (confirmDelete === item.id) { remove.mutate(item.id); setConfirmDelete(null) }
            else setConfirmDelete(item.id)
          }}>{confirmDelete === item.id ? t.confirmDelete : t.delete}</button>
        </div>
        {test.variables === item.id && test.isSuccess ? <InlineAlert tone="success" title={t.testSuccess} /> : null}
        {test.variables === item.id && test.isError ? <InlineAlert tone="danger" title={t.testError}>
          {testError ?? describeError(test.error, i18n)}</InlineAlert> : null}
        {lifecycle.isError && lifecycle.variables?.id === item.id ? <InlineAlert tone="danger" title={t.actionError}>
          {describeError(lifecycle.error, i18n)}</InlineAlert> : null}
        {remove.isError && remove.variables === item.id ? <InlineAlert tone="danger" title={t.deleteError}>
          {describeError(remove.error, i18n)}</InlineAlert> : null}
      </article>)}</div>
    </WorkspaceSection> : null}
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
        <p>{t.credentialHint}</p>
      </> : null}
      {save.isError ? <InlineAlert tone="danger" title={t.saveError}>{describeError(save.error, i18n)}</InlineAlert> : null}
      <div className="notification-form-actions"><button className="primary-button" type="submit" disabled={save.isPending || providers.isPending}>
        {save.isPending ? t.saving : t.save}</button><Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link></div>
    </WorkspaceFormSection> : null}
  </div></AppShell>
}
