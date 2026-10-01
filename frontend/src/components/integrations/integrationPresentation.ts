import { ApiError } from '../../api/httpClient'
import type { I18n } from '../../i18n'
import { useI18n } from '../../i18n'
import type { StatusTone } from '../layout/WorkspacePrimitives'

/** Remnawave's own view of a node's connection; never the InfraDesk resource's status. */
export const nodeStateTones: Record<string, StatusTone> = {
  CONNECTED: 'success', CONNECTING: 'info', DISCONNECTED: 'danger', DISABLED: 'neutral',
}
export const sessionTones: Record<string, StatusTone> = { RUNNING: 'info', COMPLETED: 'success', FAILED: 'danger' }

/** A session's or request's error, in the reader's language, from its code alone. */
export function useSyncErrorText() {
  const { t } = useI18n()
  return (code: string | null) => code === null ? null
    : (t.integrations.errors as Record<string, string>)[code] ?? t.integrationInventory.errors[code]
      ?? (t.errors.codes as Record<string, string>)[code] ?? t.integrationDesiredState.codes[code] ?? t.integrationUi.unknownError
}

/** Only an InfraDesk NODE can be bound to an external control plane's node. */
export function supportsIntegrationBinding(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}

/** A sync result never supplies connection health. Only an explicit connection test does. */
export function connectionHealth(ok?: boolean, errorCode?: string): 'available' | 'unavailable' | 'unchecked' {
  if (ok !== undefined) return ok ? 'available' : 'unavailable'
  return errorCode && ['INTEGRATION_AUTH_FAILED', 'INTEGRATION_FORBIDDEN', 'INTEGRATION_ENDPOINT_NOT_FOUND',
    'INTEGRATION_RATE_LIMITED', 'INTEGRATION_TIMEOUT', 'INTEGRATION_UNREACHABLE', 'INTEGRATION_TLS_ERROR',
    'INTEGRATION_INVALID_RESPONSE', 'INTEGRATION_REMOTE_UNAVAILABLE'].includes(errorCode) ? 'unavailable' : 'unchecked'
}

/** Never surface a provider response body, a token or an unrecognized error code. */
export function describeIntegrationError(error: unknown, i18n: I18n): string {
  if (!(error instanceof ApiError)) return i18n.t.errors.generic
  const t = i18n.t
  return (t.integrations.errors as Record<string, string>)[error.code] ?? t.integrationInventory.errors[error.code]
    ?? t.integrationDesiredState.codes[error.code] ?? (t.errors.codes as Record<string, string>)[error.code]
    ?? t.integrationUi.unknownError
}
