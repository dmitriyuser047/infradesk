import { Link, useLocation, useParams } from 'react-router-dom'
import { Bell, Plus } from 'lucide-react'
import { useNotificationChannels, useSetNotificationChannelEnabled, useTestNotificationChannel } from '../api/notificationChannels'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { NotificationChannelResponse } from '../types/notificationChannel'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/notifications.css'

export function NotificationChannelsPage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <NotificationChannelsContent organizationId={organizationId} />
}

function NotificationChannelsContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n(); const t = i18n.t.notifications; const location = useLocation()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageNotifications')
  const query = useNotificationChannels(organizationId, canManage)
  const lifecycle = useSetNotificationChannelEnabled(organizationId)
  const test = useTestNotificationChannel(organizationId)
  const createPath = `/organizations/${encodeURIComponent(organizationId)}/notifications/new${location.search}`
  const label = (type: string) => type === 'WEBHOOK' ? t.webhook : type === 'TELEGRAM' ? t.telegram : t.email
  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} actions={canManage ? <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.add}</Link> : null} />
    {permissions.isPending ? <div className="notification-loading" role="status">{t.loadingPermissions}</div> : null}
    {!permissions.isPending && !canManage ? <InlineAlert tone="danger" title={t.accessDenied} /> : null}
    {canManage && query.isPending ? <div className="notification-loading" role="status">{t.loading}</div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.loadError} action={<button className="secondary-button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>{describeError(query.error, i18n)}</InlineAlert> : null}
    {query.data?.length === 0 ? <EmptyWorkspaceState icon={Bell} title={t.empty} detail={t.emptyDetail} action={canManage ? <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.add}</Link> : undefined} /> : null}
    {query.data?.length ? <WorkspaceSection title={t.section} actions={<span className="resource-count">{query.data.length}</span>}>
      <div className="notification-list">{query.data.map(channel => <ChannelCard key={channel.id} channel={channel} org={organizationId} canManage={canManage}
        label={label(channel.type)} lifecycle={lifecycle} test={test} />)}</div>
    </WorkspaceSection> : null}
  </div></AppShell>
}

function ChannelCard({ channel, org, canManage, label, lifecycle, test }: {
  channel: NotificationChannelResponse; org: string; canManage: boolean; label: string
  lifecycle: ReturnType<typeof useSetNotificationChannelEnabled>; test: ReturnType<typeof useTestNotificationChannel>
}) {
  const i18n = useI18n(); const t = i18n.t.notifications; const location = useLocation()
  const formPath = `/organizations/${encodeURIComponent(org)}/notifications/${encodeURIComponent(channel.id)}/edit${location.search}`
  const events = channel.events.map(code => code === 'INCIDENT_OPENED' ? t.incidentOpened : t.incidentResolved)
  const reasons = channel.reasons.map(code => code === 'THRESHOLD' ? t.threshold : t.noData)
  const config = channel.config
  const testResult = test.variables === channel.id && test.isSuccess ? test.data : undefined
  const testError = test.variables === channel.id && test.isError
  const testText = testResult?.status === 'SENT' ? t.testSent : testResult?.status === 'RETRYABLE_FAILURE' ? t.testRetry : testResult?.status === 'PERMANENT_FAILURE' ? t.testPermanent : undefined
  const safeCode = testResult?.code ? describeTestCode(testResult.code, t.testCodes) : undefined
  return <article className="notification-card">
    <div className="notification-card-heading"><div><h3>{channel.name}</h3><span className="notification-type">{label}</span></div>
      <span className={`status-indicator ${channel.enabled ? 'status-success' : 'status-muted'}`}>{channel.enabled ? t.enabled : t.disabled}</span></div>
    <dl className="notification-properties">
      <div><dt>{t.events}</dt><dd>{events.join(', ')}</dd></div><div><dt>{t.reasons}</dt><dd>{reasons.join(', ')}</dd></div>
      {channel.type === 'WEBHOOK' ? <div><dt>{t.webhook}</dt><dd>{t.endpointSecure}</dd></div> : null}
      {channel.type === 'TELEGRAM' && 'chatId' in config ? <div><dt>{t.chat}</dt><dd className="break-anywhere">{config.chatId}</dd></div> : null}
      {channel.type === 'EMAIL' && 'smtpHost' in config ? <><div><dt>{t.smtp}</dt><dd className="break-anywhere">{config.smtpHost}:{config.smtpPort} · {config.security}</dd></div>
        <div><dt>{t.from}</dt><dd className="break-anywhere">{config.fromAddress}</dd></div><div><dt>{t.recipients}</dt><dd className="break-anywhere">{config.recipients.join(', ')}</dd></div></> : null}
      <div><dt>{t.credentialConfigured}</dt><dd>{config.credentialConfigured ? t.credentialConfigured : t.credentialMissing}</dd></div>
    </dl>
    {canManage ? <div className="notification-actions">
      <Link className="secondary-button" to={formPath}>{t.edit}</Link>
      <button className="secondary-button" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate({ id: channel.id, enabled: !channel.enabled })}>{channel.enabled ? t.disable : t.enable}</button>
      <button className="secondary-button" disabled={test.isPending} onClick={() => test.submit(channel.id)}>{test.isPending && test.variables === channel.id ? t.sending : t.sendTest}</button>
    </div> : null}
    {testText || testError ? <p className={`notification-test-result ${testError || testResult?.status !== 'SENT' ? 'notification-test-error' : ''}`} role="status">
      {testText ?? t.testError}{safeCode ? ` ${safeCode}` : ''}
    </p> : null}
    {lifecycle.isError && lifecycle.variables?.id === channel.id ? <p className="notification-test-error" role="alert">{describeError(lifecycle.error, i18n)}</p> : null}
  </article>
}

function describeTestCode(code: string, messages: Record<string, string>): string | undefined {
  if (messages[code]) return messages[code]
  if (/^HTTP_4\d\d$/.test(code)) return messages.HTTP_4XX
  if (/^HTTP_5\d\d$/.test(code)) return messages.HTTP_5XX
  return undefined
}
