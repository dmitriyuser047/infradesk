import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'

import {
  useChangePassword,
  useRevokeAllSessions,
  useRevokeOtherSessions,
  useRevokeSession,
  useSessions,
  useSecurityEvents,
  useUpdateDisplayName,
} from '../api/account'
import { useMe, useMyOrganizations } from '../api/auth'
import { describeError } from '../i18n/errors'
import { useI18n } from '../i18n'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import type { MyOrganizationResponse } from '../types/auth'

/** The signed-in user's account: their profile, their organizations, and changing their password.
 *
 * The identity comes from the caches the shell already loaded (`me`, `my-organizations`), so the
 * page adds no duplicate request. The email is shown read-only — it is the login identity and has
 * no change flow yet. Passwords live only in this component's local state while the form is open. */
export function AccountPage() {
  const i18n = useI18n()
  const t = i18n.t.account
  const me = useMe()
  const organizations = useMyOrganizations()

  return (
    <main className="workspace-page">
      <WorkspaceHeader title={t.title} subtitle={t.subtitle} />
      <AccountSummary displayName={me.data?.displayName ?? ''} email={me.data?.email ?? ''}
        organizationCount={organizations.data?.length ?? 0} />
      <div className="account-settings-grid">
        <ProfileSection displayName={me.data?.displayName ?? ''} email={me.data?.email ?? ''}
          organizations={organizations.data ?? []} />
        <PasswordSection />
      </div>
      <SessionsSection />
      <SecurityEventsSection />
    </main>
  )
}

function SecurityEventsSection() {
  const i18n = useI18n()
  const t = i18n.t.account
  const [cursor, setCursor] = useState<{ occurredAt: string; id: string } | undefined>()
  const [items, setItems] = useState<import('../types/auth').SecurityEventResponse[]>([])
  const events = useSecurityEvents(cursor)
  useEffect(() => {
    if (events.data) setItems(cursor
      ? existing => [...existing, ...events.data!.items.filter(event => !existing.some(saved => saved.id === event.id))]
      : events.data.items)
  }, [events.data, cursor])
  const rows = items

  return (
    <WorkspaceSection title={t.securityHeading} headingId="account-security-events-heading">
      {events.isError ? <InlineAlert tone="danger">{t.securityLoadFailed}</InlineAlert> : null}
      {!events.isLoading && !events.isError && rows.length === 0
        ? <EmptyWorkspaceState compact title={t.noSecurityEvents} /> : null}
      {rows.length > 0 ? <ul className="account-security-timeline">
        {rows.map((event) => <li className={`account-security-event ${securityEventTone(event.type)}`} key={event.id}>
          <span className="activity-marker account-security-event-marker" aria-hidden="true" />
          <div className="activity-body account-security-event-body">
            <div className="activity-heading">
              <strong className="activity-title">{securityEventLabel(event.type, t)}</strong>
              <time className="activity-time" dateTime={event.occurredAt}>{i18n.format.dateTime(event.occurredAt)}</time>
            </div>
            {event.source ? <span className="activity-secondary">{event.source}</span> : null}
          </div>
        </li>)}
      </ul> : null}
      {events.data?.nextCursor ? <button type="button" className="secondary-button account-show-more"
        onClick={() => setCursor(events.data?.nextCursor)}>{t.showMore}</button> : null}
    </WorkspaceSection>
  )
}

function securityEventLabel(type: string, t: ReturnType<typeof useI18n>['t']['account']): string {
  const labels: Record<string, string> = {
    LOGIN_SUCCEEDED: t.securityEvents.loginSucceeded,
    PASSWORD_CHANGED: t.securityEvents.passwordChanged,
    SESSION_REVOKED: t.securityEvents.sessionRevoked,
    OTHER_SESSIONS_REVOKED: t.securityEvents.otherSessionsRevoked,
    ALL_SESSIONS_REVOKED: t.securityEvents.allSessionsRevoked,
  }
  return labels[type] ?? type
}

function securityEventTone(type: string): string {
  return type === 'LOGIN_SUCCEEDED' ? 'activity-positive' : 'activity-neutral'
}

function AccountSummary({ displayName, email, organizationCount }: {
  displayName: string
  email: string
  organizationCount: number
}) {
  const t = useI18n().t.account
  const name = displayName.trim() || email.trim()
  const initial = name[0]?.toLocaleUpperCase() ?? '?'
  return <div className="account-summary">
    <span className="account-summary-avatar" aria-hidden="true">{initial}</span>
    <div className="account-summary-body">
      <strong className="account-summary-name">{displayName || email}</strong>
      {displayName && email ? <span className="account-summary-email">{email}</span> : null}
      <span className="account-summary-meta">{t.organizationCount(organizationCount)}</span>
    </div>
  </div>
}

