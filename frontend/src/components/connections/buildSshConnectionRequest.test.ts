import { describe, expect, it } from 'vitest'

import {
  buildSshConnectionRequest,
  buildSshCredentials,
  type SshConnectionFormValues,
} from './buildSshConnectionRequest'

const base: SshConnectionFormValues = {
  code: ' prod-vps ', name: ' Production VPS ', projectId: 'project', environmentId: 'environment',
  host: '  example.org ', port: '22', username: ' root ',
  authenticationType: 'PASSWORD', password: 'secret', privateKey: '', passphrase: '',
  hostKeyFingerprint: ' SHA256:trusted ',
  scheduleEnabled: true, intervalSeconds: '600',
}

describe('SSH connection request builder', () => {
  it('builds an environment-scoped password request with the confirmed host identity', () => {
    const result = buildSshConnectionRequest(base)
    expect(result.scope).toEqual({ type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' })
    expect(result.ssh).toEqual({
      host: 'example.org', port: 22, username: 'root', authenticationType: 'PASSWORD',
      hostKeyFingerprint: 'SHA256:trusted',
    })
    expect(result.credentials).toEqual({ type: 'PASSWORD', password: 'secret' })
  })

  it('builds a private key request and keeps the passphrase optional', () => {
    const withKey = { ...base, authenticationType: 'PRIVATE_KEY' as const, password: '',
      privateKey: '-----BEGIN OPENSSH PRIVATE KEY-----\n', passphrase: 'unlock' }

    expect(buildSshConnectionRequest(withKey).credentials).toEqual({
      type: 'PRIVATE_KEY',
      privateKey: '-----BEGIN OPENSSH PRIVATE KEY-----\n',
      passphrase: 'unlock',
    })
    expect(buildSshConnectionRequest({ ...withKey, passphrase: '' }).credentials).toEqual({
      type: 'PRIVATE_KEY',
      privateKey: '-----BEGIN OPENSSH PRIVATE KEY-----\n',
    })
  })

  it('omits credentials when editing without entering one, whichever method is selected', () => {
    // An empty field means "keep the stored credential"; it never means "clear it".
    const password = buildSshConnectionRequest({ ...base, environmentId: '', password: '' })
    const key = buildSshConnectionRequest({
      ...base, environmentId: '', authenticationType: 'PRIVATE_KEY', password: '', privateKey: '   ',
    })

    expect(password.scope).toEqual({ type: 'PROJECT', projectId: 'project' })
    expect(password).not.toHaveProperty('credentials')
    expect(key).not.toHaveProperty('credentials')
    expect(key.ssh.authenticationType).toBe('PRIVATE_KEY')
    expect(buildSshCredentials({ ...base, password: '' })).toBeUndefined()
  })

  it('leaves the host fingerprint out until one has been confirmed', () => {
    const result = buildSshConnectionRequest({ ...base, hostKeyFingerprint: '   ' })

    expect(result.ssh).toEqual({
      host: 'example.org', port: 22, username: 'root', authenticationType: 'PASSWORD',
    })
    expect(result.ssh).not.toHaveProperty('hostKeyFingerprint')
  })

  it('rejects intervals below 300 seconds and accepts 300 or 600', () => {
    expect(() => buildSshConnectionRequest({ ...base, intervalSeconds: '299' })).toThrow(/300 seconds/)
    expect(buildSshConnectionRequest({ ...base, intervalSeconds: '300' }).schedule.intervalSeconds).toBe(300)
    expect(buildSshConnectionRequest(base).schedule.intervalSeconds).toBe(600)
  })
})
