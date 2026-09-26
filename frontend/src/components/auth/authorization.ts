import { useMyOrganizations } from '../../api/auth'
import { OrganizationRole } from '../../types/auth'

/** The capabilities the backend policy defines, named the same way on this side. */
export type OrganizationPermission =
  | 'readOrganization'
  | 'manageWorkspace'
  | 'manageConnections'
  | 'runConnectionSync'
  | 'manageMonitoring'
  | 'manageNotifications'
  | 'viewAudit'
  | 'executeOperations'

const memberPermissions: readonly OrganizationPermission[] = ['readOrganization']

/**
 * The single place where a role becomes a capability in the UI.
 *
 * This only decides what to show: the backend authorizes every request regardless, so a stale or
 * forged role here cannot grant anything.
 */
export function canOrganization(
  role: string | undefined,
  permission: OrganizationPermission,
): boolean {
  if (role === OrganizationRole.owner) {
    return true
  }

  if (role === OrganizationRole.member) {
    return memberPermissions.includes(permission)
  }

  return false
}

export interface OrganizationPermissions {
  role: string | undefined
  isPending: boolean
  can: (permission: OrganizationPermission) => boolean
}

/** Reads the role of the current organization from the memberships the app already loads. */
export function useOrganizationPermissions(organizationId: string | undefined): OrganizationPermissions {
  const memberships = useMyOrganizations()
  const role = memberships.data?.find(value => value.id === organizationId)?.role

  return { role, isPending: memberships.isPending, can: permission => canOrganization(role, permission) }
}
