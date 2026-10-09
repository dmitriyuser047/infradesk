import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { useLogin } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { safeReturnPath } from '../components/auth/authPresentation'
import { InfraDeskMark } from '../components/layout/InfraDeskMark'
import { useI18n, type Messages } from '../i18n'

/** A login failure is deliberately vague about the account, but a rate-limit is worth naming so a
 * person knows to wait rather than keep trying. */
function loginErrorMessage(error: unknown, t: Messages): string {
  if (error instanceof ApiError && error.code === 'LOGIN_RATE_LIMITED') return t.auth.tooManyAttempts
  if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') return t.auth.invalidCredentials
  return t.auth.unableToSignIn
}

export function LoginPage() {
  const { t } = useI18n()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const from = safeReturnPath((location.state as { from?: unknown } | null)?.from) ?? '/'

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    login.mutate({ email, password }, {
      onSuccess: () => navigate(from, { replace: true }),
    })
  }

  return (
    <main className="login-page">
      <form className="login-panel" onSubmit={submit}>
        <p className="eyebrow"><InfraDeskMark />InfraDesk</p>
        <h1>{t.auth.signIn}</h1>
        <label>{t.auth.email}
          <input type="email" autoComplete="email" value={email} required onChange={(event) => setEmail(event.target.value)} />
        </label>
        <label>{t.auth.password}
          <input type="password" autoComplete="current-password" value={password} required onChange={(event) => setPassword(event.target.value)} />
        </label>
        {login.isError ? (
          <p className="form-error" role="alert">{loginErrorMessage(login.error, t)}</p>
        ) : null}
        <button className="primary-button" type="submit" disabled={login.isPending}>{t.auth.signIn}</button>
      </form>
    </main>
  )
}
