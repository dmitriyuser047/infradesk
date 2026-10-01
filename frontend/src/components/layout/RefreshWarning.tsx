import { useI18n } from '../../i18n'
import { InlineAlert } from './WorkspacePrimitives'
import { ApiError } from '../../api/httpClient'

/** An unavailable or unauthorized object must not stay visible as a stale snapshot. */
export function isUnavailableError(error: unknown): boolean {
  return error instanceof ApiError && [401, 403, 404].includes(error.status)
}

/** Failed background reads leave their last successful snapshot on screen. */
export function RefreshWarning({ updatedAt, retry }: { updatedAt: number; retry: () => void }) {
  const i18n = useI18n()
  return <InlineAlert tone="warning" title={i18n.t.workScreens.refreshError}
    action={<button className="secondary-button" type="button" onClick={retry}>{i18n.t.common.retry}</button>}>
    {updatedAt > 0 ? i18n.t.workScreens.staleSince(i18n.format.dateTime(new Date(updatedAt).toISOString())) : undefined}
  </InlineAlert>
}
