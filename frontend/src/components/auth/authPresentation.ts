import { OrganizationRole } from '../../types/auth'

export function getOrganizationRoleLabel(role: string): string {
  switch (role) {
    case OrganizationRole.owner: return 'Owner'
    case OrganizationRole.member: return 'Member'
    default: return role
  }
}

export function safeReturnPath(value: unknown): string | null {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
    ? value
    : null
}
