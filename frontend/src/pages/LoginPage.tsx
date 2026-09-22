import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { useLogin } from '../api/auth'
import { ApiError } from '../api/httpClient'
import { safeReturnPath } from '../components/auth/authPresentation'

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const from = safeReturnPath((location.state as { from?: unknown } | null)?.from) ?? '/organizations'

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    login.mutate({ email, password }, {
      onSuccess: () => navigate(from, { replace: true }),
    })
  }

  return (
    <main className="login-page">
      <form className="login-panel" onSubmit={submit}>
        <p className="eyebrow">InfraDesk</p>
        <h1>Sign in</h1>
        <label>Email
          <input type="email" autoComplete="email" value={email} required onChange={(event) => setEmail(event.target.value)} />
        </label>
        <label>Password
          <input type="password" autoComplete="current-password" value={password} required onChange={(event) => setPassword(event.target.value)} />
        </label>
        {login.isError ? (
          <p className="form-error" role="alert">{login.error instanceof ApiError && login.error.code === 'INVALID_CREDENTIALS'
            ? 'Invalid email or password' : 'Unable to sign in'}</p>
        ) : null}
        <button className="primary-button" type="submit" disabled={login.isPending}>Sign in</button>
      </form>
    </main>
  )
}