function ProfileSection(props: {
  displayName: string
  email: string
  organizations: MyOrganizationResponse[]
}) {
  const i18n = useI18n()
  const t = i18n.t.account
  const roles = i18n.t.auth.roles as Record<string, string>
  const update = useUpdateDisplayName()
  // Null until the field is edited, so it shows the loaded name and still tracks changes.
  const [edited, setEdited] = useState<string | null>(null)
  const name = edited ?? props.displayName
  const trimmed = name.trim()
  const unchanged = trimmed === props.displayName.trim()

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (update.isPending || trimmed === '' || unchanged) return
    update.mutate(trimmed, { onSuccess: () => setEdited(null) })
  }

  return (
    <WorkspaceSection title={t.profileHeading} headingId="account-profile-heading">
      <form className="account-fields" onSubmit={submit}>
        <label>{t.name}
          <input type="text" value={name} maxLength={255} autoComplete="name"
            onChange={(event) => setEdited(event.target.value)} />
        </label>
        <div className="account-field">
          <span className="account-field-label">{t.email}</span>
          <span className="account-field-value">{props.email}</span>
          <small className="muted">{t.emailHint}</small>
        </div>
        <div className="account-field">
          <span className="account-field-label">{t.organization}</span>
          {props.organizations.length === 0
            ? <span className="account-field-value">{t.noOrganizations}</span>
            : <ul className="account-org-list">
                {props.organizations.map((org) => <li key={org.id}>
                  <span className="account-org-name">{org.name}</span>
                  <StatusIndicator label={roles[org.role] ?? org.role} tone="neutral" />
                </li>)}
              </ul>}
        </div>
        {update.isError ? <InlineAlert tone="danger">{t.nameSaveFailed}</InlineAlert> : null}
        {update.isSuccess && edited === null ? <InlineAlert tone="success">{t.nameUpdated}</InlineAlert> : null}
        <div className="account-form-actions"><button className="primary-button" type="submit" disabled={update.isPending || trimmed === '' || unchanged}>
          {t.saveName}
        </button></div>
      </form>
    </WorkspaceSection>
  )
}

function PasswordSection() {
  const i18n = useI18n()
  const t = i18n.t.account
  const change = useChangePassword()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [mismatch, setMismatch] = useState(false)

  function clear() {
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // Double-submit guard: while a request is in flight nothing new is sent.
    if (change.isPending) return
    if (newPassword !== confirmPassword) {
      setMismatch(true)
      return
    }
    setMismatch(false)
    change.mutate({ currentPassword, newPassword }, { onSuccess: clear })
  }

  return (
    <WorkspaceSection title={t.changePassword} headingId="account-password-heading">
      <form className="account-fields" onSubmit={submit}>
        <label>{t.currentPassword}
          <input type="password" autoComplete="current-password" value={currentPassword} required
            onChange={(event) => setCurrentPassword(event.target.value)} />
        </label>
        <label>{t.newPassword}
          <input type="password" autoComplete="new-password" value={newPassword} required minLength={12}
            onChange={(event) => setNewPassword(event.target.value)} />
        </label>
        <label>{t.confirmPassword}
          <input type="password" autoComplete="new-password" value={confirmPassword} required
            onChange={(event) => setConfirmPassword(event.target.value)} />
        </label>
        <small className="muted">{t.passwordHint}</small>
        {mismatch ? <InlineAlert tone="danger">{t.passwordMismatch}</InlineAlert> : null}
        {change.isError ? <InlineAlert tone="danger">{describeError(change.error, i18n)}</InlineAlert> : null}
        {change.isSuccess ? <InlineAlert tone="success">{t.passwordUpdated}</InlineAlert> : null}
        <div className="account-form-actions"><button className="primary-button" type="submit" disabled={change.isPending}>{t.submit}</button></div>
      </form>
    </WorkspaceSection>
  )
}

function SessionsSection() {
  const i18n = useI18n()
  const t = i18n.t.account
  const navigate = useNavigate()
  const sessions = useSessions()
  const revokeOne = useRevokeSession()
  const revokeOthers = useRevokeOtherSessions()
  const revokeAll = useRevokeAllSessions()

  const rows = sessions.data ?? []
  const current = rows.find((session) => session.current)
  const others = rows.filter((session) => !session.current)
  // While any revocation is in flight, every sign-out control is disabled: no double submit.
  const busy = revokeOne.isPending || revokeOthers.isPending || revokeAll.isPending
  const actionFailed = revokeOne.isError || revokeOthers.isError || revokeAll.isError

  return (
    <WorkspaceSection title={t.sessionsHeading} headingId="account-sessions-heading">
      {sessions.isError ? <InlineAlert tone="danger">{t.sessionsLoadFailed}</InlineAlert> : null}
      {!sessions.isError ? <ul className="account-session-list">
        {current ? <li className="account-session-row account-session-current">
          <div className="account-session-body">
            <div className="account-session-title"><strong>{t.thisDevice}</strong><StatusIndicator label={t.currentSession} tone="success" /></div>
            <div className="account-session-meta"><span>{t.started}: {i18n.format.dateTime(current.createdAt)}</span>
              <span>{t.expires}: {i18n.format.dateTime(current.expiresAt)}</span></div>
          </div>
        </li> : null}
        {others.map((session) => <li className="account-session-row" key={session.id}>
          <div className="account-session-body">
            <strong className="account-session-title">{t.sessionItem}</strong>
            <span className="account-session-meta">{t.started}: {i18n.format.dateTime(session.createdAt)} · {t.expires}: {i18n.format.dateTime(session.expiresAt)}</span>
          </div>
          <button type="button" className="text-button account-session-action" disabled={busy}
            onClick={() => revokeOne.mutate(session.id)}>{t.signOutSession}</button>
        </li>)}
      </ul> : null}
      {sessions.data && others.length === 0 ? <EmptyWorkspaceState compact tone="success" title={t.noOtherSessions} /> : null}

      {actionFailed ? <InlineAlert tone="danger">{t.sessionActionFailed}</InlineAlert> : null}

      <div className="account-actions">
        <button type="button" className="secondary-button" disabled={busy || others.length === 0}
          onClick={() => revokeOthers.mutate()}>{t.signOutOthers}</button>
        <button type="button" className="danger-button" disabled={busy}
          onClick={() => revokeAll.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          {t.signOutAll}
        </button>
      </div>
    </WorkspaceSection>
  )
}
