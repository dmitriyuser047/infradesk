/**
 * Which server the connection form trusts, as a draft.
 *
 * A confirmed fingerprint is a statement about one endpoint, host and port, never about the form
 * as a whole. Changing the address therefore leaves the new endpoint unconfirmed, even though the
 * stored key is still known; going back to the stored address restores its confirmation. A key read
 * from the server is likewise bound to the endpoint it was read from. Nothing here touches what is
 * stored: the saved fingerprint changes only when the form is saved, and the backend verifies the
 * endpoint on every connection regardless.
 */
export interface Endpoint {
  host: string
  port: number
}

export interface EndpointKey extends Endpoint {
  fingerprint: string
}

export type TrustState = 'trusted' | 'untrusted' | 'changed' | 'mismatch'

export function endpointId(endpoint: Endpoint): string {
  return `${endpoint.host.trim()}:${endpoint.port}`
}

export function sameEndpoint(left: Endpoint, right: Endpoint): boolean {
  return endpointId(left) === endpointId(right)
}

/** The confirmations the form starts with: the stored endpoint and its key, if the server is trusted. */
export function initialConfirmations(stored: EndpointKey | null): Record<string, string> {
  return stored === null || stored.fingerprint === '' ? {} : { [endpointId(stored)]: stored.fingerprint }
}

/** An explicit confirmation by the person editing: only this endpoint becomes trusted. */
export function confirm(confirmations: Record<string, string>, key: EndpointKey): Record<string, string> {
  return { ...confirmations, [endpointId(key)]: key.fingerprint }
}

export interface HostTrust {
  state: TrustState
  /** The fingerprint confirmed for the current endpoint, or null when it is not confirmed. */
  confirmed: string | null
  /** The key the current endpoint presented when it was last read, if it was read at this address. */
  presented: string | null
}

export function hostTrust({ current, stored, confirmations, probed }: {
  current: Endpoint
  stored: EndpointKey | null
  confirmations: Record<string, string>
  probed: EndpointKey | null
}): HostTrust {
  const confirmed = confirmations[endpointId(current)] ?? null
  const presented = probed !== null && sameEndpoint(probed, current) ? probed.fingerprint : null
  const state: TrustState = confirmed !== null && presented !== null && presented !== confirmed ? 'mismatch'
    : confirmed !== null ? 'trusted'
      : stored !== null && stored.fingerprint !== '' && !sameEndpoint(stored, current) ? 'changed'
        : 'untrusted'
  return { state, confirmed, presented }
}
