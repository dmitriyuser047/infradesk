import { ApiError } from '../api/httpClient'
import type { I18n } from './index'

/**
 * A user-facing sentence for a failed request.
 *
 * Backend error codes are translated; a code the dictionary does not know falls back to the
 * backend's own safe message in English and to a generic sentence in any other language, so a
 * Russian screen never shows an English server message.
 */
export function describeError(error: unknown, i18n: I18n): string {
  if (error instanceof ApiError) {
    const known = codeText(error.code, i18n)
    if (known !== null) return known
    if (i18n.locale === 'en' && error.message.trim() !== '') return error.message
  }
  return i18n.t.errors.generic
}

/** The same rule for a failure recorded on a sync session or an operation: a code plus a safe message. */
export function describeFailure(
  code: string | null | undefined,
  message: string | null | undefined,
  i18n: I18n,
  fallback: string,
): string {
  const known = code ? codeText(code, i18n) : null
  if (known !== null) return known
  if (i18n.locale === 'en' && message && message.trim() !== '') return message.trim()
  return fallback
}

export function codeText(code: string, i18n: I18n): string | null {
  return (i18n.t.errors.codes as Record<string, string>)[code] ?? null
}
