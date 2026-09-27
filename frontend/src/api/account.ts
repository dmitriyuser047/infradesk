import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson, requestVoid } from './httpClient'
import type { MeResponse, SessionResponse } from '../types/auth'

/** Change the signed-in user's password. The account is the session's; the body says only what to
 * change. Returns nothing, and the credentials are never cached. */
export function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  return requestVoid('/api/v1/account/change-password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

/** Update the signed-in user's display name; the server answers with the stored account. */
export function updateDisplayName(displayName: string): Promise<MeResponse> {
  return requestJson<MeResponse>('/api/v1/account', {
    method: 'PATCH',
    body: JSON.stringify({ displayName }),
  })
}

export function getSessions(): Promise<SessionResponse[]> {
  return requestJson<SessionResponse[]>('/api/v1/account/sessions')
}

export function revokeSession(sessionId: string): Promise<void> {
  return requestVoid(`/api/v1/account/sessions/${sessionId}`, { method: 'DELETE' })
}

export function revokeOtherSessions(): Promise<void> {
  return requestVoid('/api/v1/account/sessions/revoke-others', { method: 'POST' })
}

export function revokeAllSessions(): Promise<void> {
  return requestVoid('/api/v1/account/sessions/revoke-all', { method: 'POST' })
}

export function useSessions() {
  return useQuery({ queryKey: ['account-sessions'], queryFn: getSessions })
}

export function useChangePassword() {
  const queryClient = useQueryClient()
  // No onSuccess cache write of the credentials: a password and its confirmation never enter the
  // cache. Other sessions are revoked server-side, so the list is refetched.
  return useMutation({
    mutationFn: ({ currentPassword, newPassword }: { currentPassword: string; newPassword: string }) =>
      changePassword(currentPassword, newPassword),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['account-sessions'] }),
  })
}

export function useUpdateDisplayName() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (displayName: string) => updateDisplayName(displayName),
    onSuccess: (me) => queryClient.setQueryData(['me'], me),
  })
}

export function useRevokeSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (sessionId: string) => revokeSession(sessionId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['account-sessions'] }),
  })
}

export function useRevokeOtherSessions() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: revokeOtherSessions,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['account-sessions'] }),
  })
}

/** Sign out from all devices, the current one included. The cookie is already cleared by the
 * server; the whole cache is dropped so nothing survives the redirect to login. */
export function useRevokeAllSessions() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: revokeAllSessions,
    onSuccess: () => queryClient.removeQueries(),
  })
}
