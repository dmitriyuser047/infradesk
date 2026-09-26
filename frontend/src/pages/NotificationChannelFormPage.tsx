import { type FormEvent, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/httpClient'
import { useNotificationChannel, useSaveNotificationChannel } from '../api/notificationChannels'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, PageLoading, PageUnavailable, WorkspaceHeader } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { EmailSecurity, IncidentReason, NotificationChannelResponse, NotificationChannelType, NotificationEventType, SaveNotificationChannelRequest } from '../types/notificationChannel'
import { InvalidRoutePage } from './InvalidRoutePage'

export function NotificationChannelFormPage() {
  const { organizationId, channelId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <ChannelFormLoader organizationId={organizationId} channelId={channelId} />
}

function ChannelFormLoader({ organizationId, channelId }: { organizationId: string; channelId?: string }) {
  const i18n = useI18n(); const t = i18n.t.notifications; const location = useLocation()
  const permissions = useOrganizationPermissions(organizationId)
  const query = useNotificationChannel(organizationId, channelId, permissions.can('manageNotifications'))
  const back = `/organizations/${encodeURIComponent(organizationId)}/notifications${location.search}`
  if (!permissions.can('manageNotifications')) return <AppShell><InlineAlert tone="danger" title={t.accessDenied} /></AppShell>
  if (channelId && query.isPending) return <AppShell><PageLoading title={t.editTitle} label={t.loading} back={{ label: t.back, to: back }} /></AppShell>
  if (channelId && (query.isError || !query.data)) return <AppShell><PageUnavailable back={{ label: t.back, to: back }} onRetry={() => query.refetch()} error={query.error}
    notFound={query.error instanceof ApiError && query.error.code === 'NOTIFICATION_CHANNEL_NOT_FOUND'} notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  return <ChannelForm key={channelId ?? 'new'} org={organizationId} existing={query.data} />
}

function ChannelForm({ org, existing }: { org: string; existing?: NotificationChannelResponse }) {
  const i18n = useI18n(); const t = i18n.t.notifications; const navigate = useNavigate(); const location = useLocation()
  const save = useSaveNotificationChannel(org, existing?.id)
  const [name, setName] = useState(existing?.name ?? '')
  const [type, setType] = useState<NotificationChannelType>(existing?.type ?? 'WEBHOOK')
  const [url, setUrl] = useState(''); const [chatId, setChatId] = useState(existing?.config && 'chatId' in existing.config ? existing.config.chatId : '')
  const [botToken, setBotToken] = useState(''); const [smtpHost, setSmtpHost] = useState(existing?.config && 'smtpHost' in existing.config ? existing.config.smtpHost : '')
  const [smtpPort, setSmtpPort] = useState(String(existing?.config && 'smtpPort' in existing.config ? existing.config.smtpPort : 587))
  const [security, setSecurity] = useState<EmailSecurity>(existing?.config && 'security' in existing.config ? existing.config.security : 'STARTTLS')
  const [username, setUsername] = useState(existing?.config && 'username' in existing.config ? existing.config.username : '')
  const [password, setPassword] = useState(''); const [fromAddress, setFromAddress] = useState(existing?.config && 'fromAddress' in existing.config ? existing.config.fromAddress : '')
  const [recipients, setRecipients] = useState(existing?.config && 'recipients' in existing.config ? existing.config.recipients.join('\n') : '')
  const [events, setEvents] = useState<NotificationEventType[]>(existing?.events ?? ['INCIDENT_OPENED'])
  const [reasons, setReasons] = useState<IncidentReason[]>(existing?.reasons ?? ['THRESHOLD'])
  const [enabled, setEnabled] = useState(true); const [error, setError] = useState('')
  const typeChanged = Boolean(existing && existing.type !== type)
  const configured = existing?.config.credentialConfigured ?? false
  const title = existing ? t.editTitle : t.createTitle
  const back = `/organizations/${encodeURIComponent(org)}/notifications${location.search}`
  const toggle = <T extends string>(items: T[], value: T, set: (next: T[]) => void) => set(items.includes(value) ? items.filter(item => item !== value) : [...items, value])
  function submit(event: FormEvent) {
    event.preventDefault(); if (save.isPending) return; setError('')
    if (!name.trim()) return setError(t.nameRequired)
    if (!events.length) return setError(t.eventsRequired)
    if (!reasons.length) return setError(t.reasonsRequired)
    if (type === 'WEBHOOK') {
      if ((typeChanged || !existing) && !url.trim()) return setError(t.secretRequired)
      if (url.trim()) { try { const parsed = new URL(url); if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error() } catch { return setError(t.invalidUrl) } }
    }
    if (type === 'TELEGRAM' && (!chatId.trim() || ((typeChanged || !existing) && !botToken))) return setError(t.configRequired)
    const emailList = recipients.split(/[\n,;]+/).map(item => item.trim()).filter(Boolean)
    if (type === 'EMAIL') {
      if (!smtpHost.trim() || !Number.isInteger(Number(smtpPort)) || Number(smtpPort) < 1 || Number(smtpPort) > 65535 || !username.trim() || !fromAddress.trim() || !emailList.length ||
        ((typeChanged || !existing) && !password)) return setError(t.configRequired)
      if (![fromAddress, ...emailList].every(value => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value))) return setError(t.invalidEmail)
    }
    const body: SaveNotificationChannelRequest = { name: name.trim(), type, events, reasons }
    if (!existing) body.enabled = enabled
    if (type === 'WEBHOOK') body.webhook = url ? { url } : {}
    if (type === 'TELEGRAM') body.telegram = { chatId: chatId.trim(), ...(botToken ? { botToken } : {}) }
    if (type === 'EMAIL') body.email = { smtpHost: smtpHost.trim(), smtpPort: Number(smtpPort), security, username: username.trim(), ...(password ? { password } : {}), fromAddress: fromAddress.trim(), recipients: emailList }
    save.submit(body, { onSuccess: value => navigate(`/organizations/${encodeURIComponent(org)}/notifications${location.search}`, { replace: true, state: { saved: value.id } }), onError: failure => setError(describeError(failure, i18n)) })
  }
  const eventOptions: [NotificationEventType, string][] = [['INCIDENT_OPENED', t.incidentOpened], ['INCIDENT_RESOLVED', t.incidentResolved]]
  const reasonOptions: [IncidentReason, string][] = [['THRESHOLD', t.threshold], ['NO_DATA', t.noData]]
  return <AppShell><div className="workspace-page notification-form-page">
    <WorkspaceHeader title={title} actions={<Link className="text-link" to={back}>{t.back}</Link>} />
    <form className="workspace-section notification-form" onSubmit={submit} noValidate>
      <div className="notification-form-grid">
        <label>{t.name}<input required value={name} onChange={event => setName(event.target.value)} autoComplete="off" /></label>
        <label>{t.type}<select value={type} onChange={event => setType(event.target.value as NotificationChannelType)}><option value="WEBHOOK">{t.webhook}</option><option value="TELEGRAM">{t.telegram}</option><option value="EMAIL">{t.email}</option></select></label>
      </div>
      {type === 'WEBHOOK' ? <section className="notification-config"><h2>{t.webhook}</h2><label>{t.url}<input type="password" autoComplete="new-password" value={url} onChange={event => setUrl(event.target.value)} aria-describedby="webhook-help" /></label>
        <p id="webhook-help" className="field-hint">{existing && configured && !typeChanged ? t.secretKeep : t.urlHelper}</p>{typeChanged ? <p className="field-hint">{t.secretRequired}</p> : null}</section> : null}
      {type === 'TELEGRAM' ? <section className="notification-config"><h2>{t.telegram}</h2><label>{t.chatId}<input value={chatId} onChange={event => setChatId(event.target.value)} /></label>
        <SecretInput label={t.botToken} value={botToken} onChange={setBotToken} helper={existing && configured && !typeChanged ? t.secretKeep : t.secretRequired} /></section> : null}
      {type === 'EMAIL' ? <section className="notification-config"><h2>{t.email}</h2><div className="notification-form-grid"><label>{t.smtpHost}<input value={smtpHost} onChange={event => setSmtpHost(event.target.value)} /></label>
        <label>{t.smtpPort}<input type="number" min="1" max="65535" value={smtpPort} onChange={event => setSmtpPort(event.target.value)} /></label>
        <label>{t.security}<select value={security} onChange={event => setSecurity(event.target.value as EmailSecurity)}>{(['NONE', 'STARTTLS', 'TLS'] as const).map(mode => <option key={mode} value={mode}>{t.securityModes[mode]}</option>)}</select></label>
        <label>{t.username}<input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" /></label>
        <label>{t.fromAddress}<input type="email" value={fromAddress} onChange={event => setFromAddress(event.target.value)} /></label></div>
        <SecretInput label={t.password} value={password} onChange={setPassword} helper={existing && configured && !typeChanged ? t.secretKeep : t.secretRequired} />
        <label>{t.recipients}<textarea rows={4} value={recipients} onChange={event => setRecipients(event.target.value)} aria-describedby="recipients-help" /></label><p id="recipients-help" className="field-hint">{t.recipientsHelp}</p>
      </section> : null}
      <fieldset><legend>{t.events}</legend>{eventOptions.map(([value, label]) => <label className="notification-check" key={value}><input type="checkbox" checked={events.includes(value)} onChange={() => toggle(events, value, setEvents)} />{label}</label>)}</fieldset>
      <fieldset><legend>{t.reasons}</legend>{reasonOptions.map(([value, label]) => <label className="notification-check" key={value}><input type="checkbox" checked={reasons.includes(value)} onChange={() => toggle(reasons, value, setReasons)} />{label}</label>)}</fieldset>
      {!existing ? <label className="notification-check"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} />{t.enabledAtCreate}</label> : null}
      {error ? <InlineAlert tone="danger" title={error} /> : null}
      <div className="notification-form-actions"><Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link><button className="primary-button" type="submit" disabled={save.isPending}>{save.isPending ? t.saving : t.save}</button></div>
    </form>
  </div></AppShell>
}

function SecretInput({ label, value, onChange, helper }: { label: string; value: string; onChange: (value: string) => void; helper: string }) {
  const [visible, setVisible] = useState(false)
  const i18n = useI18n()
  return <div className="notification-secret"><label>{label}<input type={visible ? 'text' : 'password'} autoComplete="new-password" value={value} onChange={event => onChange(event.target.value)} aria-describedby={`${label}-help`} /></label>
    <button type="button" className="text-button" onClick={() => setVisible(show => !show)}>{visible ? i18n.t.notifications.hideSecret : i18n.t.notifications.showSecret} {label}</button><p id={`${label}-help`} className="field-hint">{helper}</p></div>
}
