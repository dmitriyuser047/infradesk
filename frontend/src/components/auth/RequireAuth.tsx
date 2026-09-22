import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Navigate, Outlet, useLocation } from 'react-router-dom'

import { useMe } from '../../api/auth'
import { ApiError } from '../../api/httpClient'

export function RequireAuth() {
  const me = useMe()
  const location = useLocation()
  const queryClient = useQueryClient()

  useEffect(() => {
    const recheck = () => { void queryClient.invalidateQueries({ queryKey: ['me'] }) }
    window.addEventListener('infradesk:unauthenticated', recheck)
    return () => window.removeEventListener('infradesk:unauthenticated', recheck)
  }, [queryClient])

  if (me.isPending) {
    return <div className="auth-loading" aria-label="Checking session">Checking session…</div>
  }

  if (me.isError) {
    if (me.error instanceof ApiError && me.error.status === 401 && me.error.code === 'UNAUTHENTICATED') {
      return <Navigate to="/login" replace state={{ from: location.pathname + location.search + location.hash }} />
    }
    return (
      <div className="auth-loading" role="alert">
        <p>Unable to check session</p>
        <button className="retry-button" type="button" onClick={() => me.refetch()}>Retry</button>
      </div>
    )
  }

  return <Outlet />
}
