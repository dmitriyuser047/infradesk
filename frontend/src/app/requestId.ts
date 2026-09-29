/**
 * A random UUID v4 for idempotent requests (`requestId`).
 *
 * `crypto.randomUUID` exists only in secure contexts, so a self-hosted installation opened over
 * plain HTTP by IP address (http://192.168.1.10:8080) has none. `crypto.getRandomValues` is
 * available there too, and gives the same cryptographic randomness; `Math.random` is never used.
 */
export function createRequestId(source: Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>> = crypto): string {
  if (typeof source.randomUUID === 'function') return source.randomUUID()
  const bytes = source.getRandomValues(new Uint8Array(16))
  // RFC 4122: version 4 in the high nibble of byte 6, variant 10xx in the high bits of byte 8.
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
