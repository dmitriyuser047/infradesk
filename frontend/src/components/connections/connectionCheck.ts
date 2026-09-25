import type { SshAuthenticationType } from '../../types/connection'
import { endpointId, type Endpoint } from './hostTrust'

/**
 * Everything a connection check depends on. A check answers for the draft it was run with and for
 * no other: once any of these change, its result no longer describes the form.
 *
 * The credential is represented by a revision number that grows with every edit of a password,
 * key or passphrase, so the secret itself is never copied into this key.
 */
export interface CheckDraft extends Endpoint {
  username: string
  authenticationType: SshAuthenticationType
  /** The fingerprint the check trusts; confirming another key makes a new draft. */
  fingerprint: string
  credentialRevision: number
}

export function checkKey(draft: CheckDraft): string {
  return [endpointId(draft), draft.username.trim(), draft.authenticationType, draft.fingerprint, draft.credentialRevision].join('\n')
}

/** A finished check or key read, labelled with the draft (or endpoint) it was made for. */
export type BoundResult<T> = { key: string; value: T }

/**
 * The result, if it still describes the current draft. A response that arrives after the form moved
 * on — the user changed the host while the request was running — is kept but never shown as current.
 */
export function currentResult<T>(result: BoundResult<T> | null, key: string): T | null {
  return result !== null && result.key === key ? result.value : null
}
