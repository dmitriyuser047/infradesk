import { useEffect } from 'react'
import { useMe, useMyOrganizations } from '../../api/auth'
import type { WorkspaceScope } from './workspaceNavigation'

/** A navigation preference, never an authorization grant or a replacement for route context. */
export function useRememberedWorkspace(): WorkspaceScope | undefined {
  const me = useMe()
  const memberships = useMyOrganizations()
  const key = me.isSuccess ? `infradesk.workspace.${me.data.id}` : undefined
  let remembered: WorkspaceScope | undefined
  try {
    const value: unknown = key ? JSON.parse(localStorage.getItem(key) ?? sessionStorage.getItem(key) ?? 'null') : null
    if (value && typeof value === 'object' && 'organizationId' in value && typeof value.organizationId === 'string') {
      remembered = { organizationId: value.organizationId,
        projectId: 'projectId' in value && typeof value.projectId === 'string' ? value.projectId : undefined,
        environmentId: 'environmentId' in value && typeof value.environmentId === 'string' ? value.environmentId : undefined }
    }
  } catch { /* Storage may be unavailable; the organization picker remains available. */ }
  const member = memberships.isSuccess ? memberships.data.find(item => item.id === remembered?.organizationId) : undefined
  useEffect(() => {
    if (key && memberships.isSuccess && remembered && !member) {
      try { localStorage.removeItem(key); sessionStorage.removeItem(key) } catch { /* Optional navigation preference. */ }
    }
  }, [key, memberships.isSuccess, remembered?.organizationId, member?.id])
  if (member) return remembered
  return undefined
}

export function rememberWorkspace(userId: string, scope: WorkspaceScope) {
  try { localStorage.setItem(`infradesk.workspace.${userId}`, JSON.stringify(scope)) }
  catch { /* Navigation must work even when browser storage is unavailable. */ }
}
