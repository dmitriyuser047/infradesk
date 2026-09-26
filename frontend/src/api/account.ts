import { useMutation, useQueryClient } from '@tanstack/react-query'

import { requestJson, requestVoid } from './httpClient'
import type { MeResponse } from '../types/auth'

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

export function useChangePassword() {
  // No onSuccess cache write: a password and its confirmation never enter the query cache.
  return useMutation({
    mutationFn: ({ currentPassword, newPassword }: { currentPassword: string; newPassword: string }) =>
      changePassword(currentPassword, newPassword),
  })
}

export function useUpdateDisplayName() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (displayName: string) => updateDisplayName(displayName),
    // The current-user cache is the one place the name lives, so the new name replaces it there.
    onSuccess: (me) => queryClient.setQueryData(['me'], me),
  })
}
