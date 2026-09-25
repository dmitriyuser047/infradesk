import type { I18n } from '../../i18n'

export function getOrganizationRoleLabel(role: string, i18n: I18n): string {
  return (i18n.t.auth.roles as Record<string, string>)[role] ?? role
}

export function safeReturnPath(value: unknown): string | null {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
    ? value
    : null
}
