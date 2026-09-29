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
      ?? (t.errors.codes as Record<string, string>)[code] ?? code
}

/** Only an InfraDesk NODE can be bound to an external control plane's node. */
export function supportsIntegrationBinding(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}
