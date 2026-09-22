import { describe, expect, it } from 'vitest'

import { buildSshConnectionRequest, type SshConnectionFormValues } from './buildSshConnectionRequest'

const base: SshConnectionFormValues = {
  code: ' prod-vps ', name: ' Production VPS ', projectId: 'project', environmentId: 'environment',
  host: '  example.org ', port: '22', username: ' root ', password: 'secret',
  scheduleEnabled: true, intervalSeconds: '60',
}

describe('SSH connection request builder', () => {
  it('builds an environment-scoped password request', () => {
    const result = buildSshConnectionRequest(base)
    expect(result.scope).toEqual({ type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' })
    expect(result.ssh).toEqual({ host: 'example.org', port: 22, username: 'root' })
    expect(result.credentials).toEqual({ type: 'PASSWORD', password: 'secret' })
  })

  it('omits credentials when editing with a blank password', () => {
    const result = buildSshConnectionRequest({ ...base, environmentId: '', password: '' })
    expect(result.scope).toEqual({ type: 'PROJECT', projectId: 'project' })
    expect(result).not.toHaveProperty('credentials')
  })
})
