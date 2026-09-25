import { describe, expect, it } from 'vitest'

import { confirm, hostTrust, initialConfirmations, type EndpointKey } from './hostTrust'

const stored: EndpointKey = { host: 'example.test', port: 22, fingerprint: 'SHA256:stored' }

function trust(current: { host: string; port: number }, confirmations = initialConfirmations(stored),
  probed: EndpointKey | null = null) {
  return hostTrust({ current, stored, confirmations, probed })
}

describe('host trust in the connection draft', () => {
  it('trusts the stored endpoint with its stored key', () => {
    expect(trust({ host: 'example.test', port: 22 })).toEqual({ state: 'trusted', confirmed: 'SHA256:stored', presented: null })
  })

  it('does not carry the stored key over to a changed host or port', () => {
    expect(trust({ host: 'other.test', port: 22 })).toEqual({ state: 'changed', confirmed: null, presented: null })
    expect(trust({ host: 'example.test', port: 2222 })).toEqual({ state: 'changed', confirmed: null, presented: null })
  })

  it('trusts a new endpoint only after its key is read and explicitly confirmed', () => {
    const probed = { host: 'other.test', port: 22, fingerprint: 'SHA256:other' }
    expect(trust({ host: 'other.test', port: 22 }, undefined, probed))
      .toEqual({ state: 'changed', confirmed: null, presented: 'SHA256:other' })
    expect(trust({ host: 'other.test', port: 22 }, confirm(initialConfirmations(stored), probed), probed))
      .toEqual({ state: 'trusted', confirmed: 'SHA256:other', presented: 'SHA256:other' })
  })

  it('restores the stored trust when the address goes back to the stored one', () => {
    const probed = { host: 'other.test', port: 22, fingerprint: 'SHA256:other' }
    const confirmations = confirm(initialConfirmations(stored), probed)
    expect(trust({ host: 'example.test', port: 22 }, confirmations, probed).state).toBe('trusted')
    expect(trust({ host: ' example.test ', port: 22 }).confirmed).toBe('SHA256:stored')
  })

  it('ignores a key read at another address and flags a different key at the confirmed one', () => {
    expect(trust({ host: 'example.test', port: 22 }, undefined, { host: 'other.test', port: 22, fingerprint: 'SHA256:x' }))
      .toEqual({ state: 'trusted', confirmed: 'SHA256:stored', presented: null })
    expect(trust({ host: 'example.test', port: 22 }, undefined, { ...stored, fingerprint: 'SHA256:x' }).state).toBe('mismatch')
  })

  it('leaves a new connection unconfirmed rather than changed', () => {
    expect(hostTrust({ current: { host: 'a.test', port: 22 }, stored: null, confirmations: {}, probed: null }).state)
      .toBe('untrusted')
  })
})
