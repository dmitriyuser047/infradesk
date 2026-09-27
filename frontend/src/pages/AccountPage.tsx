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
    <main className="account-page">
      <header>
        <h1>{t.title}</h1>
        <p className="muted">{t.subtitle}</p>
      </header>

      <ProfileSection displayName={me.data?.displayName ?? ''} email={me.data?.email ?? ''}
        organizations={organizations.data ?? []} />
      <PasswordSection />
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
    if (events.data) setItems(cursor ? existing => [...existing, ...events.data!.items] : events.data.items)
  }, [events.data, cursor])
  const rows = items

  return (
    <section className="account-panel" aria-labelledby="account-security-events-heading">
      <h2 id="account-security-events-heading">{t.securityHeading}</h2>
      {events.isError ? <p className="form-error" role="alert">{t.securityLoadFailed}</p> : null}
      {!events.isLoading && rows.length === 0 ? <p className="muted">{t.noSecurityEvents}</p> : null}
      <ul className="account-sessions">
        {rows.map((event) => <li key={event.id}>
          <span>{securityEventLabel(event.type, t)}</span>
          <small className="muted">{i18n.format.dateTime(event.occurredAt)}{event.source ? ` · ${event.source}` : ''}</small>
        </li>)}
      </ul>
      {events.data?.nextCursor ? <button type="button" className="secondary-button"
        onClick={() => setCursor(events.data?.nextCursor)}>{t.showMore}</button> : null}
    </section>
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
    <section className="account-panel" aria-labelledby="account-profile-heading">
      <h2 id="account-profile-heading">{t.profileHeading}</h2>
      <form onSubmit={submit}>
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
            : <ul className="account-orgs">
                {props.organizations.map((org) => <li key={org.id}>
                  {org.name} — {roles[org.role] ?? org.role}
                </li>)}
              </ul>}
        </div>
        {update.isError ? <p className="form-error" role="alert">{t.nameSaveFailed}</p> : null}
        {update.isSuccess && edited === null ? <p className="form-success" role="status">{t.nameUpdated}</p> : null}
        <button className="primary-button" type="submit" disabled={update.isPending || trimmed === '' || unchanged}>
          {t.saveName}
        </button>
      </form>
    </section>
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
    <section className="account-panel" aria-labelledby="account-password-heading">
      <h2 id="account-password-heading">{t.changePassword}</h2>
      <form onSubmit={submit}>
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
        {mismatch ? <p className="form-error" role="alert">{t.passwordMismatch}</p> : null}
        {change.isError ? <p className="form-error" role="alert">{describeError(change.error, i18n)}</p> : null}
        {change.isSuccess ? <p className="form-success" role="status">{t.passwordUpdated}</p> : null}
        <button className="primary-button" type="submit" disabled={change.isPending}>{t.submit}</button>
      </form>
    </section>
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
    <section className="account-panel" aria-labelledby="account-sessions-heading">
      <h2 id="account-sessions-heading">{t.sessionsHeading}</h2>
      {sessions.isError ? <p className="form-error" role="alert">{t.sessionsLoadFailed}</p> : null}

      {current ? <div className="account-field">
        <span className="account-field-label">{t.currentSession}</span>
        <span className="account-field-value">{t.thisDevice}</span>
        <small className="muted">{t.started}: {i18n.format.dateTime(current.createdAt)} · {t.expires}: {i18n.format.dateTime(current.expiresAt)}</small>
      </div> : null}

      <div className="account-field">
        <span className="account-field-label">{t.otherSessions}</span>
        {others.length === 0
          ? <span className="account-field-value">{t.noOtherSessions}</span>
          : <ul className="account-sessions">
              {others.map((session) => <li key={session.id}>
                <span>{i18n.format.dateTime(session.createdAt)}</span>
                <button type="button" className="link-button" disabled={busy}
                  onClick={() => revokeOne.mutate(session.id)}>{t.signOutSession}</button>
              </li>)}
            </ul>}
      </div>

      {actionFailed ? <p className="form-error" role="alert">{t.sessionActionFailed}</p> : null}

      <div className="account-actions">
        <button type="button" className="secondary-button" disabled={busy || others.length === 0}
          onClick={() => revokeOthers.mutate()}>{t.signOutOthers}</button>
        <button type="button" className="secondary-button" disabled={busy}
          onClick={() => revokeAll.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          {t.signOutAll}
        </button>
      </div>
    </section>
  )
}
